package com.shiroha.mmdskin.render.pipeline;

import com.shiroha.mmdskin.render.policy.ConfigManagerRenderPerformanceConfig;
import com.shiroha.mmdskin.config.ConfigManager;
import java.util.LinkedHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 半透明渲染诊断；热路径只更新当前线程作用域中的固定数组。 */
public final class TranslucentDiagnostics {
    public enum Stage { CLASSIFY, CANDIDATES, DEPTH_KEYS, SORT, INDEX_BUILD, UPLOAD, SUBMIT, READBACK }
    public enum Counter { CANDIDATE_BUILD, CANDIDATE_REUSE, CANDIDATE_TRIANGLES, VISIBLE_TRIANGLES, RUNS,
        DRAW_OPAQUE, DRAW_TRANSLUCENT, DRAW_DEPTH_SEAL, DRAW_SHADOW, READBACK_CALLS, READBACK_BYTES, UPLOAD_BYTES,
        BATCH_SUBMISSIONS, BATCH_RUNS, BATCH_FALLBACK, BATCH_REJECT_PROGRAM, BATCH_REJECT_SAMPLERS,
        BATCH_REJECT_CAPACITY, GPU_PREPARE, PREPARED_REUSE, GPU_WINDOW_FALLBACK }
    private static final Stage[] STAGES = Stage.values();
    private static final Counter[] COUNTERS = Counter.values();

    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_KEYS = 128;
    private static final int WINDOW_SIZE = 120;
    private static final ConfigManagerRenderPerformanceConfig CONFIG = ConfigManagerRenderPerformanceConfig.get();
    private static final LinkedHashMap<Key, Aggregate> AGGREGATES = new LinkedHashMap<>();
    private static final ThreadLocal<ScopeStack<Scope>> CURRENT = new ThreadLocal<>();
    private static volatile boolean lastSamplingEnabled;
    private static volatile long samplingGeneration;
    private static long lastLogTimeMs = System.currentTimeMillis();

    private TranslucentDiagnostics() { }

    public static long startTimer() {
        return ConfigManager.isDebugGpuLogEnabled() ? System.nanoTime() : 0L;
    }

    public static void endTimer(Stage stage, long start) {
        if (start == 0L || !ConfigManager.isDebugGpuLogEnabled()) return;
        ScopeStack<Scope> stack = CURRENT.get();
        Scope scope = stack == null ? null : stack.current();
        if (scope != null && scope.active) scope.stageNanos[stage.ordinal()] += Math.max(0L, System.nanoTime() - start);
    }

    public static void add(Counter counter, long amount) {
        if (amount <= 0L || !ConfigManager.isDebugGpuLogEnabled()) return;
        ScopeStack<Scope> stack = CURRENT.get();
        Scope scope = stack == null ? null : stack.current();
        if (scope != null && scope.active) scope.counterTotals[counter.ordinal()] += amount;
    }

    public static Scope beginModel(long modelId, String modelName, String skinning, String scene) {
        RenderPerformanceProfiler.get().isSamplingEnabled();
        boolean enabled = ConfigManager.isDebugGpuLogEnabled();
        clearIfSamplingChanged(enabled);
        if (!enabled) return Scope.NOOP;
        ScopeStack<Scope> stack = CURRENT.get();
        if (stack == null) {
            stack = new ScopeStack<>();
            CURRENT.set(stack);
        }
        Scope parent = stack.current();
        Scope scope = new Scope(parent, new Key(modelId, safe(skinning), safe(scene)), safe(modelName), System.nanoTime());
        scope.generation = samplingGeneration;
        stack.push(scope);
        return scope;
    }

    private static String safe(String value) { return value == null ? "unknown" : value; }

    private static void finish(Scope scope) {
        if (!scope.active) return;
        RenderPerformanceProfiler.get().isSamplingEnabled();
        boolean sampling = ConfigManager.isDebugGpuLogEnabled();
        scope.active = false;
        ScopeStack<Scope> stack = CURRENT.get();
        if (stack != null) {
            stack.pop(scope, scope.parent);
            if (stack.isEmpty()) CURRENT.remove();
        }
        synchronized (TranslucentDiagnostics.class) {
            clearIfSamplingChanged(sampling);
            if (!sampling || scope.generation != samplingGeneration) return;
            Aggregate aggregate = AGGREGATES.get(scope.key);
            if (aggregate == null) {
                maybeLog();
                if (AGGREGATES.size() >= MAX_KEYS) return;
                aggregate = new Aggregate(scope.key, scope.modelName);
                AGGREGATES.put(scope.key, aggregate);
            }
            aggregate.add(scope);
            maybeLog();
        }
    }

    private static void clearIfSamplingChanged(boolean enabled) {
        if (lastSamplingEnabled == enabled) return;
        synchronized (TranslucentDiagnostics.class) {
            if (lastSamplingEnabled == enabled) return;
            AGGREGATES.clear();
            samplingGeneration++;
            lastLogTimeMs = System.currentTimeMillis();
            lastSamplingEnabled = enabled;
        }
    }

