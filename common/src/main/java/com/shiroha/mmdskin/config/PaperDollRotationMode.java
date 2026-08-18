package com.shiroha.mmdskin.config;

/**
 * 纸娃娃模型旋转与朝向模式枚举。
 */
public enum PaperDollRotationMode {
    FIXED("固定微侧身"),
    FOLLOW_PLAYER("跟随玩家视角");

    private final String displayName;

    PaperDollRotationMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
