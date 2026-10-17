package com.shiroha.mmdskin.render.material;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelMaterialHairTest {
    @Test void recognizesXingCiAndCommonHairNames() {
        for (String name : new String[]{"後髪", "后長髪", "前发", "頭髮", "ヘア", "Hair", "BackHair",
                "HairBack", "hair_00", "Bangs.001", "ponytail"}) {
            assertTrue(material(name, "data/tianyi.png").isHair(), name);
        }
    }

    @Test void sharedAtlasAndAccessoriesDoNotBecomeHair() {
        for (String name : new String[]{"服装 外层", "body", "chair", "发饰", "髪飾り", "Hair Ribbon",
                "hairpin", "HairClip", "eyebrow", "", "mat_01"}) {
            assertFalse(material(name, "hair.png").isHair(), name);
        }
        assertFalse(material(null, "hair.png").isHair());
    }

    private static ModelMaterial material(String name, String texture) {
        ModelMaterial result = new ModelMaterial();
        result.name = name;
        result.texturePath = texture;
        return result;
    }
}
