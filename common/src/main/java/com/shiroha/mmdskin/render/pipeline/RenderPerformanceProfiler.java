package com.shiroha.mmdskin.render.pipeline;

import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.render.policy.ConfigManagerRenderPerformanceConfig;
import com.shiroha.mmdskin.render.policy.RenderPerformanceConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 文件职责：记录渲染热路径耗时并按周期输出统计。 */
public final class RenderPerformanceProfiler {
    public static final String SECTION_LIVING_STATE_SYNC = "livingStateSync";
    public static final String SECTION_NATIVE_MODEL_UPDATE = "nativeModelUpdate";
    public static final String SECTION_BONE_UPLOAD = "boneUpload";
    public static final String SECTION_MORPH_UPLOAD = "morphUpload";
    public static final String SECTION_MATERIAL_MORPH_FETCH = "materialMorphFetch";
    public static final String SECTION_COMPUTE_DISPATCH = "computeDispatch";
    public static final String SECTION_SUB_MESH_FETCH = "subMeshFetch";
    public static final String SECTION_DRAW = "draw";
    public static final String GPU_SECTION_COMPUTE = "gpuCompute";
    public static final String GPU_SECTION_DRAW = "gpuDraw";
    public enum Scene {
        WORLD, FIRST_PERSON, SHADOW, PAPERDOLL, INVENTORY, ITEM, OTHER;
        public final String nativeSection = "nativeUpdate." + name();
        /** Includes transparent preparation and submission, excludes outline. CPU wall time. */
        public final String bodySection = "draw.body." + name();
        public final String outlineSection = "draw.outline." + name();
    }
    private final java.util.EnumMap<Scene, java.util.Set<Long>> renderedInstances = new java.util.EnumMap<>(Scene.class);
    private final long[][] sceneTotals = new long[Scene.values().length][8];
    private final int[][] sceneFrame = new int[Scene.values().length][3];
    private long paperDollCacheHits, paperDollCacheRefreshes;
    private long parallelBatches, parallelModels;

    public synchronized void recordParallelBatch(int models) {
        if (models > 1 && isSamplingEnabled()) { parallelBatches++; parallelModels += models; }
    }

    /** Sum of exclusive per-model CPU work. With parallel evaluation it is not render-thread wait time. */
    public synchronized void recordElapsed(String section, long nanos) {
        if (nanos < 0 || !isSamplingEnabled()) return;
        profilingTotalsNanos.merge(section, nanos, Long::sum);
        currentFrameTotalsNanos.merge(section, nanos, Long::sum);
        currentFrameCalls.merge(section, 1, Integer::sum);
        sectionWindows.computeIfAbsent(section, ignored -> new PerformanceSampleWindow(120));
    }

    public synchronized void recordNativeProfile(Scene scene, java.nio.ByteBuffer data) {
        if (!isSamplingEnabled() || data.getLong(0) != 1) return;
        String[] names = {"animation", "ik", "physics", "cpuSkinning"};
        for (int i = 0; i < names.length; i++) recordElapsed("native." + names[i] + "." + scene.name(), data.getLong((i + 1) * 8));
        long[] totals = sceneTotals[scene.ordinal()];
        totals[4]++;
        totals[5] += data.getLong(40);
        totals[6] += data.getLong(48);
        totals[7] += data.getLong(56);
    }

    public synchronized void recordPaperDollCache(boolean refresh) {
        if (!isSamplingEnabled()) return;
        if (refresh) paperDollCacheRefreshes++; else paperDollCacheHits++;
    }

    public static Scene scene(com.shiroha.mmdskin.render.scene.RenderScene scene) {
        if (com.shiroha.mmdskin.compat.iris.IrisCompat.isRenderingShadows()) return Scene.SHADOW;
        if (scene == null) return Scene.OTHER;
        if (scene.isPaperDoll()) return Scene.PAPERDOLL;
        if (scene.isFirstPerson()) return Scene.FIRST_PERSON;
        if (scene.isWorldScene()) return Scene.WORLD;
        if (scene.isInventoryScene()) return Scene.INVENTORY;
        if (scene.sceneType() == com.shiroha.mmdskin.render.scene.RenderScene.SceneType.ITEM) return Scene.ITEM;
        return Scene.OTHER;
    }

