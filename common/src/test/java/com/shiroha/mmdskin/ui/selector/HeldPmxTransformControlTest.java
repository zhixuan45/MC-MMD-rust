package com.shiroha.mmdskin.ui.selector;

import com.shiroha.mmdskin.config.ModelConfigData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HeldPmxTransformControlTest {
    @Test
    void uiEditsSurviveTheEditableCopyAndSaveLoadPath(@TempDir Path directory) {
        ModelConfigData config = new ModelConfigData();
        HeldPmxTransformControl.ROT_X.set(config, 90);
        HeldPmxTransformControl.ROT_Y.set(config, -135);
        HeldPmxTransformControl.ROT_Z.set(config, 27);
        HeldPmxTransformControl.OFFSET_X.set(config, 1.2f);
        HeldPmxTransformControl.OFFSET_Y.set(config, -3.4f);
        HeldPmxTransformControl.OFFSET_Z.set(config, 0.5f);
        HeldPmxTransformControl.SCALE.set(config, 0.75f);

        var file = directory.resolve("model.json").toFile();
        config.normalizedCopy().save(file);
        ModelConfigData loaded = ModelConfigData.load(file);
        assertEquals(90, loaded.heldPmxRotX);
        assertEquals(-135, loaded.heldPmxRotY);
        assertEquals(27, loaded.heldPmxRotZ);
        assertEquals(1.2f, loaded.heldPmxOffsetX, 0.00001f);
        assertEquals(-3.4f, loaded.heldPmxOffsetY, 0.00001f);
        assertEquals(0.5f, loaded.heldPmxOffsetZ, 0.00001f);
        assertEquals(0.75f, loaded.heldPmxScale);

        loaded.mainPmxFile = "character.pmx";
        loaded.heldPmxFile = "prop.pmx";
        loaded.heldItemScale = 0.5f;
        loaded.hiddenMaterials.add(3);
        HeldPmxTransformControl.reset(loaded);
        assertEquals(0, loaded.heldPmxRotX);
        assertEquals(0, loaded.heldPmxRotY);
        assertEquals(0, loaded.heldPmxRotZ);
        assertEquals(0, loaded.heldPmxOffsetX);
        assertEquals(0, loaded.heldPmxOffsetY);
        assertEquals(0, loaded.heldPmxOffsetZ);
        assertEquals(1, loaded.heldPmxScale);
        assertEquals("character.pmx", loaded.mainPmxFile);
        assertEquals("prop.pmx", loaded.heldPmxFile);
        assertEquals(0.5f, loaded.heldItemScale);
        assertTrue(loaded.hiddenMaterials.contains(3));
    }
}
