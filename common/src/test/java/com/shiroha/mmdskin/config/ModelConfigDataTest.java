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

    @Test
    void pmxSelectionsAreCopiedNormalizedAndPersisted(@TempDir Path tempDir) {
        ModelConfigData config = new ModelConfigData();
        config.mainPmxFile = "普通.pmx";
        config.heldPmxFile = "手持模型.PMX";
        config.heldPmxPhysicsEnabled = false;
        ModelConfigData copied = config.normalizedCopy();
        assertEquals("普通.pmx", copied.mainPmxFile);
        assertEquals("手持模型.PMX", copied.heldPmxFile);
        config.mainPmxFile = "changed.pmx";
        assertEquals("普通.pmx", copied.mainPmxFile);

        File saved = tempDir.resolve("model-config.json").toFile();
        copied.save(saved);
        ModelConfigData loaded = ModelConfigData.load(saved);
        assertEquals("普通.pmx", loaded.mainPmxFile);
        assertEquals("手持模型.PMX", loaded.heldPmxFile);
        assertFalse(loaded.heldPmxPhysicsEnabled);
        assertTrue(new ModelConfigData().heldPmxPhysicsEnabled);
        assertEquals(1.0f, loaded.heldPmxScale);
        assertEquals(0.0f, loaded.heldPmxOffsetX);
        assertEquals("", new ModelConfigData().heldPmxFile);
    }

    @Test
    void propTransformsAreNormalizedAndRoundTrip(@TempDir Path tempDir) {
        ModelConfigData original = new ModelConfigData();
        original.heldPmxScale = Float.NaN;
        original.heldPmxOffsetX = 500.0f;
        original.heldPmxOffsetY = -3.0f;
        original.heldPmxRotZ = Float.POSITIVE_INFINITY;
        ModelConfigData normalized = original.normalizedCopy();
        assertEquals(1.0f, normalized.heldPmxScale);
        assertEquals(20.0f, normalized.heldPmxOffsetX);
        assertEquals(-3.0f, normalized.heldPmxOffsetY);
        assertEquals(0.0f, normalized.heldPmxRotZ);

        File file = tempDir.resolve("transforms.json").toFile();
        normalized.save(file);
        ModelConfigData loaded = ModelConfigData.load(file);
        assertEquals(20.0f, loaded.heldPmxOffsetX);
        assertEquals(-3.0f, loaded.heldPmxOffsetY);
    }

    @Test
    void pmxSelectionsRejectNonBasenamesAndInvalidExtensions() {
        assertEquals("", ModelConfigData.normalizePmxFileName("../bad.pmx"));
        assertEquals("", ModelConfigData.normalizePmxFileName("sub/bad.pmx"));
        assertEquals("", ModelConfigData.normalizePmxFileName("sub\\\\bad.pmx"));
        assertEquals("", ModelConfigData.normalizePmxFileName("prop.pmd"));
        assertEquals("", ModelConfigData.normalizePmxFileName("bad.pmx" + (char) 0));
        assertEquals("角色.PMX", ModelConfigData.normalizePmxFileName("角色.PMX"));
    }

    @Test
    void propBoneAndStageMotionAreCopiedNormalizedAndPersisted(@TempDir Path dir) {
        ModelConfigData config=new ModelConfigData();
        config.heldPmxBone="  頭  "; config.heldPmxStageMotion="fan.VMD";
        ModelConfigData copy=config.normalizedCopy();
        config.heldPmxBone="changed";
        assertEquals("頭",copy.heldPmxBone); assertEquals("fan.VMD",copy.heldPmxStageMotion);
        File file=dir.resolve("props.json").toFile(); copy.save(file);
        ModelConfigData loaded=ModelConfigData.load(file);
        assertEquals("頭",loaded.heldPmxBone); assertEquals("fan.VMD",loaded.heldPmxStageMotion);
        assertEquals("",new ModelConfigData().heldPmxBone); assertEquals("",new ModelConfigData().heldPmxStageMotion);
        assertEquals("",ModelConfigData.normalizeVmdFileName("../bad.vmd"));
        assertEquals("",ModelConfigData.normalizeVmdFileName("bad.pmx"));
        assertEquals("",ModelConfigData.normalizeVmdFileName("C:bad.vmd"));
        assertEquals("",ModelConfigData.normalizeBoneName("head\n"));
    }
}