    public synchronized void recordModel(Scene scene, long handle) {
        if (isSamplingEnabled()) renderedInstances.get(scene).add(handle);
    }
    public synchronized void recordEvaluation(Scene scene, boolean physicsEnabled) {
        if (!isSamplingEnabled()) return;
        sceneFrame[scene.ordinal()][0]++;
        if (physicsEnabled) sceneFrame[scene.ordinal()][1]++;
    }
    public synchronized void recordEvaluationReuse(Scene scene) {
        if (isSamplingEnabled()) sceneFrame[scene.ordinal()][2]++;
    }

    /** 传输分类只在真实 JNI/OpenGL 数据搬运点记录，避免 HUD 展示推算值。 */
    public enum TransferKind {
        BONE,
        VERTEX_MORPH,
        UV_MORPH,
        MATERIAL_MORPH,
        SUB_MESH,
        CPU_VERTEX,
        FIRST_PERSON_INDEX,
        LIGHT,
        GPU_READBACK
    }

    private static final Logger logger = LogManager.getLogger();
    private static final RenderPerformanceProfiler INSTANCE = new RenderPerformanceProfiler();
    private final RenderPerformanceConfig config = ConfigManagerRenderPerformanceConfig.get();

    private final Map<String, Long> profilingTotalsNanos = new LinkedHashMap<>();
    private final Map<String, Long> currentFrameTotalsNanos = new LinkedHashMap<>();
    private final Map<String, Integer> currentFrameCalls = new LinkedHashMap<>();
    private final Map<String, PerformanceSampleWindow> sectionWindows = new LinkedHashMap<>();
    private final PerformanceSampleWindow frameTimeWindow = new PerformanceSampleWindow(120);
    private final long[] currentTransferBytes = new long[TransferKind.values().length];
    private final long[] currentAvoidedUploadBytes = new long[TransferKind.values().length];
    private long lastPresentedFrameNanos;
    private volatile Snapshot latestSnapshot = Snapshot.empty();

    private long profilingLastLogTimeMs = System.currentTimeMillis();
    private long profiledFrameCount = 0L;
    private long profiledVisibleModels = 0L;
    private long profiledPhysicsModels = 0L;
    private volatile boolean lastSamplingState;
    private volatile boolean lastGpuLogState;
    private volatile long samplingGeneration;

    private RenderPerformanceProfiler() {
        for (Scene scene : Scene.values()) {
            renderedInstances.put(scene, new java.util.HashSet<>());
            profilingTotalsNanos.put(scene.nativeSection, 0L);
            profilingTotalsNanos.put(scene.bodySection, 0L);
            profilingTotalsNanos.put(scene.outlineSection, 0L);
        }
        profilingTotalsNanos.put("paperDoll.items", 0L);
        profilingTotalsNanos.put("paperDoll.flush", 0L);
        profilingTotalsNanos.put(SECTION_LIVING_STATE_SYNC, 0L);
        profilingTotalsNanos.put(SECTION_NATIVE_MODEL_UPDATE, 0L);
        profilingTotalsNanos.put(SECTION_BONE_UPLOAD, 0L);
        profilingTotalsNanos.put(SECTION_MORPH_UPLOAD, 0L);
        profilingTotalsNanos.put(SECTION_MATERIAL_MORPH_FETCH, 0L);
        profilingTotalsNanos.put(SECTION_COMPUTE_DISPATCH, 0L);
        profilingTotalsNanos.put(SECTION_SUB_MESH_FETCH, 0L);
        profilingTotalsNanos.put(SECTION_DRAW, 0L);
        for (String section : profilingTotalsNanos.keySet()) {
            currentFrameTotalsNanos.put(section, 0L);
            currentFrameCalls.put(section, 0);
            sectionWindows.put(section, new PerformanceSampleWindow(120));
        }
        sectionWindows.put(GPU_SECTION_COMPUTE, new PerformanceSampleWindow(120));
        sectionWindows.put(GPU_SECTION_DRAW, new PerformanceSampleWindow(120));
    }

    public static RenderPerformanceProfiler get() {
        return INSTANCE;
    }

    public long startTimer() {
        return isSamplingEnabled() ? System.nanoTime() : 0L;
    }

    public synchronized void endTimer(String section, long startTimeNanos) {
        if (startTimeNanos == 0L || !isSamplingEnabled()) {
            return;
        }

        long elapsed = System.nanoTime() - startTimeNanos;
        profilingTotalsNanos.merge(section, elapsed, Long::sum);
        currentFrameTotalsNanos.merge(section, elapsed, Long::sum);
        currentFrameCalls.merge(section, 1, Integer::sum);
        sectionWindows.computeIfAbsent(section, ignored -> new PerformanceSampleWindow(120));
    }

