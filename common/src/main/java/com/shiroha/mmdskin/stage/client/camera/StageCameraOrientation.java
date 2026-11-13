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
        // 1.20.1 Camera 使用 rotationYXZ(-yaw, pitch, 0)，局部 +Z 是前方。
        // 与 Forge 的视图 Rz(roll) * Rx(pitch) * Ry(yaw + 180°) 对应。
        rotation.rotateZ((float) Math.toRadians(rollDegrees));
        forwards.set(0.0f, 0.0f, 1.0f).rotate(rotation);
        up.set(0.0f, 1.0f, 0.0f).rotate(rotation);
        left.set(1.0f, 0.0f, 0.0f).rotate(rotation);
    }
}
