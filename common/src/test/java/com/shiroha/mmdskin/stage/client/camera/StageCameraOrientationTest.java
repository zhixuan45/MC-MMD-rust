package com.shiroha.mmdskin.stage.client.camera;

import net.minecraft.client.Camera;
import org.joml.Matrix4d;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageCameraOrientationTest {
    private static final float EPSILON = 1.0E-5f;

    @Test
    void oneDegreeRollShouldRemainOneDegreeInEitherDirection() {
        for (float roll : new float[]{-1.0f, 1.0f}) {
            TestCamera camera = camera(0.0f, 0.0f);
            applyRoll(camera, roll);
            assertVector(new Vector3f(0, 0, 1), camera.getLookVector());
            assertEquals(roll, Math.toDegrees(Math.atan2(-camera.getUpVector().x,
                    camera.getUpVector().y)), 1.0E-5);
        }
    }

    @Test
    void zeroRollShouldPreserveActualVanillaOrientationExactly() {
        TestCamera camera = camera(37.0f, 22.0f);
        Quaternionf original = new Quaternionf(camera.rotation());
        Vector3f forwards = new Vector3f(camera.getLookVector());
        Vector3f up = new Vector3f(camera.getUpVector());
        Vector3f left = new Vector3f(camera.getLeftVector());
        applyRoll(camera, 0.0f);
        assertEquals(original, camera.rotation());
        assertVector(forwards, camera.getLookVector());
        assertVector(up, camera.getUpVector());
        assertVector(left, camera.getLeftVector());
    }

    @Test
    void actual1201CameraBasisShouldMatchForgeViewMatrixForAllThreeAxes() {
        for (float yaw : new float[]{-170.0f, 0.0f, 37.0f, 180.0f, 370.0f}) {
            for (float pitch : new float[]{-90.0f, -22.0f, 0.0f, 89.999f, 90.0f}) {
                for (float roll : new float[]{-360.0f, -180.0f, -90.0f, -1.0f,
                        0.0f, 1.0f, 90.0f, 179.9f, 360.0f}) {
                    TestCamera camera = camera(yaw, pitch);
                    Vector3f originalForward = new Vector3f(camera.getLookVector());
                    applyRoll(camera, roll);
                    assertVector(originalForward, camera.getLookVector());
                    assertEquals(0.0f, camera.getUpVector().dot(camera.getLookVector()), EPSILON);
                    assertEquals(0.0f, camera.getUpVector().dot(camera.getLeftVector()), EPSILON);
                    // Forge 在 ComputeCameraAngles 后按此顺序构造真实世界视图。
                    Matrix4d world = new Matrix4d().rotationZ(Math.toRadians(roll))
                            .rotateX(Math.toRadians(pitch)).rotateY(Math.toRadians(yaw + 180.0f)).invert();
                    assertViewVector(direction(world, 0, 0, -1), camera.getLookVector());
                    assertViewVector(direction(world, 0, 1, 0), camera.getUpVector());
                    assertViewVector(direction(world, -1, 0, 0), camera.getLeftVector());
                }
            }
        }
    }

    @Test
    void changingRollShouldResetFromVanillaEveryFrame() {
        TestCamera camera = camera(37.0f, 22.0f);
        for (float roll : new float[]{90.0f, -90.0f, 1.0f, -1.0f, 0.0f}) {
            camera.setAngles(37.0f, 22.0f);
            applyRoll(camera, roll);
            Matrix4d world = new Matrix4d().rotationZ(Math.toRadians(roll))
                    .rotateX(Math.toRadians(22.0)).rotateY(Math.toRadians(217.0)).invert();
            assertVector(direction(world, 0, 1, 0), camera.getUpVector());
        }
    }

    private static TestCamera camera(float yaw, float pitch) {
        TestCamera camera = new TestCamera();
        camera.setAngles(yaw, pitch);
        return camera;
    }

    private static void applyRoll(Camera camera, float roll) {
        StageCameraOrientation.applyRoll(camera.rotation(), camera.getLookVector(),
                camera.getUpVector(), camera.getLeftVector(), roll);
    }

    private static Vector3f direction(Matrix4d world, double x, double y, double z) {
        Vector3d direction = world.transformDirection(new Vector3d(x, y, z));
        return new Vector3f((float) direction.x, (float) direction.y, (float) direction.z);
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertTrue(actual.equals(expected, EPSILON), () -> "expected " + expected + ", got " + actual);
    }

    private static void assertViewVector(Vector3f expected, Vector3f actual) {
        // 真实 1.20.1 Camera 的 float 四元数在竖直镜头实测误差最大 5.46e-5。
        assertTrue(actual.equals(expected, 1.0E-4f), () -> "expected " + expected + ", got " + actual);
    }

    private static final class TestCamera extends Camera {
        void setAngles(float yaw, float pitch) {
            setRotation(yaw, pitch);
        }
    }
}
