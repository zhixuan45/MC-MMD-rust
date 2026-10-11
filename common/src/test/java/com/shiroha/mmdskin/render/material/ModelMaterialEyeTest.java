package com.shiroha.mmdskin.render.material;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelMaterialEyeTest {
    @Test void recognizesEyesAndIrisInModelNames() {
        for (String name : new String[]{"Eyes", "eye_L", "Iris.001", "pupil", "瞳", "虹彩", "目玉"}) {
            assertTrue(material(name, "body.png").isEye(), name);
        }
        assertTrue(material("", "textures/eye_green.png").isEye());
    }

    @Test void excludesSkinAndFacialLinesEvenWithSharedEyeTexture() {
        for (String name : new String[]{"face", "body", "eyelash", "eyelid", "eyebrow", "eyeline", "eye_shadow", "眉", "瞳睫毛"}) {
            assertFalse(material(name, "eye.png").isEye(), name);
        }
        assertFalse(material("", "textures/highlight.png").isEye());
    }

    private static ModelMaterial material(String name, String path) {
        ModelMaterial material = new ModelMaterial();
        material.name = name; material.texturePath = path;
        return material;
    }
}
