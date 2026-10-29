package com.shiroha.mmdskin.bridge.runtime;

import java.nio.ByteBuffer;

/** 文件职责：集中定义动画句柄、时间线与相机采样相关的 native 能力。 */
public interface NativeAnimationPort {

    long loadAnimation(long modelHandle, String animationPath);

    void deleteAnimation(long animationHandle);

    void mergeAnimation(long mergedAnimationHandle, long sourceAnimationHandle);

    boolean hasCameraData(long animationHandle);

    boolean hasBoneData(long animationHandle);

    boolean hasMorphData(long animationHandle);

    float getAnimationMaxFrame(long animationHandle);

    default float getCameraEndFrame(long animationHandle) { return hasCameraData(animationHandle) ? getAnimationMaxFrame(animationHandle) : -1; }
    default float getMotionEndFrame(long animationHandle) { return hasBoneData(animationHandle) || hasMorphData(animationHandle) ? getAnimationMaxFrame(animationHandle) : -1; }

    void seekLayer(long modelHandle, long layer, float frame);

    /** 设置动画层播放速度；默认实现保持轻量测试替身兼容。 */
    default void setLayerSpeed(long modelHandle, long layer, float speed) {
    }

    void getCameraTransform(long animationHandle, float frame, ByteBuffer targetBuffer);
}
