package com.shiroha.mmdskin.render.material;

import java.util.Locale;

/** MMD 模型材质定义。 */
public class ModelMaterial {
    private static final String[] FACIAL_TOKENS = {
            "eye", "eyes", "eyeline", "eyelash", "eyelid", "iris", "pupil", "brow", "eyebrow",
            "mouth", "lip", "teeth", "tooth", "tongue", "gum", "lash", "highlight", "eyeshadow",
            "瞳", "目", "眉", "睫", "口", "唇", "牙", "舌", "ハイライト", "まつげ", "くち",
            "くちびる", "アイ", "アイライン", "アイラッシュ"
    };

    public int tex = 0;
    public boolean hasAlpha = false;
    public String name = "";
    public String texturePath = "";
    public boolean ownsTexture = false;
    public float[] diffuse = {1.0f, 1.0f, 1.0f, 1.0f};
    public float[] ambient = {0.5f, 0.5f, 0.5f};
    public float[] specular = {0.0f, 0.0f, 0.0f};
    public float specularPower;
    public boolean hasTranslucentPixels;

    private Boolean cachedIsFacialFeature;
    private Boolean cachedIsEye;
    private Float cachedEyeEmissionMaskMode;

    public float eyeEmissionMaskMode() {
        if (cachedEyeEmissionMaskMode == null) cachedEyeEmissionMaskMode = EyeMaterialMask.classify(name);
        return cachedEyeEmissionMaskMode;
    }

    public boolean isEye() {
        if (cachedIsEye == null) {
            // 材质名优先，避免脸材质共用 eye.png 时把整张脸点亮。
            cachedIsEye = isEyeLabel(name) || ((name == null || name.isBlank()) && isEyeLabel(texturePath));
        }
        return cachedIsEye;
    }

    private static boolean isEyeLabel(String value) {
        if (value == null) return false;
        String label = value.toLowerCase(Locale.ROOT);
        for (String excluded : new String[]{"lash", "lid", "brow", "line", "shadow", "睫", "眉", "まつげ", "アイライン"}) {
            if (label.contains(excluded)) return false;
        }
        return label.contains("iris") || label.contains("pupil") || label.contains("瞳")
                || label.contains("虹彩") || label.contains("眼球") || label.contains("目玉")
                || label.matches(".*(?:^|[^a-z])eyes?(?:[^a-z]|$).*");
    }

    public boolean isFacialFeature() {
        if (cachedIsFacialFeature == null) {
            cachedIsFacialFeature = containsFacialToken(name) || containsFacialToken(texturePath);
        }
        return cachedIsFacialFeature;
    }

    private static boolean containsFacialToken(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String token : FACIAL_TOKENS) {
            if (normalized.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
