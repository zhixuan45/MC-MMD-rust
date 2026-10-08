package com.shiroha.mmdskin.render.material;

import com.shiroha.mmdskin.bridge.runtime.NativeModelLoadPort;
import com.shiroha.mmdskin.texture.runtime.TextureRepository;

import java.util.List;

/** 集中处理 PMX 材质主纹理与无纹理颜色回退。 */
public final class MaterialTextureLoader {
    private MaterialTextureLoader() {
    }

    public static ModelMaterial loadMaterial(NativeModelLoadPort port, long modelHandle,
                                              int materialIndex, List<String> textureKeys) {
        ModelMaterial material = new ModelMaterial();
        String path = port.getMaterialTexturePath(modelHandle, materialIndex);
        material.texturePath = path == null ? "" : path;
        if (path == null || path.isEmpty()) {
            TextureRepository.Texture texture = TextureRepository.createMaterialColorTexture(
                    port.getMaterialDiffuseColor(modelHandle, materialIndex));
            material.tex = texture.tex;
            material.hasAlpha = texture.hasAlpha;
            material.ownsTexture = true;
        } else {
            TextureRepository.Texture texture = TextureRepository.GetTexture(path);
            if (texture != null) {
                material.tex = texture.tex;
                material.hasAlpha = texture.hasAlpha;
                TextureRepository.addRef(path);
                textureKeys.add(path);
            }
        }
        return material;
    }

    public static void releaseOwnedTextures(ModelMaterial[] materials) {
        if (materials == null) return;
        for (ModelMaterial material : materials) {
            if (material != null && material.ownsTexture && material.tex > 0) {
                org.lwjgl.opengl.GL46C.glDeleteTextures(material.tex);
                material.tex = 0;
                material.ownsTexture = false;
            }
        }
    }
}
