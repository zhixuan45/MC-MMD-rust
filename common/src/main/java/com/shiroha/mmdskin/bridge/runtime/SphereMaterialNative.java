package com.shiroha.mmdskin.bridge.runtime;

/** PMX 球面贴图的轻量 JNI 查询入口。 */
public final class SphereMaterialNative {
    private SphereMaterialNative() {
    }

    public static native int getMode(long modelHandle, int materialIndex);
}
