/* 文件职责：验证模型独立配置的归一化和防御性复制。 */
package com.shiroha.mmdskin.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelConfigDataTest {

    @Test
    void shouldNormalizeOutOfRangeValuesAndHiddenMaterials() {
        ModelConfigData config = new ModelConfigData();
        config.eyeMaxAngle = Float.NaN;
        config.modelScale = 100.0f;
        config.heldItemScale = -1.0f;
        config.hiddenMaterials = new HashSet<>(Set.of(-2, 3));

        ModelConfigData normalized = config.normalizedCopy();

        assertEquals(ModelConfigData.DEFAULT_EYE_MAX_ANGLE, normalized.eyeMaxAngle);
        assertEquals(ModelConfigData.MAX_MODEL_SCALE, normalized.modelScale);
        assertEquals(ModelConfigData.MIN_HELD_ITEM_SCALE, normalized.heldItemScale);
        assertEquals(Set.of(3), normalized.hiddenMaterials);
    }

    @Test
    void shouldCopyHiddenMaterialsDefensively() {
        ModelConfigData config = new ModelConfigData();
        config.hiddenMaterials.add(7);

        ModelConfigData copied = config.copy();
        copied.hiddenMaterials.add(9);

        assertNotSame(config.hiddenMaterials, copied.hiddenMaterials);
        assertEquals(Set.of(7), config.hiddenMaterials);
        assertEquals(Set.of(7, 9), copied.hiddenMaterials);
    }

    @Test
    void shouldLoadLegacyHeldBlockScaleFieldAndEnableMissingTailOptions(@TempDir Path tempDir) throws IOException {
        File tempFile = tempDir.resolve("legacy-config.json").toFile();
        String legacyJson = """
                {
                  "firstPersonHeldBlockScale": 0.6
                }
                """;
        Files.writeString(tempFile.toPath(), legacyJson, java.nio.charset.StandardCharsets.UTF_8);
        ModelConfigData loaded = ModelConfigData.load(tempFile);
        assertEquals(0.6f, loaded.heldItemScale);
        assertTrue(loaded.tailIdleLiftEnabled);
        assertTrue(loaded.tailMovementBoostEnabled);
    }

    @Test
    void tailPhysicsOptionsDefaultOnAndRoundTripThroughCopyAndJson(@TempDir Path tempDir) {
        ModelConfigData defaults = new ModelConfigData();
        assertTrue(defaults.tailIdleLiftEnabled);
        assertTrue(defaults.tailMovementBoostEnabled);

        defaults.tailIdleLiftEnabled = false;
        ModelConfigData copied = defaults.copy();
        assertFalse(copied.tailIdleLiftEnabled);
        assertTrue(copied.tailMovementBoostEnabled);

        File tempFile = tempDir.resolve("tail-config.json").toFile();
        copied.save(tempFile);
        ModelConfigData loaded = ModelConfigData.load(tempFile);
        assertFalse(loaded.tailIdleLiftEnabled);
        assertTrue(loaded.tailMovementBoostEnabled);

        File missingFile = tempDir.resolve("missing-config.json").toFile();
        ModelConfigData missingConfig = ModelConfigData.load(missingFile);
        assertTrue(missingConfig.tailIdleLiftEnabled);
        assertTrue(missingConfig.tailMovementBoostEnabled);
    }
}
