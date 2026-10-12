package com.shiroha.mmdskin.render.material;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EyeMaterialMaskTest {
    @Test void explicitEyeRegionsIncludeRimAndHighlight() {
        for (String name : new String[]{"瞳", "美瞳外圈", "黒目", "iris_rim", "pupil", "eye_hi2", "eye_highlight"})
            assertEquals(1f, EyeMaterialMask.classify(name), name);
    }

    @Test void whitesAndLensesOverrideEyeNames() {
        for (String name : new String[]{"眼白", "eye_white", "白目", "iris_lens", "美瞳镜片", "eye_glasses", "eye_hi_眼白", "eyeliner", "眉"})
            assertEquals(-1f, EyeMaterialMask.classify(name), name);
    }

    @Test void SharedOrUnknownRegionsStillRequireEyeBones() {
        for (String name : new String[]{"目", "Eyes", "目縁", "Face", "highlight", "Irishat", ""})
            assertEquals(0f, EyeMaterialMask.classify(name), name);
        ModelMaterial material = new ModelMaterial();
        material.texturePath = "eye.png";
        assertEquals(0f, material.eyeEmissionMaskMode());
    }
}
