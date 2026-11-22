package com.shiroha.mmdskin.render.effect;

/**
 * 维护单个模型实例的骨盆遮罩状态与时间平滑不透明度，
 * 保证进出游泳/鞘翅状态及视角旋转时柔和淡入淡出。
 */
public final class PelvisVeilState {
    /** 低于该不透明度时跳过全部骨骼查询与 GL 绘制。 */
    public static final float MIN_VISIBLE_OPACITY = 0.003f;
    private static final float FADE_IN_TAU_SECONDS = 0.16f;
    private static final float FADE_OUT_TAU_SECONDS = 0.26f;
    private static final float MAX_DT_SECONDS = 0.10f;
    private static final float INITIAL_DT_SECONDS = 1.0f / 60.0f;

    private PelvisVeilMode requestedMode = PelvisVeilMode.NONE;
    private PelvisVeilMode renderMode = PelvisVeilMode.NONE;
    private float opacity;
    private float seconds;
    private float tintR = 0.26f;
    private float tintG = 0.72f;
    private float tintB = 0.88f;
    private long lastUpdateNanos;

    public void updateTarget(PelvisVeilMode mode, float seconds, float tintR, float tintG, float tintB) {
        PelvisVeilMode safeMode = mode != null ? mode : PelvisVeilMode.NONE;
        this.requestedMode = safeMode;
        this.seconds = Float.isFinite(seconds) ? seconds : 0.0f;
        if (safeMode.isActive()) {
            this.renderMode = safeMode;
            this.tintR = clamp01(tintR);
            this.tintG = clamp01(tintG);
            this.tintB = clamp01(tintB);
        }
    }

    /** 未激活且已完全淡出时返回 false，避免在普通状态下产生每帧骨骼拷贝开销。 */
    public boolean shouldEvaluate() {
        return requestedMode.isActive() || (renderMode.isActive() && opacity >= MIN_VISIBLE_OPACITY);
    }

    /** 按单调时间戳推进平滑不透明度，并返回当前帧生效值 [0, 1]。 */
    public float advance(float viewExposure, long nowNanos) {
        float target = requestedMode.isActive() ? clamp01(viewExposure) : 0.0f;
        float dt;
        if (lastUpdateNanos == 0L || nowNanos <= lastUpdateNanos) {
            dt = INITIAL_DT_SECONDS;
        } else {
            dt = Math.min(MAX_DT_SECONDS, (nowNanos - lastUpdateNanos) * 1.0e-9f);
        }
        lastUpdateNanos = nowNanos;

        float tau = target > opacity ? FADE_IN_TAU_SECONDS : FADE_OUT_TAU_SECONDS;
        float factor = 1.0f - (float) Math.exp(-dt / tau);
        opacity = clamp01(opacity + (target - opacity) * factor);

        if (target <= 0.0f && opacity < MIN_VISIBLE_OPACITY) {
            opacity = 0.0f;
            if (!requestedMode.isActive()) {
                renderMode = PelvisVeilMode.NONE;
            }
        }
        return opacity;
    }

    public void reset() {
        requestedMode = PelvisVeilMode.NONE;
        renderMode = PelvisVeilMode.NONE;
        opacity = 0.0f;
        lastUpdateNanos = 0L;
    }

    public PelvisVeilMode requestedMode() { return requestedMode; }
    public PelvisVeilMode renderMode() { return renderMode; }
    public float opacity() { return opacity; }
    public float seconds() { return seconds; }
    public float tintR() { return tintR; }
    public float tintG() { return tintG; }
    public float tintB() { return tintB; }

    private static float clamp01(float value) {
        if (!Float.isFinite(value)) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