    /** GPU 查询结果会延迟若干帧返回，因此独立写入阶段窗口。 */
    public synchronized void recordGpuTime(String section, long elapsedNanos) {
        if (isSamplingEnabled() && (GPU_SECTION_COMPUTE.equals(section) || GPU_SECTION_DRAW.equals(section)) && elapsedNanos >= 0L) {
            sectionWindows.get(section).record(elapsedNanos);
        }
    }

    public synchronized void recordTransfer(TransferKind kind, long bytes) {
        if (isSamplingEnabled() && bytes > 0L) {
            currentTransferBytes[kind.ordinal()] += bytes;
        }
    }

    public synchronized void recordAvoidedUpload(TransferKind kind, long bytes) {
        if (isSamplingEnabled() && bytes > 0L) {
            currentAvoidedUploadBytes[kind.ordinal()] += bytes;
        }
    }

    /** HUD 绘制边界用于采样实际呈现帧间隔，不参与 MMD 世界帧阶段累加。 */
    public synchronized void markPresentedFrame() {
        if (!isSamplingEnabled()) {
            lastPresentedFrameNanos = 0L;
            return;
        }
        long now = System.nanoTime();
        if (lastPresentedFrameNanos != 0L) {
            frameTimeWindow.record(now - lastPresentedFrameNanos);
        }
        lastPresentedFrameNanos = now;
    }

    public Snapshot snapshot() {
        return latestSnapshot;
    }

    public synchronized void completeFrame(int visibleModels, int physicsModels) {
        if (!isSamplingEnabled()) {
            resetProfiling();
            return;
        }

        profiledFrameCount++;
        profiledVisibleModels += visibleModels;
        profiledPhysicsModels += physicsModels;
        for (Map.Entry<String, Long> entry : currentFrameTotalsNanos.entrySet()) {
            sectionWindows.get(entry.getKey()).record(entry.getValue());
            entry.setValue(0L);
        }
        Map<String, Integer> frameCalls = new LinkedHashMap<>(currentFrameCalls);
        for (Scene scene : Scene.values()) {
            int i = scene.ordinal(), instances = renderedInstances.get(scene).size();
            sceneTotals[i][0] += instances;
            frameCalls.put(scene.name() + ".instances", instances);
            for (int v = 0; v < 3; v++) { sceneTotals[i][v + 1] += sceneFrame[i][v]; }
            frameCalls.put(scene.name() + ".evaluations", sceneFrame[i][0]);
            frameCalls.put(scene.name() + ".physicsEnabledEvaluations", sceneFrame[i][1]);
            frameCalls.put(scene.name() + ".evaluationReuses", sceneFrame[i][2]);
            renderedInstances.get(scene).clear();
            java.util.Arrays.fill(sceneFrame[i], 0);
        }
        frameCalls = Map.copyOf(frameCalls);
        currentFrameCalls.replaceAll((section, count) -> 0);
        long[] frameTransferBytes = currentTransferBytes.clone();
        long[] frameAvoidedUploadBytes = currentAvoidedUploadBytes.clone();
        java.util.Arrays.fill(currentTransferBytes, 0L);
        java.util.Arrays.fill(currentAvoidedUploadBytes, 0L);
        rebuildSnapshot(visibleModels, physicsModels, frameCalls, frameTransferBytes, frameAvoidedUploadBytes);
        if (config.isPerformanceProfilingEnabled() || ConfigManager.isDebugGpuLogEnabled()) {
            maybeLogProfiling();
        }
    }

    private void rebuildSnapshot(int visibleModels, int physicsModels, Map<String, Integer> frameCalls,
                                 long[] transferBytes, long[] avoidedUploadBytes) {
        Map<String, PerformanceSampleWindow.Snapshot> sections = new LinkedHashMap<>();
        for (Map.Entry<String, PerformanceSampleWindow> entry : sectionWindows.entrySet()) {
            sections.put(entry.getKey(), entry.getValue().snapshot());
        }
        latestSnapshot = new Snapshot(
                frameTimeWindow.snapshot(), Map.copyOf(sections), frameCalls, visibleModels, physicsModels,
                new TransferSnapshot(transferBytes, avoidedUploadBytes));
    }

