package com.shiroha.mmdskin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/** 保存场景放置的本地显示偏好。 */
public final class SceneConfig {
    private static final Logger logger = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path configFile;
    private volatile boolean outlineEnabled;

    private static final class Holder {
        private static final SceneConfig INSTANCE = load(PathConstants.getConfigFile(PathConstants.SCENE_CONFIG).toPath());
    }

    private static final class Preferences {
        boolean outlineEnabled = true;
    }

    private SceneConfig(Path configFile, boolean outlineEnabled) {
        this.configFile = configFile;
        this.outlineEnabled = outlineEnabled;
    }

    public static SceneConfig getInstance() {
        return Holder.INSTANCE;
    }

    static SceneConfig load(Path configFile) {
        boolean enabled = true;
        if (Files.exists(configFile)) {
            try {
                Preferences preferences = GSON.fromJson(Files.readString(configFile), Preferences.class);
                if (preferences != null) enabled = preferences.outlineEnabled;
            } catch (Exception e) {
                logger.warn("[SceneConfig] 加载失败: {}", e.getMessage());
            }
        }
        return new SceneConfig(configFile, enabled);
    }

    public boolean isOutlineEnabled() {
        return outlineEnabled;
    }

    public synchronized void setOutlineEnabled(boolean enabled) {
        outlineEnabled = enabled;
        Preferences preferences = new Preferences();
        preferences.outlineEnabled = enabled;
        try {
            Files.createDirectories(configFile.toAbsolutePath().getParent());
            Files.writeString(configFile, GSON.toJson(preferences));
        } catch (Exception e) {
            logger.warn("[SceneConfig] 保存失败: {}", e.getMessage());
        }
    }
}
