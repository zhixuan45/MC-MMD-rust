package com.shiroha.mmdskin.render.effect;

/** 骨盆半透明遮罩模式：区分游泳水波纹与鞘翅气流风纹。 */
public enum PelvisVeilMode {
    NONE(-1),
    SWIMMING_WATER(0),
    ELYTRA_WIND(1);

    private final int styleId;

    PelvisVeilMode(int styleId) {
        this.styleId = styleId;
    }

    public boolean isActive() {
        return this != NONE;
    }

    public int styleId() {
        return styleId;
    }
}
