package com.shiroha.mmdskin.render.backend;

import com.shiroha.mmdskin.render.pipeline.RenderPerformanceProfiler;

/** Evidence of a completed world draw, without a second set of GUI head/eye inputs. */
final class PaperDollPoseState<T> {
    private T entity;
    private final FrameEvaluationCache drawnPose = new FrameEvaluationCache();
    private boolean firstPerson;

    void capture(T entity, long frame, long revision, boolean firstPerson) {
        this.entity = entity;
        drawnPose.capture(frame, revision, firstPerson
                ? RenderPerformanceProfiler.Scene.FIRST_PERSON : RenderPerformanceProfiler.Scene.WORLD);
        this.firstPerson = firstPerson;
    }

    boolean matches(T entity, long frame, long revision) {
        return entity != null && this.entity == entity && drawnPose.matches(frame, revision);
    }

    boolean isFirstPerson() { return firstPerson; }
}
