package com.shiroha.mmdskin.render.pipeline;

import java.util.ArrayDeque;
import java.util.Deque;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL33C;

/** 固定容量的非阻塞 GPU timer query 池。 */
public final class GpuTimerQueryPool {
    private static final int MAX_QUERIES = 32;
    private static final GpuTimerQueryPool COMPUTE = new GpuTimerQueryPool(RenderPerformanceProfiler.GPU_SECTION_COMPUTE);
    private static final GpuTimerQueryPool DRAW = new GpuTimerQueryPool(RenderPerformanceProfiler.GPU_SECTION_DRAW);

    private final String section;
    private final Deque<Integer> available = new ArrayDeque<>(MAX_QUERIES);
    private final Deque<Integer> pending = new ArrayDeque<>(MAX_QUERIES);
    private boolean initialized;
    private boolean supported = true;
    private int activeQuery;
    private GLCapabilities ownerCapabilities;
    private GLCapabilities lastCheckedCapabilities;
    private boolean wasSamplingEnabled;
    private long samplingGeneration = Long.MIN_VALUE;

    private GpuTimerQueryPool(String section) { this.section = section; }

    public static GpuTimerQueryPool compute() { return COMPUTE; }
    public static GpuTimerQueryPool draw() { return DRAW; }

    /** 渲染入口采样关闭时清掉两池的延迟样本，避免重开后混入旧采样窗口。 */
    public static void releasePendingWhenSamplingDisabled() {
        if (RenderPerformanceProfiler.get().isSamplingEnabled()) return;
        COMPUTE.onSamplingDisabled();
        DRAW.onSamplingDisabled();
    }

    public static void disposeAll() {
        COMPUTE.dispose();
        DRAW.dispose();
    }

