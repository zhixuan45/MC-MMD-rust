package com.shiroha.mmdskin.bridge.runtime;

/** 文件职责：定义模型加载与材质主贴图查询相关的 native 能力边界。 */
public interface NativeModelLoadPort {

    long loadPmxModel(String modelFilePath, String modelDir, long layerCount);

    long loadPmdModel(String modelFilePath, String modelDir, long layerCount);

    long loadVrmModel(String modelFilePath, String modelDir, long layerCount);

    String getMaterialTexturePath(long modelHandle, int materialIndex);

    default float[] getMaterialDiffuseColor(long modelHandle, int materialIndex) {
        return new float[] {1.0f, 1.0f, 1.0f, 1.0f};
    }

    default float[] getMaterialAmbientColor(long modelHandle, int materialIndex) {
        return new float[] {0.5f, 0.5f, 0.5f};
    }

    default float[] getMaterialSpecularColor(long modelHandle, int materialIndex) {
        return new float[] {0.0f, 0.0f, 0.0f};
    }

    default float getMaterialSpecularPower(long modelHandle, int materialIndex) {
        return 0.0f;
    }
}