    private static void maybeLog() {
        if (!ConfigManager.isDebugGpuLogEnabled()) return;
        long now = System.currentTimeMillis();
        long interval = Math.max(1, CONFIG.getPerformanceLogIntervalSeconds()) * 1000L;
        if (now - lastLogTimeMs < interval) return;
        for (Aggregate aggregate : AGGREGATES.values()) LOGGER.info("[MMD透明性能] " + aggregate.toJson());
        AGGREGATES.clear();
        lastLogTimeMs = now;
    }

    static void onSamplingReset() {
        synchronized (TranslucentDiagnostics.class) {
            AGGREGATES.clear();
            samplingGeneration++;
            lastSamplingEnabled = false;
            lastLogTimeMs = System.currentTimeMillis();
        }
    }

    private record Key(long modelId, String skinning, String scene) { }

    static final class ScopeStack<T> {
        private T current;
        T current() { return current; }
        boolean isEmpty() { return current == null; }
        void push(T value) { current = value; }
        void pop(T value, T parent) {
            if (current != value) throw new IllegalStateException("scope closed out of order");
            current = parent;
        }
    }

    public static final class Scope implements AutoCloseable {
        private static final Scope NOOP = new Scope();
        private final Scope parent;
        private final Key key;
        private final String modelName;
        private final long started;
        private final long[] stageNanos;
        private final long[] counterTotals;
        private boolean active;
        private long generation;

        private Scope() {
            parent = null; key = null; modelName = ""; started = 0L;
            stageNanos = new long[0]; counterTotals = new long[0]; active = false;
        }

        Scope(Scope parent, Key key, String modelName, long started) {
            this.parent = parent; this.key = key; this.modelName = modelName; this.started = started;
            stageNanos = new long[STAGES.length]; counterTotals = new long[COUNTERS.length]; active = true;
        }

        @Override public void close() { if (active) finish(this); }
    }

    static final class Aggregate {
        private final Key key;
        private String modelName;
        private final PerformanceSampleWindow renderCalls = new PerformanceSampleWindow(WINDOW_SIZE);
        private final PerformanceSampleWindow[] stages = new PerformanceSampleWindow[Stage.values().length];
        private final long[] counters = new long[Counter.values().length];
        private final long[] stageTotalsNanos = new long[Stage.values().length];
        private long samples;

        Aggregate(long modelId, String skinning, String scene, String modelName) {
            this(new Key(modelId, safe(skinning), safe(scene)), safe(modelName));
        }

        private Aggregate(Key key, String modelName) {
            this.key = key; this.modelName = modelName;
            for (int i = 0; i < stages.length; i++) stages[i] = new PerformanceSampleWindow(WINDOW_SIZE);
        }

        private void add(Scope scope) {
            modelName = scope.modelName;
            addSample(Math.max(0L, System.nanoTime() - scope.started), scope.stageNanos, scope.counterTotals);
        }

        void addSample(long renderNanos, long[] stageNanos, long[] counterValues) {
            renderCalls.record(Math.max(0L, renderNanos));
            for (int i = 0; i < stages.length; i++) {
                stages[i].record(stageNanos[i]);
                stageTotalsNanos[i] += stageNanos[i];
            }
            for (int i = 0; i < counters.length; i++) counters[i] += counterValues[i];
            samples++;
        }

        String toJson() {
            StringBuilder out = new StringBuilder(640).append("{\"schemaVersion\":1,\"modelId\":").append(key.modelId)
                    .append(",\"modelName\":\"").append(escape(modelName)).append("\",\"skinning\":\"")
                    .append(escape(key.skinning)).append("\",\"scene\":\"").append(escape(key.scene))
                    .append("\",\"samples\":").append(samples);
            PerformanceSampleWindow.Snapshot render = renderCalls.snapshot();
            out.append(",\"renderCallMedianNs\":").append(render.medianNanos()).append(",\"renderCallP95Ns\":").append(render.p95Nanos());
            out.append(",\"windowSamples\":").append(Math.min(samples, WINDOW_SIZE)).append(",\"stages\":{");
            for (int i = 0; i < stages.length; i++) {
                if (i > 0) out.append(',');
                PerformanceSampleWindow.Snapshot s = stages[i].snapshot();
                out.append('"').append(STAGES[i].name()).append("\":{\"avgNs\":")
                        .append(samples == 0 ? 0L : stageTotalsNanos[i] / samples)
                        .append(",\"medianNs\":").append(s.medianNanos()).append(",\"p95Ns\":").append(s.p95Nanos()).append('}');
            }
            out.append("},\"counters\":{");
            for (int i = 0; i < counters.length; i++) {
                if (i > 0) out.append(',');
                out.append('"').append(COUNTERS[i].name()).append("\":").append(counters[i]);
            }
            return out.append("}}").toString();
        }
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.toString();
    }
}
