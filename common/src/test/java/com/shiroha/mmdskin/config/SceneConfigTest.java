package com.shiroha.mmdskin.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SceneConfigTest {
    @Test
    void outlineDefaultsOnAndPersistsBothStates(@TempDir Path root) throws Exception {
        Path file = root.resolve("nested/scene_config.json");
        SceneConfig config = SceneConfig.load(file);
        assertTrue(config.isOutlineEnabled());
        config.setOutlineEnabled(false);
        assertFalse(config.isOutlineEnabled());
        assertFalse(SceneConfig.load(file).isOutlineEnabled());
        config.setOutlineEnabled(true);
        assertTrue(SceneConfig.load(file).isOutlineEnabled());
        Files.writeString(file, "{}");
        assertTrue(SceneConfig.load(file).isOutlineEnabled());
    }
}
