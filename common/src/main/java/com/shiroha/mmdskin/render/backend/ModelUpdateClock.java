package com.shiroha.mmdskin.render.backend;

/** 按单调纳秒采样模型更新步长。 */
final class ModelUpdateClock {
    private static final long MAX_DELTA_NANOS = 250_000_000L;
    private static final float MAX_DELTA_SECONDS = 0.25f;

    private boolean initialized;
    private long lastSampleNanos;

    synchronized float sampleDeltaSeconds(long nowNanos) {
        if (!initialized) {
            initialized = true;
            lastSampleNanos = nowNanos;
            return 0.0f;
        }

        long elapsedNanos = nowNanos - lastSampleNanos;
        if (elapsedNanos <= 0L) return 0.0f;
        lastSampleNanos = nowNanos;
        if (elapsedNanos >= MAX_DELTA_NANOS) return MAX_DELTA_SECONDS;
        return elapsedNanos / 1_000_000_000.0f;
    }
}
