package com.shiroha.mmdskin.render.pipeline.transparency;

import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.render.pipeline.PerformanceSampleWindow;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/** Render-thread diagnostics. Timestamp pairs can coexist with the model's TIME_ELAPSED query. */
final class WorldTransparencyProfiler {
    enum Stage {
        STORAGE_CLEAR, DEPTH_INITIAL, DEPTH_ENTITIES, DEPTH_OPAQUE, DEPTH_PRE_TRANSLUCENT,
        DEPTH_RESOLVE, CAPTURE_STATE, WATER_READBACK, OVERFLOW_SYNC_READBACK,
        OVERFLOW_ASYNC_ISSUE, OVERFLOW_ASYNC_POLL, RESOLVE, RESOLVE_DRAW,
        WATER_RESOLVE, WATER_RESOLVE_DRAW, WATER_GPU_CLEAR
    }

    private static final int MAX_PENDING = 64;
    private static final WorldTransparencyProfiler INSTANCE = new WorldTransparencyProfiler();
    private static final Scope DISABLED = new Scope(null, null, 0, null);
    private final PerformanceSampleWindow[] cpu = windows(), gpu = windows();
    private final long[] frameNanos = new long[Stage.values().length];
    private final long[] intervalCalls = new long[Stage.values().length];
    private final Deque<Pair> free = new ArrayDeque<>(), pending = new ArrayDeque<>();
    private Object context;
    private int allocated, frames, focusedFrames;
    private long lastLog, generation;
    private boolean enabled, started;
    private final boolean[] focusWindow = new boolean[120];
    private int focusIndex, focusCount, focusedWindowCount;

    private record Pair(int start, int end, Stage stage, long generation) { }

    private static PerformanceSampleWindow[] windows() {
        return Arrays.stream(Stage.values()).map(s -> new PerformanceSampleWindow(120))
                .toArray(PerformanceSampleWindow[]::new);
    }

    static void beginFrame(boolean focused) {
        var p = INSTANCE;
        boolean next = Boolean.parseBoolean(System.getProperty("mmdskin.profileWorldTransparency",
                Boolean.toString(ConfigManager.isDebugGpuLogEnabled())));
        if (next != p.enabled) {
            p.generation++;
            for (var w : p.cpu) w.clear();
            for (var w : p.gpu) w.clear();
            Arrays.fill(p.frameNanos, 0); Arrays.fill(p.intervalCalls, 0);
            p.frames = p.focusedFrames = 0; p.started = false; p.lastLog = System.nanoTime();
            p.focusIndex = p.focusCount = p.focusedWindowCount = 0;
            Arrays.fill(p.focusWindow, false);
        }
        p.enabled = next;
        if (!next) return;
        p.refreshContext();
        p.collect();
        if (p.started) {
            for (Stage s : Stage.values()) {
                p.cpu[s.ordinal()].record(p.frameNanos[s.ordinal()]);
            }
            p.frames++;
            if (p.previousFocused) p.focusedFrames++;
            if (p.focusWindow[p.focusIndex]) p.focusedWindowCount--;
            p.focusWindow[p.focusIndex] = p.previousFocused;
            if (p.previousFocused) p.focusedWindowCount++;
            p.focusIndex = (p.focusIndex + 1) % p.focusWindow.length;
            p.focusCount = Math.min(p.focusCount + 1, p.focusWindow.length);
        }
        Arrays.fill(p.frameNanos, 0);
        p.previousFocused = focused;
        p.started = true;
        long now = System.nanoTime();
        if (now - p.lastLog >= Math.max(1, ConfigManager.getPerformanceLogIntervalSeconds()) * 1_000_000_000L) {
            p.log(); p.lastLog = now;
        }
    }

    private boolean previousFocused;

    static Scope measure(Stage stage, boolean gpu) {
        var p = INSTANCE;
        if (!p.enabled) return DISABLED;
        Pair pair = null;
        if (gpu) {
            if (!p.free.isEmpty()) {
                var old = p.free.removeFirst();
                pair = new Pair(old.start, old.end, stage, p.generation);
            } else if (p.allocated < MAX_PENDING) {
                pair = new Pair(GL33C.glGenQueries(), GL33C.glGenQueries(), stage, p.generation);
                p.allocated++;
            }
            if (pair != null) GL33C.glQueryCounter(pair.start, GL33C.GL_TIMESTAMP);
        }
        return new Scope(p, stage, System.nanoTime(), pair);
    }