    public boolean isSamplingEnabled() {
        boolean gpuLog = ConfigManager.isDebugGpuLogEnabled();
        boolean enabled = config.isPerformanceProfilingEnabled() || ConfigManager.isDebugHudEnabled() || gpuLog;
        if (enabled != lastSamplingState || gpuLog != lastGpuLogState) resetSamplingWindows(enabled, gpuLog);
        return enabled;
    }

    public long samplingGeneration() {
        return samplingGeneration;
    }

    private void resetSamplingWindows(boolean enabled, boolean gpuLog) {
        synchronized (this) {
            if (enabled == lastSamplingState && gpuLog == lastGpuLogState) return;
            lastSamplingState = enabled;
            lastGpuLogState = gpuLog;
            samplingGeneration++;
            lastPresentedFrameNanos = 0L;
            frameTimeWindow.clear();
            for (PerformanceSampleWindow window : sectionWindows.values()) window.clear();
            currentFrameTotalsNanos.replaceAll((section, value) -> 0L);
            currentFrameCalls.replaceAll((section, value) -> 0);
            for (Scene scene : Scene.values()) {
                renderedInstances.get(scene).clear();
                java.util.Arrays.fill(sceneFrame[scene.ordinal()], 0);
            }
            java.util.Arrays.fill(currentTransferBytes, 0L);
            java.util.Arrays.fill(currentAvoidedUploadBytes, 0L);
            latestSnapshot = Snapshot.empty();
            resetProfiling();
        }
        TranslucentDiagnostics.onSamplingReset();
    }

    private void maybeLogProfiling() {
        long now = System.currentTimeMillis();
        long intervalMs = config.getPerformanceLogIntervalSeconds() * 1000L;
        if (profiledFrameCount <= 0 || now - profilingLastLogTimeMs < intervalMs) {
            return;
        }

        StringBuilder message = new StringBuilder("[MMD性能] frames=")
                .append(profiledFrameCount)
                .append(", avgVisible=")
                .append(String.format("%.2f", profiledVisibleModels / (double) profiledFrameCount))
                .append(", avgPhysics=")
                .append(String.format("%.2f", profiledPhysicsModels / (double) profiledFrameCount));

        PerformanceSampleWindow.Snapshot frame = frameTimeWindow.snapshot();
        message.append(", paperDoll.cacheHits=").append(paperDollCacheHits)
                .append(", paperDoll.cacheRefreshes=").append(paperDollCacheRefreshes);
        message.append(", native.parallelBatches=").append(parallelBatches)
                .append(", native.parallelModels=").append(parallelModels);
        message.append(", presentedFrameSamples=").append(frame.count())
                .append(", presentedFrameMedian=").append(String.format("%.3fms", frame.medianNanos() / 1_000_000.0d))
                .append(", presentedFrameP95=").append(String.format("%.3fms", frame.p95Nanos() / 1_000_000.0d));
        PerformanceSampleWindow.Snapshot nativeUpdate = sectionWindows.get(SECTION_NATIVE_MODEL_UPDATE).snapshot();
        message.append(", nativeUpdateWorldFrame={samples:").append(nativeUpdate.count())
                .append(",medianNs:").append(nativeUpdate.medianNanos())
                .append(",p95Ns:").append(nativeUpdate.p95Nanos()).append('}');
        for (Scene scene : Scene.values()) {
            long[] totals = sceneTotals[scene.ordinal()];
            if (totals[0] + totals[1] + totals[3] == 0) continue;
            message.append(", scene.").append(scene.name()).append("={avgInstances:")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", totals[0] / (double) profiledFrameCount))
                    .append(",evaluations:").append(totals[1])
                    .append(",physicsEnabledEvaluations:").append(totals[2])
                    .append(",reuses:").append(totals[3])
                    .append(",nativeProfileSamples:").append(totals[4])
                    .append(",bulletSteps:").append(totals[5])
                    .append(",bulletSubsteps:").append(totals[6])
                    .append(",bulletRequestedSubsteps:").append(totals[7]).append('}');
        }
        if (ConfigManager.isDebugGpuLogEnabled()) {
            appendGpuQuerySummary(message, GPU_SECTION_COMPUTE, "compute");
            appendGpuQuerySummary(message, GPU_SECTION_DRAW, "draw");
        }

        for (Map.Entry<String, Long> entry : profilingTotalsNanos.entrySet()) {
            double avgMs = entry.getValue() / 1_000_000.0d / profiledFrameCount;
            message.append(", ").append(entry.getKey()).append('=')
                    .append(String.format("%.3fms", avgMs));
        }

        logger.info(message.toString());
        resetProfiling();
    }

