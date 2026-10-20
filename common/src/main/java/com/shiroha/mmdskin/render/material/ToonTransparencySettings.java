package com.shiroha.mmdskin.render.material;

import com.shiroha.mmdskin.config.ConfigManager;

/** 可热更新的透明设置；JVM 参数保留为诊断覆盖。 */
public final class ToonTransparencySettings {
    static final int MAX_LAYERS = 16;
    static final long MAX_BYTES = 128L * 1024 * 1024;

    private ToonTransparencySettings() { }

    static int requestedLayers() {
        String value = System.getProperty("mmdskin.toonTransparencyLayerCount");
        if (value == null) return Math.clamp(ConfigManager.getToonTransparencyLayerCount(), 0, MAX_LAYERS);
        try {
            int count = Integer.parseInt(value);
            return count >= 0 && count <= MAX_LAYERS ? count : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static boolean disableDepthSeal() {
        String value = System.getProperty("mmdskin.toonDisableDepthSeal");
        return value == null ? ConfigManager.isToonDisableDepthSeal() : Boolean.parseBoolean(value);
    }

    static boolean diagnostics() {
        return ConfigManager.isDebugGpuLogEnabled() || Boolean.getBoolean("mmdskin.toonTransparencyDiagnostics");
    }
}
