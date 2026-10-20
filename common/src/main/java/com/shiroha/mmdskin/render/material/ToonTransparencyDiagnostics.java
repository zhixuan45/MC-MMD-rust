package com.shiroha.mmdskin.render.material;

import com.google.gson.Gson;
import com.shiroha.mmdskin.render.pipeline.RenderFrameClock;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL46C;

/** 有界日志及延迟读取的尾部查询；查询结果从不参与本帧绘制决策。 */
public final class ToonTransparencyDiagnostics {
    public enum Reason {
        ACTIVE, BUDGET_REDUCED, DISABLED, INVALID_COUNT, WRONG_THREAD, UNSUPPORTED_GL,
        ACTIVE_QUERY, NO_PROGRAM, MISSING_UNIFORMS, TEXTURE_UNITS, DEFAULT_FRAMEBUFFER,
        STENCIL_TEST, NO_DEPTH, DEPTH_TARGET, MULTISAMPLE, VIEWPORT, DEPTH_FORMAT,
        DEPTH_COPY_FAILED, DEPTH_FUNCTION, BUDGET_EXHAUSTED, POOL_FORMAT_CONFLICT, ALLOCATION_FAILED, EXCEPTION
    }

    public record Snapshot(int schemaVersion, boolean enabled, Reason reason, int requestedLayers, int actualLayers,
                           int width, int height, int depthFormat, int samples,
                           int resolvedDepthFormat, int depthBits, int stencilBits, int componentType, int attachmentType,
                           long budgetBytes, long allocatedBytes, int allocatedWidth, int allocatedHeight,
                           int allocatedLayers, boolean depthSealDisabled) { }
    public record Overflow(long frame, long submission, int layers, boolean remainingFragments) { }
    private static final Gson JSON = new Gson();
    private static final Slot[] SLOTS = {new Slot(), new Slot(), new Slot(), new Slot()};
    private static Snapshot latest, logged;
    private static Overflow overflow;
    private static long lastLog, lastOverflowLog, submissions;

    private ToonTransparencyDiagnostics() { }

    static final class Attempt {
        final int requested;
        int width, height, format, samples, resolvedFormat, depthBits, stencilBits, componentType, attachmentType;
        Reason reason = Reason.DEPTH_TARGET;
        Attempt(int requested) { this.requested = requested; }
        void publish(Reason reason, int actual) {
            latest = new Snapshot(2, actual > 0, reason, requested, actual, width, height, format, samples,
                    resolvedFormat, depthBits, stencilBits, componentType, attachmentType,
                    ToonTransparencySettings.MAX_BYTES, ToonTransparencyLayers.allocatedBytes(),
                    ToonTransparencyLayers.allocatedWidth(), ToonTransparencyLayers.allocatedHeight(),
                    ToonTransparencyLayers.allocatedLayers(),
                    ToonTransparencySettings.disableDepthSeal());
            long now = System.nanoTime();
            if (ToonTransparencySettings.diagnostics() && !latest.equals(logged)
                    && (logged == null || now - lastLog >= 5_000_000_000L)) {
                LogManager.getLogger().info("[MMD透明分层] {}", JSON.toJson(latest));
                logged = latest;
                lastLog = now;
            }
        }
    }

    private static final class Slot {
        int query;
        boolean pending;
        long frame, submission;
        int layers;
    }

    public static Snapshot latest() { return latest; }
    public static Overflow latestOverflow() { return overflow; }

    static int beginTail(int layers) {
        if (!ToonTransparencySettings.diagnostics()) return 0;
        poll();
        for (Slot slot : SLOTS) {
            if (slot.pending) continue;
            if (slot.query == 0) slot.query = GL46C.glGenQueries();
            if (slot.query == 0) return 0;
            slot.frame = RenderFrameClock.current();
            slot.submission = ++submissions;
            slot.layers = layers;
            slot.pending = true;
            GL46C.glBeginQuery(GL46C.GL_ANY_SAMPLES_PASSED, slot.query);
            return slot.query;
        }
        return 0; // GPU 落后时跳过采样，绝不阻塞或覆盖尚未完成的查询。
    }

    /** 仅查询 ready 后读取；暴露给离线探针，不调用 glFinish。 */
    public static void poll() {
        for (Slot slot : SLOTS) {
            if (!slot.pending || GL46C.glGetQueryObjecti(slot.query, GL46C.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            Overflow result = new Overflow(slot.frame, slot.submission, slot.layers,
                    GL46C.glGetQueryObjecti(slot.query, GL46C.GL_QUERY_RESULT) != 0);
            if (overflow == null || result.submission() > overflow.submission()) overflow = result;
            slot.pending = false;
            long now = System.nanoTime();
            if (ToonTransparencySettings.diagnostics() && now - lastOverflowLog >= 5_000_000_000L) {
                LogManager.getLogger().info("[MMD透明余量] {}", JSON.toJson(result));
                lastOverflowLog = now;
            }
        }
    }

    static void cancel(int query) {
        for (Slot slot : SLOTS) if (slot.query == query) slot.pending = false;
    }

    static void cleanup() {
        for (Slot slot : SLOTS) {
            if (slot.query != 0) GL46C.glDeleteQueries(slot.query);
            slot.query = 0;
            slot.pending = false;
        }
        latest = logged = null;
        overflow = null;
        lastLog = lastOverflowLog = submissions = 0;
    }
}
