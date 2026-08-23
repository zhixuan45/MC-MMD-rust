package com.shiroha.mmdskin.config;

/**
 * 纸娃娃在屏幕上的锚点位置枚举。
 */
public enum PaperDollPosition {
    TOP_LEFT("左上角"),
    TOP_RIGHT("右上角"),
    BOTTOM_LEFT("左下角"),
    BOTTOM_RIGHT("右下角");

    private final String displayName;

    PaperDollPosition(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
