package com.shiroha.mmdskin.stage.client.camera;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 在原版 yaw/pitch 重置后应用 Roll；公共相机角度均为度。 */
public final class StageCameraOrientation {
    private StageCameraOrientation() {
    }

    public static void applyRoll(Quaternionf rotation, Vector3f forwards, Vector3f up,
                                 Vector3f left, float rollDegrees) {
        if (rollDegrees == 0.0f) {
            return;
        }
        // 后乘局部 Z 旋转，与 NeoForge 三轴 Camera.setRotation 保持一致。
        rotation.rotateZ(-(float) Math.toRadians(rollDegrees));
        // 使用原版相机基向量，保证视锥、粒子与视图矩阵一致。
        forwards.set(0.0f, 0.0f, -1.0f).rotate(rotation);
        up.set(0.0f, 1.0f, 0.0f).rotate(rotation);
        left.set(-1.0f, 0.0f, 0.0f).rotate(rotation);
    }
}
