package com.shiroha.mmdskin.bridge.runtime;

import java.nio.ByteBuffer;

/** 文件职责：定义矩阵句柄与手部姿态相关的 native 能力边界。 */
public interface NativeMatrixPort {

    long createMatrix();

    void deleteMatrix(long matrixHandle);

    void populateHandMatrix(long modelHandle, long handMatrixHandle, boolean mainHand);

    /** Returns false without writing when the bone or model is absent. */
    default boolean populateBoneMatrix(long modelHandle, long matrixHandle, String boneName) { throw new UnsupportedOperationException("Bone matrices are not supported"); }

    boolean copyMatrixToBuffer(long matrixHandle, ByteBuffer targetBuffer);

    /** Prop-to-owner matrix, including both render scales; not a camera matrix. */
    default boolean setPropPhysicsAttachment(long propHandle, long ownerHandle, ByteBuffer transform) { throw new UnsupportedOperationException("Prop physics attachment is not supported"); }
    default boolean setPropBoneParents(long model, int[] indices, float[] matrices) {
        throw new UnsupportedOperationException("Split prop parent updates are not supported");
    }
    /** Replaces VMD outside-parent switch windows; windows are previous/next frame pairs. */
    default boolean setPropBoneWindows(long model, int[] indices, float[] windows) {
        throw new UnsupportedOperationException("Split prop window updates are not supported");
    }
    /** Atomically commits parent and window inputs. Zero means success. */
    default int setPropBindings(long model, int[] parentIndices, float[] matrices,
                               int[] windowIndices, float[] windows) {
        throw new UnsupportedOperationException("Atomic prop binding is not supported");
    }
    /** Fixed global/local/rest matrices, exactly 192 bytes. */
    default boolean getPropBonePose(long model, int index, ByteBuffer matrices) { throw new UnsupportedOperationException("Prop pose packets are not supported"); }
    default boolean evaluatePropPose(long model) { throw new UnsupportedOperationException("Prop pose evaluation is not supported"); }
}
