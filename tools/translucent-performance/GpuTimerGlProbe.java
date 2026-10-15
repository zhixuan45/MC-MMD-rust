import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.render.pipeline.GpuTimerQueryPool;
import com.shiroha.mmdskin.render.pipeline.RenderPerformanceProfiler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.opengl.GL33C;

/** 在 CacheGlProbe 已创建的隐藏上下文中检查 timer query 所有权和生命周期。 */
public final class GpuTimerGlProbe {
    private GpuTimerGlProbe() { }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void checkNoGlError(String point) {
        int error = GL33C.glGetError();
        check(error == GL33C.GL_NO_ERROR, point + " GL error=0x" + Integer.toHexString(error));
    }

    private static ConfigManager.IConfigProvider config(AtomicBoolean profiling, AtomicBoolean hud, AtomicBoolean gpuLog) {
        return (ConfigManager.IConfigProvider) Proxy.newProxyInstance(
                ConfigManager.IConfigProvider.class.getClassLoader(),
                new Class<?>[]{ConfigManager.IConfigProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isPerformanceProfilingEnabled" -> profiling.get();
                    case "isDebugHudEnabled" -> hud.get();
                    case "isDebugGpuLogEnabled" -> gpuLog.get();
                    case "toString" -> "GpuTimerGlProbeConfig";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }

    public static void run() {
        AtomicBoolean profiling = new AtomicBoolean(false);
        AtomicBoolean hud = new AtomicBoolean(false);
        AtomicBoolean gpuLog = new AtomicBoolean(false);
        ConfigManager.init(config(profiling, hud, gpuLog));
        GpuTimerQueryPool compute = GpuTimerQueryPool.compute();
        GpuTimerQueryPool draw = GpuTimerQueryPool.draw();
        try {
            check(!RenderPerformanceProfiler.get().isSamplingEnabled(), "sampling starts disabled");
            check(!draw.begin(), "sampling off skips query begin");
            check(GL33C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY) == 0,
                    "sampling off leaves timer target idle");

            gpuLog.set(true);
            check(RenderPerformanceProfiler.get().isSamplingEnabled(), "GPU log alone enables sampling");
            check(draw.begin(), "GPU log alone begins a timer query");
            draw.end();
            gpuLog.set(false);
            check(!RenderPerformanceProfiler.get().isSamplingEnabled(), "GPU log alone can stop sampling");
            check(!draw.begin(), "GPU log off skips query begin");

            profiling.set(true); // 仅性能日志开启时也应采样。
            check(!hud.get() && RenderPerformanceProfiler.get().isSamplingEnabled(), "profiling without HUD");
            check(compute.begin(), "performance-only query begins");
            int outerQuery = GL33C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY);
            check(outerQuery != 0, "outer compute query is active");
            check(!compute.begin(), "same-pool nested begin is rejected");
            check(GL33C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY) == outerQuery,
                    "same-pool nested begin keeps outer query active");
            check(!draw.begin(), "cross-pool nested begin is rejected");
            check(GL33C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY) == outerQuery,
                    "cross-pool begin keeps outer query active");
            compute.end();
            check(GL33C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY) == 0,
                    "owned query ends once");

            int hostQuery = GL33C.glGenQueries();
            try {
                GL33C.glBeginQuery(GL33C.GL_TIME_ELAPSED, hostQuery);
                check(!draw.begin(), "host query prevents pool query");
                check(GL33C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY) == hostQuery,
                        "pool leaves host query active");
            } finally {
                if (GL33C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY) == hostQuery) {
                    GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED);
                }
                GL33C.glDeleteQueries(hostQuery);
            }
            check(GL33C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY) == 0,
                    "host query ends intact");

            check(draw.begin(), "draw query starts before toggle");
            draw.end();
            profiling.set(false);
            check(!RenderPerformanceProfiler.get().isSamplingEnabled(), "sampling toggles off");
            check(!draw.begin(), "sampling off rejects begin after toggle");
            profiling.set(true);
            check(RenderPerformanceProfiler.get().isSamplingEnabled(), "sampling toggles on");
            check(draw.begin(), "pool can be reused in the new sampling generation");
            draw.end();

            draw.dispose();
            draw.dispose();
            compute.dispose();
            compute.dispose();
            checkNoGlError("query disposal");
            System.out.println("PASS GPU timer query ownership/lifecycle");
        } finally {
            ConfigManager.init(null);
            GpuTimerQueryPool.disposeAll();
        }
    }
}
