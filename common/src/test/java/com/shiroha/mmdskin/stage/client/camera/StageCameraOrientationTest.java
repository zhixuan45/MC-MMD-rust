package com.shiroha.mmdskin.stage.client.camera;

import org.joml.Matrix4f;
import org.joml.Matrix4d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageCameraOrientationTest {
    private static final float EPSILON = 1.0E-5f;

    @Test
    void oneDegreeRollShouldRemainOneDegree() {
        Quaternionf rotation = new Quaternionf();
        Vector3f forwards = new Vector3f(0, 0, -1);
        Vector3f up = new Vector3f(0, 1, 0);
        Vector3f left = new Vector3f(-1, 0, 0);

        StageCameraOrientation.applyRoll(rotation, forwards, up, left, 1.0f);

        float angle = (float) Math.toRadians(1.0);
        assertVector(new Vector3f(0, 0, -1), new Vector3f(0, 0, -1).rotate(rotation));
        assertVector(new Vector3f((float) Math.sin(angle), (float) Math.cos(angle), 0), up);
        assertVector(new Vector3f(-(float) Math.cos(angle), (float) Math.sin(angle), 0), left);
        assertEquals(1.0, Math.toDegrees(Math.atan2(up.x, up.y)), 1.0E-5);
    }

    @Test
    void zeroRollShouldPreserveVanillaOrientationExactly() {
        Quaternionf rotation = vanillaRotation(37.0f, 22.0f);
        Quaternionf original = new Quaternionf(rotation);
        Vector3f forwards = new Vector3f(0, 0, -1).rotate(rotation);
        Vector3f up = new Vector3f(0, 1, 0).rotate(rotation);
        Vector3f left = new Vector3f(-1, 0, 0).rotate(rotation);

        StageCameraOrientation.applyRoll(rotation, forwards, up, left, 0.0f);

        assertEquals(original, rotation);
        assertVector(new Vector3f(0, 0, -1).rotate(original), forwards);
        assertVector(new Vector3f(0, 1, 0).rotate(original), up);
        assertVector(new Vector3f(-1, 0, 0).rotate(original), left);
    }

    @Test
    void tiltedCameraShouldKeepForwardAndMatchNeoForgeAndViewMatrix() {
        for (float yaw : new float[]{-170.0f, 0.0f, 37.0f, 180.0f, 370.0f}) {
            for (float pitch : new float[]{-90.0f, -22.0f, 0.0f, 89.999f, 90.0f}) {
                for (float roll : new float[]{-180.0f, -1.0f, 1.0f, 90.0f, 179.9f}) {
                    Quaternionf rotation = vanillaRotation(yaw, pitch);
                    Vector3f forwards = new Vector3f(0, 0, -1).rotate(rotation);
                    Vector3f up = new Vector3f(0, 1, 0).rotate(rotation);
                    Vector3f left = new Vector3f(-1, 0, 0).rotate(rotation);
                    Vector3f originalForward = new Vector3f(forwards);

                    StageCameraOrientation.applyRoll(rotation, forwards, up, left, roll);

                    // 从最终四元数重建，能发现局部/世界旋转次序错误。
                    assertVector(originalForward, new Vector3f(0, 0, -1).rotate(rotation));
                    assertVector(originalForward, forwards);
                    assertVector(new Vector3f(0, 1, 0).rotate(rotation), up);
                    assertVector(new Vector3f(-1, 0, 0).rotate(rotation), left);

                    Quaternionf neoForge = new Quaternionf().rotationYXZ(
                            (float) Math.PI - radians(yaw), -radians(pitch), -radians(roll));
                    assertVector(new Vector3f(0, 1, 0).rotate(neoForge), up);
                    assertVector(new Vector3f(-1, 0, 0).rotate(neoForge), left);
                    assertEquals(0.0f, up.dot(forwards), EPSILON);
                    assertEquals(0.0f, up.dot(left), EPSILON);

                    // 半角接近 90° 的 JOML 浮点开方误差实测 5.4e-5，矩阵容差取 1e-4。
                    Matrix4d expectedView = new Matrix4d().rotationZ(Math.toRadians(roll))
                            .rotateX(Math.toRadians(pitch)).rotateY(Math.toRadians(yaw) - Math.PI);
                    Matrix4f actualView = new Matrix4f().rotation(new Quaternionf(rotation).conjugate());
                    assertTrue(new Matrix4d(actualView).equals(expectedView, 1.0E-4),
                            () -> "yaw=" + yaw + ", pitch=" + pitch + ", roll=" + roll
                                    + "\nactual:\n" + actualView + "expected:\n" + expectedView);
                }
            }
        }
    }

    private static Quaternionf vanillaRotation(float yaw, float pitch) {
        return new Quaternionf().rotationYXZ((float) Math.PI - radians(yaw), -radians(pitch), 0.0f);
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertTrue(actual.equals(expected, EPSILON), () -> "expected " + expected + ", got " + actual);
    }
}
