package com.shiroha.mmdskin.render.material;

import com.shiroha.mmdskin.bridge.runtime.NativeModelLoadPort;
import com.shiroha.mmdskin.texture.runtime.TextureRepository;

import java.util.List;

/** 集中处理 PMX 主纹理、球面纹理与无纹理颜色回退。 */
public final class MaterialTextureLoader {
    private MaterialTextureLoader() {
    }

    public static ModelMaterial loadMaterial(NativeModelLoadPort port, long modelHandle,
                                              int materialIndex, List<String> textureKeys) {
        ModelMaterial material = new ModelMaterial();
        material.diffuse = port.getMaterialDiffuseColor(modelHandle, materialIndex);
        material.ambient = port.getMaterialAmbientColor(modelHandle, materialIndex);
        material.specular = port.getMaterialSpecularColor(modelHandle, materialIndex);
        material.specularPower = port.getMaterialSpecularPower(modelHandle, materialIndex);
        String path = port.getMaterialTexturePath(modelHandle, materialIndex);
        material.texturePath = path == null ? "" : path;
        if (path == null || path.isEmpty()) {
            TextureRepository.Texture texture = TextureRepository.createMaterialColorTexture(
                    new float[] {1.0f, 1.0f, 1.0f, 1.0f});
            material.tex = texture.tex;
            material.hasAlpha = texture.hasAlpha;
            material.ownsTexture = true;
        } else {
            TextureRepository.Texture texture = TextureRepository.GetTexture(path, "diffuse",
                    "render modelHandle=" + modelHandle + " materialIndex=" + materialIndex);
            if (texture != null) {
                material.tex = texture.tex;
                material.hasAlpha = texture.hasAlpha;
                material.hasTranslucentPixels = texture.hasTranslucentPixels;
                material.alphaCoverage = texture.alphaCoverage;
                TextureRepository.addRef(path);
                textureKeys.add(path);
            }
        }
        try {
            loadSphere(port, modelHandle, materialIndex, textureKeys, material);
        } catch (RuntimeException | Error failure) {
            // 赋值到实例材质数组之前失败，仍须释放已创建的独占回退纹理。
            releaseOwnedTextures(new ModelMaterial[] {material});
            throw failure;
        }
        return material;
    }

    private static void loadSphere(NativeModelLoadPort port, long modelHandle, int materialIndex,
                                   List<String> textureKeys, ModelMaterial material) {
        material.sphereMode = port.getMaterialSphereTextureMode(modelHandle, materialIndex);
        String spherePath = port.getMaterialSphereTexturePath(modelHandle, materialIndex);
        material.sphereTexturePath = spherePath == null ? "" : spherePath;
        // 模式来自 PMX 字段，不能根据 PNG/SPH/SPA 文件名猜测。
        if ((material.sphereMode == 1 || material.sphereMode == 2) && !material.sphereTexturePath.isEmpty()) {
            TextureRepository.Texture sphere = TextureRepository.GetTexture(material.sphereTexturePath, "sphere",
                    "render modelHandle=" + modelHandle + " materialIndex=" + materialIndex);
            if (sphere != null) {
                material.sphereTex = sphere.tex;
                TextureRepository.addRef(material.sphereTexturePath);
                textureKeys.add(material.sphereTexturePath);
            }
        }
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
