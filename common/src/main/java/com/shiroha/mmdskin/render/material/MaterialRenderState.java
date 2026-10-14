package com.shiroha.mmdskin.render.material;

import java.nio.ByteBuffer;

/** PMX 材质与 56-float 材质 morph 的求值；不依赖 OpenGL。 */
public final class MaterialRenderState {
    public static final float OPAQUE_ALPHA = 0.999f;
    public final float[] color = new float[3];
    public final float[] specular = new float[3];
    public final float[] sphereMul = new float[4];
    public final float[] sphereAdd = new float[4];
    public float specularPower;
    public float alpha;

    public void resolve(ModelMaterial material, ByteBuffer morphs, int materialId, float effectiveAlpha) {
        for (int channel = 0; channel < 3; channel++) {
            float diffuse = morph(material.diffuse[channel], morphs, materialId, channel);
            float ambient = morph(material.ambient[channel], morphs, materialId, 8 + channel);
            float textureTint = morph(1.0f, morphs, materialId, 16 + channel);
            // Toon/原版共享的 PMX 基础光照色：默认 diffuse=1、ambient=.5 时保持白色。
            // 极端/负值须在相加后裁剪，不能先裁剪而丢失作者用于压黑的参数。
            color[channel] = clamp(diffuse * 0.5f + ambient) * Math.max(0.0f, textureTint);
            specular[channel] = morph(material.specular[channel], morphs, materialId, 4 + channel);
        }
        specularPower = Math.max(1.0f, morph(material.specularPower, morphs, materialId, 7));
        // effectiveAlpha 已包含 diffuse alpha morph 与全局透明度，各项只应用一次。
        alpha = clamp(effectiveAlpha * morph(1.0f, morphs, materialId, 19));
        for (int channel = 0; channel < 4; channel++) {
            // 保留独立乘加因子；球面 tint 的 alpha 控制 RGB，不控制表面透明度。
            sphereMul[channel] = morphFactor(morphs, materialId, 20 + channel, 1.0f);
            sphereAdd[channel] = morphFactor(morphs, materialId, 48 + channel, 0.0f);
        }
    }

    private static float morphFactor(ByteBuffer morphs, int materialId, int offset, float fallback) {
        int base = materialId * 56 * Float.BYTES;
        if (morphs == null || materialId < 0 || base + 56 * Float.BYTES > morphs.limit()) return fallback;
        return morphs.getFloat(base + offset * Float.BYTES);
    }

    private static float morph(float value, ByteBuffer morphs, int materialId, int offset) {
        int base = materialId * 56 * Float.BYTES;
        if (morphs == null || materialId < 0 || base + 56 * Float.BYTES > morphs.limit()) return value;
        return value * morphs.getFloat(base + offset * Float.BYTES)
                + morphs.getFloat(base + (28 + offset) * Float.BYTES);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
