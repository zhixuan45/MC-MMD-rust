package com.shiroha.mmdskin.asset.catalog;

import com.shiroha.mmdskin.config.ModelConfigData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HeldPmxAssetsTest {
    @TempDir Path root;

    @Test void siblingPropUsesItsOwnFolderWithoutChangingTheOwnerAsset() throws Exception {
        Path actor = Files.createDirectory(root.resolve("角色A"));
        Path prop = Files.createDirectory(root.resolve("扇子B"));
        Files.writeString(actor.resolve("model.pmx"), "actor");
        Files.writeString(actor.resolve("fan.pmx"), "local prop");
        Files.writeString(prop.resolve("fan.pmx"), "external prop");
        Files.createDirectory(prop.resolve("anims"));
        Files.writeString(prop.resolve("anims/fan.vmd"), "motion");

        var external = HeldPmxAssets.resolve(root, "角色A", "扇子B/fan.pmx");
        assertNotNull(external);
        assertEquals(prop.toRealPath(), Path.of(external.getFolderPath()).toRealPath());
        assertEquals("external prop", Files.readString(Path.of(external.getModelFilePath())));
        assertEquals(prop.resolve("anims/fan.vmd"),
                HeldPmxMotionFiles.resolve(Path.of(external.getFolderPath()), "fan.vmd"));
        assertEquals("local prop", Files.readString(Path.of(
                HeldPmxAssets.resolve(root, "角色A", "fan.pmx").getModelFilePath())));
        assertEquals("actor", Files.readString(actor.resolve("model.pmx")));
    }

    @Test void invalidOrMissingReferencesNeverSelectAnAutomaticCharacterModel() throws Exception {
        Path prop = Files.createDirectory(root.resolve("B"));
        Files.writeString(prop.resolve("model.pmx"), "character");
        for (String reference : new String[]{"", "B/missing.pmx", "missing/fan.pmx", "../model.pmx",
                "B/../model.pmx", "/model.pmx", "C:/model.pmx", "B\\model.pmx", "B/model.pmd"}) {
            assertNull(HeldPmxAssets.resolve(root, "A", reference), reference);
        }
        assertNull(HeldPmxAssets.resolve(root, "../B", "model.pmx"));
    }

    @Test void crossFolderSelectionSurvivesCopyAndPersistenceWhileCharacterNamesStayBasenames() {
        var config = new ModelConfigData();
        config.mainPmxFile = "actor.pmx";
        config.heldPmxFile = "扇子B/fan.PMX";
        config.heldPmxBone = "左手首";
        config.heldPmxStageMotion = "fan.vmd";
        var file = root.resolve("config.json").toFile();
        config.normalizedCopy().save(file);
        var loaded = ModelConfigData.load(file);
        assertEquals("actor.pmx", loaded.mainPmxFile);
        assertEquals("扇子B/fan.PMX", loaded.heldPmxFile);
        assertEquals("左手首", loaded.heldPmxBone);
        assertEquals("fan.vmd", loaded.heldPmxStageMotion);
        assertEquals("", ModelConfigData.normalizePmxFileName("扇子B/fan.PMX"));
    }
}
