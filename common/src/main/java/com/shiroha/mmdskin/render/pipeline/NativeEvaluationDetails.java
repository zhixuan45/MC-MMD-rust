package com.shiroha.mmdskin.render.pipeline;

import java.nio.ByteBuffer;

/** 详细采样 ABI v1；旧 DLL 的 64 字节记录继续有效。 */
public final class NativeEvaluationDetails {
    private static final String[] STAGES = {
            "collectBones", "syncAndForces", "bulletStep", "clampAndDiagnostics", "writeback", "finalMatrices"
    };

    private NativeEvaluationDetails() {}

    public static void record(RenderPerformanceProfiler profiler, RenderPerformanceProfiler.Scene scene, ByteBuffer data) {
        if (!valid(data)) return;
        for (int i = 0; i < STAGES.length; i++) {
            profiler.recordElapsed("native.detail." + STAGES[i] + "." + scene.name(), data.getLong(120 + i * 8));
        }
        profiler.recordNativeParallelDetail(scene, data);
    }

    static boolean valid(ByteBuffer data) {
        return data != null && data.capacity() >= 288 && data.getLong(0) == 1
                && data.getLong(64) == 1 && data.getLong(72) == 288;
    }
}
