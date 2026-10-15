package com.shiroha.mmdskin.render.backend;

/** One exact input epoch per real render frame. Unknown ports always evaluate. */
final class FrameEvaluationCache {
    private long frame = -1, revision = Long.MIN_VALUE;
    boolean matches(long frame, long revision) {
        return frame > 0 && revision != Long.MIN_VALUE && this.frame == frame && this.revision == revision;
    }
    void capture(long frame, long revision) { this.frame = frame; this.revision = revision; }
}