    private void appendGpuQuerySummary(StringBuilder message, String name, String label) {
        PerformanceSampleWindow.Snapshot query = sectionWindows.get(name).snapshot();
        message.append(", gpuQuery").append(label).append("={samples:").append(query.count())
                .append(",medianNs:").append(query.medianNanos()).append(",p95Ns:").append(query.p95Nanos()).append('}');
    }

    private void resetProfiling() {
        profilingLastLogTimeMs = System.currentTimeMillis();
        profiledFrameCount = 0L;
        profiledVisibleModels = 0L;
        profiledPhysicsModels = 0L;
        paperDollCacheHits = paperDollCacheRefreshes = 0;
        parallelBatches = parallelModels = 0;
        for (long[] totals : sceneTotals) java.util.Arrays.fill(totals, 0L);
        for (Map.Entry<String, Long> entry : profilingTotalsNanos.entrySet()) {
            entry.setValue(0L);
        }
        java.util.Arrays.fill(currentTransferBytes, 0L);
        java.util.Arrays.fill(currentAvoidedUploadBytes, 0L);
    }

    /** HUD 只读取不可变快照，避免在绘制阶段持有 Profiler 锁。 */
    public record Snapshot(PerformanceSampleWindow.Snapshot frameTime,
                           Map<String, PerformanceSampleWindow.Snapshot> sections,
                           Map<String, Integer> sectionCalls,
                           int visibleModels,
                           int physicsModels,
                           TransferSnapshot transfers) {
        private static Snapshot empty() {
            return new Snapshot(PerformanceSampleWindow.Snapshot.empty(), Map.of(), Map.of(), 0, 0,
                    TransferSnapshot.empty());
        }

        public PerformanceSampleWindow.Snapshot section(String name) {
            return sections.getOrDefault(name, PerformanceSampleWindow.Snapshot.empty());
        }

        public int calls(String name) {
            return sectionCalls.getOrDefault(name, 0);
        }
    }

    /** 数组在构造时复制，HUD 无需持有 profiler 锁即可安全读取。 */
    public record TransferSnapshot(long[] uploadedBytes, long[] avoidedUploadBytes) {
        public TransferSnapshot {
            uploadedBytes = uploadedBytes.clone();
            avoidedUploadBytes = avoidedUploadBytes.clone();
        }

        private static TransferSnapshot empty() {
            return new TransferSnapshot(new long[TransferKind.values().length],
                    new long[TransferKind.values().length]);
        }

        @Override
        public long[] uploadedBytes() {
            return uploadedBytes.clone();
        }

        @Override
        public long[] avoidedUploadBytes() {
            return avoidedUploadBytes.clone();
        }

        public long uploaded(TransferKind kind) {
            return uploadedBytes[kind.ordinal()];
        }

        public long avoided(TransferKind kind) {
            return avoidedUploadBytes[kind.ordinal()];
        }

        public long totalGpuUploaded() {
            long total = 0L;
            return uploaded(TransferKind.BONE)
                    + uploaded(TransferKind.VERTEX_MORPH)
                    + uploaded(TransferKind.UV_MORPH)
                    + uploaded(TransferKind.CPU_VERTEX)
                    + uploaded(TransferKind.FIRST_PERSON_INDEX)
                    + uploaded(TransferKind.LIGHT);
        }

        public long totalGpuUploadAvoided() {
            return avoided(TransferKind.BONE)
                    + avoided(TransferKind.VERTEX_MORPH)
                    + avoided(TransferKind.UV_MORPH)
                    + avoided(TransferKind.CPU_VERTEX)
                    + avoided(TransferKind.FIRST_PERSON_INDEX)
                    + avoided(TransferKind.LIGHT);
        }

        public long totalCpuTransfer() {
            return uploaded(TransferKind.MATERIAL_MORPH) + uploaded(TransferKind.SUB_MESH);
        }

        public long totalCpuTransferAvoided() {
            return avoided(TransferKind.MATERIAL_MORPH) + avoided(TransferKind.SUB_MESH);
        }
    }
}