    static final class Scope implements AutoCloseable {
        private final WorldTransparencyProfiler owner;
        private final Stage stage;
        private final long start;
        private final Pair pair;
        private boolean closed;
        Scope(WorldTransparencyProfiler owner, Stage stage, long start, Pair pair) {
            this.owner = owner; this.stage = stage; this.start = start; this.pair = pair;
        }
        @Override public void close() {
            if (owner == null || closed) return;
            closed = true;
            owner.frameNanos[stage.ordinal()] += System.nanoTime() - start;
            owner.intervalCalls[stage.ordinal()]++;
            if (pair != null) {
                GL33C.glQueryCounter(pair.end, GL33C.GL_TIMESTAMP);
                owner.pending.addLast(pair);
            }
        }
    }

    private void refreshContext() {
        Object next = GL.getCapabilities();
        if (next == context) return;
        context = next;
        // Names from an old context must not be deleted in a new one.
        free.clear(); pending.clear(); allocated = 0;
        for (var w : gpu) w.clear();
    }

    private void collect() {
        while (!pending.isEmpty()) {
            Pair pair = pending.peekFirst();
            if (GL33C.glGetQueryObjecti(pair.end, GL33C.GL_QUERY_RESULT_AVAILABLE) == 0) break;
            pending.removeFirst();
            if (pair.generation == generation) {
                long start = GL33C.glGetQueryObjecti64(pair.start, GL33C.GL_QUERY_RESULT);
                long end = GL33C.glGetQueryObjecti64(pair.end, GL33C.GL_QUERY_RESULT);
                if (end >= start) gpu[pair.stage.ordinal()].record(end - start);
            }
            free.addLast(pair);
        }
    }

    private void log() {
        if (frames == 0) return;
        var out = new StringBuilder("[MMD世界透明性能] {\"schemaVersion\":1,\"frames\":")
                .append(frames).append(",\"focusedFrames\":").append(focusedFrames)
                .append(",\"windowFrames\":").append(focusCount)
                .append(",\"windowFocusedFrames\":").append(focusedWindowCount)
                .append(",\"asyncOverflow\":").append(AsyncOverflowReadback.enabled())
                .append(",\"gpuWaterDecision\":").append(WaterResolveStage.enabled())
                .append(",\"toon\":").append(ConfigManager.isToonRenderingEnabled())
                .append(",\"cpuUnits\":\"ns_per_world_frame\",\"gpuUnits\":\"ns_per_operation\",\"stages\":{");
        boolean comma = false;
        for (Stage s : Stage.values()) {
            int i = s.ordinal();
            if (comma) out.append(','); comma = true;
            out.append('"').append(s).append("\":{\"calls\":").append(intervalCalls[i])
                    .append(",\"cpu\":");
            append(out, cpu[i].snapshot()); out.append(",\"gpu\":"); append(out, gpu[i].snapshot());
            out.append('}');
        }
        out.append("},\"pendingGpuQueries\":").append(pending.size()).append('}');
        LogManager.getLogger().info(out);
        frames = focusedFrames = 0; Arrays.fill(intervalCalls, 0);
    }

    private static void append(StringBuilder out, PerformanceSampleWindow.Snapshot s) {
        out.append("{\"samples\":").append(s.count()).append(",\"avgNs\":").append(s.averageNanos())
                .append(",\"medianNs\":").append(s.medianNanos()).append(",\"p95Ns\":").append(s.p95Nanos()).append('}');
    }

    static void release() {
        var p = INSTANCE;
        if (p.context == GL.getCapabilities()) {
            for (Pair q : p.free) { GL33C.glDeleteQueries(q.start); GL33C.glDeleteQueries(q.end); }
            for (Pair q : p.pending) { GL33C.glDeleteQueries(q.start); GL33C.glDeleteQueries(q.end); }
        }
        p.free.clear(); p.pending.clear(); p.context = null; p.allocated = 0;
        p.enabled = p.started = false;
    }
}
