package com.shiroha.mmdskin.stage.client.camera;

/** Small drift uses a bounded pitch correction; discontinuous seeks happen only while muted. */
final class StageAudioSync {
    record Adjustment(float pitch, float gain, float seekSeconds) { }
    private static final long FADE_NS = 30_000_000L;
    private int phase;
    private long phaseStart;

    void reset() { phase = 0; }

    Adjustment update(float target, float actual, long now) {
        if (!Float.isFinite(target) || !Float.isFinite(actual)) return new Adjustment(1, 1, Float.NaN);
        float error = target - actual;
        if (phase == 0 && Math.abs(error) > 0.5f) {
            phase = 1;
            phaseStart = now;
        }
        if (phase == 1) {
            float amount = Math.max(0, Math.min(1, (now - phaseStart) / (float) FADE_NS));
            if (amount < 1) return new Adjustment(1, 1 - amount, Float.NaN);
            phase = 2;
            phaseStart = now;
            return new Adjustment(1, 0, Math.max(0, target));
        }
        if (phase == 2) {
            float amount = Math.max(0, Math.min(1, (now - phaseStart) / (float) FADE_NS));
            if (amount < 1) return new Adjustment(1, amount, Float.NaN);
            phase = 0;
        }
        float pitch = Math.abs(error) < 0.015f ? 1 : Math.max(0.95f, Math.min(1.05f, 1 + error * 0.2f));
        return new Adjustment(pitch, 1, Float.NaN);
    }
}
