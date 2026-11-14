package com.shiroha.mmdskin.render.material;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelMaterialSkinTest {
    @Test
    void recognizesCommonSkinFaceAndBodyMaterials() {
        for (String name : new String[]{
                "肌", "素肌", "皮膚", "皮肤", "肌肤", "顔", "脸", "体", "身体", "素体",
                "Skin", "Face", "Body", "FaceSkin", "Body_Skin", "F00_000_00_Face_00_SKIN",
                "F00_000_00_Body_00_SKIN", "腕", "手", "足", "脚", "Neck", "Arms", "Legs"
        }) {
            assertTrue(material(name, "data/cloth.png").isSkin(), name);
        }
    }

    @Test
    void excludesEyesHairClothingAccessoriesAndOverlays() {
        for (String name : new String[]{
                "髪", "前髪", "Hair", "瞳", "iris", "eye_white", "眼白", "eyelash", "まつげ",
                "eyebrow", "眉", "mouth", "teeth", "舌", "服装", "体服", "体操服", "body_cloth",
                "bodysuit", "手袋", "手甲", "首輪", "首飾り", "choker", "足袋", "shoe", "socks",
                "blush", "チーク", "頬染", "顔影", "face_shadow", "outline", "描边",
                "headphone", "イヤホン", "", "mat_01"
        }) {
            assertFalse(material(name, "skin.png").isSkin(), name);
        }
        assertFalse(material(null, "skin.png").isSkin());
    }

    private static ModelMaterial material(String name, String texture) {
        ModelMaterial result = new ModelMaterial();
        result.name = name;
        result.texturePath = texture;
        return result;
    }
}
