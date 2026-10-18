package com.shiroha.mmdskin.bridge.runtime;

import java.nio.ByteBuffer;

/** 文件职责：定义渲染后端读取模型数据与驱动 native 更新的能力边界。 */
public interface NativeRenderBackendPort extends NativeModelPort, NativeModelLoadPort, NativeModelQueryPort, NativeScenePort {

    void changeModelAnimation(long modelHandle, long animationHandle, long layer);

    void transitionLayerTo(long modelHandle, long layer, long animationHandle, float transitionTime);

    void setLayerLoop(long modelHandle, long layer, boolean loop);

    /** 设置动画层播放速度；用于舞台外部帧驱动。 */
    default void setLayerSpeed(long modelHandle, long layer, float speed) {
    }

    default void setLayerWeight(long modelHandle, long layer, float weight) {
        // 兼容不需要动画混合能力的轻量测试后端。
    }

    void resetModelPhysics(long modelHandle);

    void setPhysicsEnabled(long modelHandle, boolean enabled);

    void updateModel(long modelHandle, float deltaTime);

    void updateAnimationOnly(long modelHandle, float deltaTime);

    /** False: capability unavailable and no evaluation performed. */
    default boolean updateModelProfiled(long handle, float delta, boolean cpuSkinning, ByteBuffer output) { return false; }

    default String takePhysicsDebugDiagnostic(long modelHandle) {
        // 测试替身和不支持诊断的后端默认没有待输出消息。
        return null;
    }

    default String getIssueDebugDiagnostic(long modelHandle, int flags) {
        // 无诊断能力的测试或替代后端保持兼容。
        return null;
    }

    default String takeRustLogs() {
        // Rust 日志是进程级队列，轻量测试后端无需实现。
        return null;
    }

    int getIndexElementSize(long modelHandle);

    long getIndexDataAddress(long modelHandle);

    long getFirstPersonIndexCount(long modelHandle);

    long getFirstPersonIndexDataAddress(long modelHandle);

    int refreshFirstPersonIndices(long modelHandle, ByteBuffer matrices,
                                  boolean gpuSkinning, ByteBuffer indices);

    long getPositionDataAddress(long modelHandle);

    long getNormalDataAddress(long modelHandle);

    long getUvDataAddress(long modelHandle);

    void copyNativeDataToBuffer(ByteBuffer targetBuffer, long sourceAddress, int size);

    int copyOriginalPositionsToBuffer(long modelHandle, ByteBuffer targetBuffer, int vertexCount);

    int copyOriginalNormalsToBuffer(long modelHandle, ByteBuffer targetBuffer, int vertexCount);

    int copyBoneIndicesToBuffer(long modelHandle, ByteBuffer targetBuffer, int vertexCount);

    int copyBoneWeightsToBuffer(long modelHandle, ByteBuffer targetBuffer, int vertexCount);

    int copySkinningMatricesToBuffer(long modelHandle, ByteBuffer targetBuffer);

    void initGpuSkinningData(long modelHandle);

    void initGpuMorphData(long modelHandle);

    void initGpuUvMorphData(long modelHandle);

    long copyGpuMorphOffsetsToBuffer(long modelHandle, ByteBuffer targetBuffer);

    int copyGpuMorphWeightsToBuffer(long modelHandle, ByteBuffer targetBuffer);

    long copyGpuUvMorphOffsetsToBuffer(long modelHandle, ByteBuffer targetBuffer);

    int copyGpuUvMorphWeightsToBuffer(long modelHandle, ByteBuffer targetBuffer);

    int getMaterialMorphResultCount(long modelHandle);

    int copyMaterialMorphResultsToBuffer(long modelHandle, ByteBuffer targetBuffer);

    int getSubMeshCount(long modelHandle);

    int batchGetSubMeshData(long modelHandle, ByteBuffer targetBuffer, boolean firstPersonView);
}
