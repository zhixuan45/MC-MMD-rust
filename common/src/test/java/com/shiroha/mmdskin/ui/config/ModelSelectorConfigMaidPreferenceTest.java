package com.shiroha.mmdskin.ui.config;

import com.shiroha.mmdskin.config.UIConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证女仆偏好兼容旧配置、容忍坏条目并强制原子保存。 */
class ModelSelectorConfigMaidPreferenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsLegacyFieldsAndValidMaidEntriesWhenOneEntryIsBad() throws Exception {
        UUID maidId = UUID.randomUUID();
        File configFile = temporaryDirectory.resolve("model_selector.json").toFile();
        Files.writeString(configFile.toPath(), """
                {
                  "playerModels": {"player": "PlayerModel"},
                  "quickModelSlots": {"0": "QuickModel"},
                  "maidModelPreferences": {
                    "%s": "MaidModel",
                    "bad-uuid": "BrokenModel",
                    "1-1-1-1-1": "ShortUuid",
                    "%s": 12
                  }
                }
                """.formatted(maidId.toString().toUpperCase(), UUID.randomUUID()), StandardCharsets.UTF_8);

        ModelSelectorConfig config = new ModelSelectorConfig(configFile);

        assertEquals("MaidModel", config.getMaidModelPreference(maidId));
        assertEquals("PlayerModel", config.getRawModel("player"));
        assertEquals("QuickModel", config.getQuickSlotModel(0));
        assertEquals(1, config.getMaidModelPreferences().size());
    }

    @Test
    void forcedSavePersistsExplicitDefaultAndLastRapidChoice() throws Exception {
        File configFile = temporaryDirectory.resolve("model_selector.json").toFile();
        ModelSelectorConfig config = new ModelSelectorConfig(configFile);
        UUID maidId = UUID.randomUUID();

        config.setMaidModelPreference(maidId, "FirstModel");
        config.setMaidModelPreference(maidId, UIConstants.DEFAULT_MODEL_NAME);
        ModelSelectorConfig reloaded = new ModelSelectorConfig(configFile);

        assertEquals(UIConstants.DEFAULT_MODEL_NAME, reloaded.getMaidModelPreference(maidId));
    }

    @Test
    void rapidQuickSlotChangesAreBothPersisted() {
        File configFile = temporaryDirectory.resolve("model_selector.json").toFile();
        ModelSelectorConfig config = new ModelSelectorConfig(configFile);

        config.setQuickSlotModel(0, "FirstModel");
        config.setQuickSlotModel(1, "SecondModel");
        ModelSelectorConfig reloaded = new ModelSelectorConfig(configFile);

        assertEquals("FirstModel", reloaded.getQuickSlotModel(0));
        assertEquals("SecondModel", reloaded.getQuickSlotModel(1));
    }

    @Test
    void reportsSaveFailureAndRollsBackTheInMemoryPreference() throws Exception {
        Path blockedParent = temporaryDirectory.resolve("parent-is-a-file");
        Files.writeString(blockedParent, "not a directory", StandardCharsets.UTF_8);
        ModelSelectorConfig config = new ModelSelectorConfig(blockedParent.resolve("model_selector.json").toFile());
        UUID maidId = UUID.randomUUID();

        assertThrows(IllegalStateException.class,
                () -> config.setMaidModelPreference(maidId, "MaidModel"));
        assertNull(config.getMaidModelPreference(maidId));
    }
}
