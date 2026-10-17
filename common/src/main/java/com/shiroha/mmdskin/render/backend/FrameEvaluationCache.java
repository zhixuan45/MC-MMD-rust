package com.shiroha.mmdskin.render.backend;

import com.shiroha.mmdskin.render.pipeline.RenderPerformanceProfiler;

/** One exact input epoch per real render frame. Unknown ports always evaluate. */
final class FrameEvaluationCache {
    private long frame = -1, revision = Long.MIN_VALUE;
    private RenderPerformanceProfiler.Scene scene = RenderPerformanceProfiler.Scene.OTHER;
    boolean matches(long frame, long revision) {
        return frame > 0 && revision != Long.MIN_VALUE && this.frame == frame && this.revision == revision;
    }
    boolean matchesWorld(long frame, long revision, boolean firstPersonPrepared, boolean vrActive) {
        if (!matches(frame, revision)) return false;
        return scene == RenderPerformanceProfiler.Scene.WORLD
                || scene == RenderPerformanceProfiler.Scene.SHADOW
                || (scene == RenderPerformanceProfiler.Scene.FIRST_PERSON && firstPersonPrepared && !vrActive);
    }
    void capture(long frame, long revision, RenderPerformanceProfiler.Scene scene) {
        this.frame = frame;
        this.revision = revision;
        this.scene = scene == null ? RenderPerformanceProfiler.Scene.OTHER : scene;
    }
}
