package com.shiroha.mmdskin.config;

/**
 * 纸娃娃显示触发模式枚举。
 */
public enum PaperDollDisplayMode {
    ALWAYS("常驻显示"),
    DYNAMIC("仅动作触发");

    private final String displayName;

    PaperDollDisplayMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
