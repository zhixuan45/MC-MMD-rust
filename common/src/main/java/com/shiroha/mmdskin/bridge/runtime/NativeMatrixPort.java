package com.shiroha.mmdskin.bridge.runtime;

import java.nio.ByteBuffer;

/** 文件职责：定义矩阵句柄与手部姿态相关的 native 能力边界。 */
public interface NativeMatrixPort {

    long createMatrix();

    void deleteMatrix(long matrixHandle);

    void populateHandMatrix(long modelHandle, long handMatrixHandle, boolean mainHand);

    /** Returns false without writing when the bone or model is absent. */
    default boolean populateBoneMatrix(long modelHandle, long matrixHandle, String boneName) { return false; }

    boolean copyMatrixToBuffer(long matrixHandle, ByteBuffer targetBuffer);

    /** Prop-to-owner matrix, including both render scales; not a camera matrix. */
    default boolean setPropPhysicsAttachment(long propHandle, long ownerHandle, ByteBuffer transform) { return false; }
    default boolean setPropBoneParents(long model, int[] indices, float[] matrices) { return indices.length == 0; }
    /** Replaces VMD outside-parent switch windows; windows are previous/next frame pairs. */
    default boolean setPropBoneWindows(long model, int[] indices, float[] windows) {
        return indices != null && windows != null && indices.length == 0 && windows.length == 0;
    }
    /** Global/local matrices, plus local rest origin when the buffer has at least 192 bytes. */
    default boolean getPropBonePose(long model, int index, ByteBuffer matrices) { return false; }
    default boolean evaluatePropPose(long model) { return false; }
}