    /** 查询池耗尽、宿主 query 活跃或采样关闭时都跳过，不等待 GPU。 */
    public boolean begin() {
        boolean samplingEnabled = RenderPerformanceProfiler.get().isSamplingEnabled();
        if (!samplingEnabled) {
            onSamplingDisabled();
            return false;
        }
        wasSamplingEnabled = true;
        long currentSamplingGeneration = RenderPerformanceProfiler.get().samplingGeneration();
        GLCapabilities capabilities;
        try {
            capabilities = GL.getCapabilities();
        } catch (RuntimeException ignored) {
            return false;
        }
        refreshContext(capabilities);
        if (samplingGeneration != currentSamplingGeneration) {
            if (samplingGeneration != Long.MIN_VALUE) discardPendingIfPossible();
            samplingGeneration = currentSamplingGeneration;
        }
        if (activeQuery != 0 || !ensureInitialized(capabilities)) return false;
        collectAvailableResults();
        if (available.isEmpty()) return false;
        try {
            // GL_TIME_ELAPSED 是每上下文全局目标；不嵌套，也不覆盖 Minecraft/Iris 的计时。
            if (GL33C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY) != 0) return false;
            int query = available.peekFirst();
            GL33C.glBeginQuery(GL33C.GL_TIME_ELAPSED, query);
            available.removeFirst();
            activeQuery = query;
            return true;
        } catch (RuntimeException ignored) {
            // 能力在上下文切换期间失效时跳过本次样本。
            return false;
        }
    }

    private void onSamplingDisabled() {
        if (wasSamplingEnabled) discardPendingIfPossible();
        wasSamplingEnabled = false;
    }

    public void end() {
        int query = activeQuery;
        if (query == 0) return;
        activeQuery = 0;
        try {
            if (GL.getCapabilities() != ownerCapabilities) {
                discardQuery(query);
                return;
            }
            if (GL33C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY) == query) {
                GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED);
                pending.addLast(query);
            } else {
                // 目标已由外部结束或上下文改变；丢弃该 query，绝不结束宿主的计时。
                discardQuery(query);
            }
        } catch (RuntimeException ignored) {
            discardQuery(query);
        }
    }

    /** 调用方必须在创建 query 的渲染上下文中调用。 */
    public void dispose() {
        if (!initialized || ownerCapabilities == null) return;
        try {
            if (GL.getCapabilities() != ownerCapabilities) {
                resetForContextChange();
                return;
            }
            if (activeQuery != 0) {
                int query = activeQuery;
                if (GL33C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY) == query) {
                    GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED);
                }
                GL33C.glDeleteQueries(query);
            }
            activeQuery = 0;
            for (int query : available) GL33C.glDeleteQueries(query);
            for (int query : pending) GL33C.glDeleteQueries(query);
            available.clear();
            pending.clear();
            initialized = false;
            supported = true;
            ownerCapabilities = null;
        } catch (RuntimeException ignored) {
            resetForContextChange();
        }
    }

    private boolean ensureInitialized(GLCapabilities capabilities) {
        if (initialized || !supported) return supported;
        if (!capabilities.OpenGL33 && !capabilities.GL_ARB_timer_query) {
            supported = false;
            return false;
        }
        ownerCapabilities = capabilities;
        try {
            for (int i = 0; i < MAX_QUERIES; i++) {
                int query = GL33C.glGenQueries();
                if (query == 0) throw new IllegalStateException("OpenGL returned query name 0");
                available.addLast(query);
            }
            initialized = true;
            return true;
        } catch (RuntimeException exception) {
            // 若初始化中途失败，释放已创建的对象（仍在创建时的上下文）。
            clearPartialInitialization();
            supported = false;
            lastCheckedCapabilities = capabilities;
            return false;
        }
    }

    private void refreshContext(GLCapabilities capabilities) {
        if (ownerCapabilities != null && ownerCapabilities != capabilities) resetForContextChange();
        if (lastCheckedCapabilities != capabilities) {
            lastCheckedCapabilities = capabilities;
            supported = true;
        }
    }

    private void discardPendingIfPossible() {
        if (pending.isEmpty()) return;
        try {
            if (ownerCapabilities == null || GL.getCapabilities() != ownerCapabilities) {
                resetForContextChange();
                return;
            }
            // 丢弃旧样本并复用 query 名；重新 begin 会覆盖旧结果，不发生读回或等待。
            while (!pending.isEmpty()) available.addLast(pending.removeFirst());
        } catch (RuntimeException ignored) {
            // 下次上下文代次检测会丢弃旧名称；不等待正在执行的 GPU 工作。
        }
    }

    private void collectAvailableResults() {
        while (!pending.isEmpty()) {
            int query = pending.peekFirst();
            if (GL33C.glGetQueryObjecti(query, GL33C.GL_QUERY_RESULT_AVAILABLE) == 0) break;
            pending.removeFirst();
            long elapsedNanos = GL33C.glGetQueryObjecti64(query, GL33C.GL_QUERY_RESULT);
            // GPU 时间是异步独立样本，不属于 poll 时的 CPU 帧。
            RenderPerformanceProfiler.get().recordGpuTime(section, elapsedNanos);
            available.addLast(query);
        }
    }

    private void clearPartialInitialization() {
        if (ownerCapabilities != null) {
            try {
                if (GL.getCapabilities() == ownerCapabilities) {
                    for (int query : available) GL33C.glDeleteQueries(query);
                    for (int query : pending) GL33C.glDeleteQueries(query);
                }
            } catch (RuntimeException ignored) { }
        }
        available.clear();
        pending.clear();
        activeQuery = 0;
        initialized = false;
        ownerCapabilities = null;
    }

    private void discardQuery(int query) {
        try {
            if (ownerCapabilities != null && GL.getCapabilities() == ownerCapabilities) GL33C.glDeleteQueries(query);
        } catch (RuntimeException ignored) { }
    }

    private void resetForContextChange() {
        // Query 名称属于旧上下文，不能通过新上下文删除。
        available.clear();
        pending.clear();
        activeQuery = 0;
        initialized = false;
        supported = true;
        ownerCapabilities = null;
        lastCheckedCapabilities = null;
    }
}
