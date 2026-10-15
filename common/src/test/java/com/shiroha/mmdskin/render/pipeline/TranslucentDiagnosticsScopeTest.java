package com.shiroha.mmdskin.render.pipeline;

import com.shiroha.mmdskin.config.ConfigManager;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslucentDiagnosticsScopeTest {
    private final AtomicBoolean gpuLog = new AtomicBoolean();
    private final AtomicBoolean profiling = new AtomicBoolean();
    private final AtomicBoolean hud = new AtomicBoolean();

    @BeforeEach
    void setUp() throws Exception {
        clearAggregates();
        ConfigManager.init(provider());
        RenderPerformanceProfiler.get().isSamplingEnabled();
    }

    @AfterEach
    void tearDown() throws Exception {
        ConfigManager.init(null);
        RenderPerformanceProfiler.get().isSamplingEnabled();
        clearAggregates();
    }

    @Test
    void disabledSamplingReturnsSharedNoopAndDoesNotStartTimer() throws Exception {
        TranslucentDiagnostics.Scope first = TranslucentDiagnostics.beginModel(1L, "one", "CPU", "WORLD");
        TranslucentDiagnostics.Scope second = TranslucentDiagnostics.beginModel(2L, "two", "CPU", "WORLD");
        assertSame(first, second);
        assertEquals(0L, TranslucentDiagnostics.startTimer());
        first.close();
        assertTrue(aggregates().isEmpty());
    }

    @Test
    void nestedExceptionRestoresOuterScopeAndKeepsModelCountersSeparate() throws Exception {
        gpuLog.set(true);
        assertTrue(RenderPerformanceProfiler.get().isSamplingEnabled());

        try (TranslucentDiagnostics.Scope outer =
                     TranslucentDiagnostics.beginModel(10L, "outer", "CPU", "WORLD")) {
            TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.RUNS, 2L);
            try (TranslucentDiagnostics.Scope inner =
                         TranslucentDiagnostics.beginModel(20L, "inner", "GPU_COMPUTE", "WORLD")) {
                TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.RUNS, 7L);
                throw new IllegalStateException("simulated render failure");
            } catch (IllegalStateException expected) {
                TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.RUNS, 3L);
            }
        }

        Map<Long, String> byModel = jsonByModel();
        assertEquals(2, byModel.size());
        assertTrue(byModel.get(10L).contains("\"RUNS\":5"));
        assertTrue(byModel.get(20L).contains("\"RUNS\":7"));
    }

    @Test
    void disablingAndReenablingDropsOldScopeAndOldAggregate() throws Exception {
        gpuLog.set(true);
        assertTrue(RenderPerformanceProfiler.get().isSamplingEnabled());
        TranslucentDiagnostics.Scope oldScope =
                TranslucentDiagnostics.beginModel(31L, "same", "CPU", "WORLD");
        TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.RUNS, 100L);
        oldScope.close();

        TranslucentDiagnostics.Scope openAcrossToggle =
                TranslucentDiagnostics.beginModel(32L, "stale", "CPU", "WORLD");
        TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.RUNS, 200L);
        try {
            gpuLog.set(false);
            assertFalse(RenderPerformanceProfiler.get().isSamplingEnabled());
            assertTrue(aggregates().isEmpty());

            gpuLog.set(true);
            assertTrue(RenderPerformanceProfiler.get().isSamplingEnabled());
            try (TranslucentDiagnostics.Scope fresh =
                         TranslucentDiagnostics.beginModel(32L, "fresh", "CPU", "WORLD")) {
                TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.RUNS, 4L);
            }
        } finally {
            openAcrossToggle.close();
        }

        Map<Long, String> byModel = jsonByModel();
        assertEquals(1, byModel.size());
        assertTrue(byModel.get(32L).contains("\"modelName\":\"fresh\""));
        assertTrue(byModel.get(32L).contains("\"RUNS\":4"));
        assertFalse(byModel.containsKey(31L));
    }

    @Test
    void aggregateMapRemainsBoundedAt128Keys() throws Exception {
        gpuLog.set(true);
        RenderPerformanceProfiler.get().isSamplingEnabled();
        for (long modelId = 0L; modelId < 140L; modelId++) {
            try (TranslucentDiagnostics.Scope ignored =
                         TranslucentDiagnostics.beginModel(modelId, "model", "CPU", "WORLD")) {
                TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.RUNS, 1L);
            }
        }
        assertEquals(128, aggregates().size());
    }

    @Test
    void hudAndGeneralProfilingDoNotEnableGpuDiagnosticLogs() throws Exception {
        for (boolean hudEnabled : new boolean[] {false, true}) {
            hud.set(hudEnabled);
            profiling.set(true);
            assertTrue(RenderPerformanceProfiler.get().isSamplingEnabled());
            try (TranslucentDiagnostics.Scope ignored =
                         TranslucentDiagnostics.beginModel(50L, "disabled GPU log", "CPU", "WORLD")) {
                TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.RUNS, 9L);
                assertEquals(0L, TranslucentDiagnostics.startTimer());
            }
            assertTrue(aggregates().isEmpty());
        }
    }

    @Test
    void gpuLogToggleResetsItsWindowWhileHudKeepsSampling() throws Exception {
        hud.set(true);
        gpuLog.set(true);
        RenderPerformanceProfiler profiler = RenderPerformanceProfiler.get();
        assertTrue(profiler.isSamplingEnabled());
        long firstGeneration = profiler.samplingGeneration();
        try (TranslucentDiagnostics.Scope ignored =
                     TranslucentDiagnostics.beginModel(60L, "old", "GPU_COMPUTE", "WORLD")) {
            TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.RUNS, 90L);
        }
        gpuLog.set(false);
        assertTrue(profiler.isSamplingEnabled());
        assertTrue(profiler.samplingGeneration() > firstGeneration);
        assertTrue(aggregates().isEmpty());
        gpuLog.set(true);
        try (TranslucentDiagnostics.Scope ignored =
                     TranslucentDiagnostics.beginModel(60L, "new", "GPU_COMPUTE", "WORLD")) {
            TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.RUNS, 2L);
        }
        assertTrue(jsonByModel().get(60L).contains("\"RUNS\":2"));
    }

    private ConfigManager.IConfigProvider provider() {
        return (ConfigManager.IConfigProvider) Proxy.newProxyInstance(
                ConfigManager.IConfigProvider.class.getClassLoader(),
                new Class<?>[] {ConfigManager.IConfigProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isPerformanceProfilingEnabled" -> profiling.get();
                    case "isDebugGpuLogEnabled" -> gpuLog.get();
                    case "getPerformanceLogIntervalSeconds" -> 3_600;
                    case "isDebugHudEnabled" -> hud.get();
                    case "toString" -> "TranslucentDiagnosticsTestConfig";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> primitiveDefault(method.getReturnType());
                });
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, TranslucentDiagnostics.Aggregate> aggregates() throws Exception {
        Field field = TranslucentDiagnostics.class.getDeclaredField("AGGREGATES");
        field.setAccessible(true);
        return (Map<Object, TranslucentDiagnostics.Aggregate>) field.get(null);
    }

    private static void clearAggregates() throws Exception {
        aggregates().clear();
    }

    private static Map<Long, String> jsonByModel() throws Exception {
        Map<Long, String> result = new java.util.LinkedHashMap<>();
        for (TranslucentDiagnostics.Aggregate aggregate : aggregates().values()) {
            String json = aggregate.toJson();
            int start = json.indexOf("\"modelId\":") + "\"modelId\":".length();
            int end = json.indexOf(',', start);
            result.put(Long.parseLong(json.substring(start, end)), json);
        }
        return result;
    }
}
