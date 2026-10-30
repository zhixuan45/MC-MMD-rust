package com.shiroha.mmdskin.render.material;

import java.nio.ByteBuffer;

/** 仅在一次模型绘制内复用材质求值，下次绘制重新读取 morph 和透明度。 */
final class DrawMaterialCache {
    private final ModelMaterial[] materials;
    private final ByteBuffer morphs;
    private final SubMeshDrawHelper.AlphaResolver alphas;
    private final SubMeshDrawHelper.TextureResolver textures;
    private final MaterialRenderState[] states;
    private final int[] baseAlphas;
    private final int[] textureIds;
    private final boolean[] textureResolved;
    private final com.shiroha.mmdskin.render.effectpack.FxMaterialData[] effects;

    DrawMaterialCache(ModelMaterial[] materials, ByteBuffer morphs, SubMeshDrawHelper.AlphaResolver alphas) {
        this(materials, morphs, alphas, null);
    }

    DrawMaterialCache(ModelMaterial[] materials, ByteBuffer morphs,
                      SubMeshDrawHelper.AlphaResolver alphas,
                      SubMeshDrawHelper.TextureResolver textures) {
        this.materials = materials;
        this.morphs = morphs;
        this.alphas = alphas;
        this.textures = textures;
        states = new MaterialRenderState[materials.length];
        baseAlphas = new int[materials.length];
        textureIds = new int[materials.length];
        textureResolved = new boolean[materials.length];
        effects = new com.shiroha.mmdskin.render.effectpack.FxMaterialData[materials.length];
    }

    MaterialRenderState resolve(int materialId, float baseAlpha) {
        MaterialRenderState state = states[materialId];
        int bits = Float.floatToRawIntBits(baseAlpha);
        if (state == null || baseAlphas[materialId] != bits) {
            if (state == null) states[materialId] = state = new MaterialRenderState();
            state.resolve(materials[materialId], morphs, materialId, alphas.resolve(materialId, baseAlpha));
            baseAlphas[materialId] = bits;
        }
        return state;
    }

    int texture(int materialId) {
        if (textures == null) throw new IllegalStateException("Texture resolver is not configured");
        if (!textureResolved[materialId]) {
            textureIds[materialId] = textures.resolve(materialId);
            textureResolved[materialId] = true;
        }
        return textureIds[materialId];
    }

    com.shiroha.mmdskin.render.effectpack.FxMaterialData effect(int materialId) {
        if (effects[materialId] == null) effects[materialId] = com.shiroha.mmdskin.render.effectpack.FxMaterialData.capture(materialId, materials[materialId].name);
        return effects[materialId];
    }
}
