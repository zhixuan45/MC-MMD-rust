package com.shiroha.mmdskin.render.pipeline.transparency;

/** Tracks availability of the loader's translucent event without requiring a GL context. */
final class WorldTransparencyStageGate {
    private boolean previousFrameReachedStage;
    private boolean frameOpen;
    private boolean reachedStage;

    /** Defer only after an actual callback has been seen in the preceding frame. */
    boolean beginFrame() {
        boolean ready = previousFrameReachedStage;
        frameOpen = true;
        reachedStage = false;
        return ready;
    }

    /** An opaque snapshot must not be refreshed once the post-water callback ran. */
    boolean beforeTranslucent() {
        return frameOpen && !reachedStage;
    }

    /** Duplicate, late, and out-of-frame callbacks must not resolve twice. */
    boolean afterTranslucent() {
        if (!frameOpen || reachedStage) return false;
        reachedStage = true;
        return true;
    }

    /** If a renderer skips the event, use the immediate path next frame. */
    boolean endFrame() {
        if (!frameOpen) return false;
        boolean missing = previousFrameReachedStage && !reachedStage;
        previousFrameReachedStage = reachedStage;
        frameOpen = false;
        return missing;
    }
}
