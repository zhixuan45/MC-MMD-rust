package com.shiroha.mmdskin.render.backend;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class ShadowModelViewMatrixTest {
    private static final float EPSILON = 0.00001f;

    @Test
    void shadowPoseShouldUploadWithoutMainCameraMultiplication() {
        // 1.20.1 的阴影 PoseStack 已合成光源视图、实体位移与模型缩放。
        Matrix4f shadowPose = new Matrix4f().rotateX((float) Math.PI / 2)
                .translate(2, 3, 4).scale(0.5f);
        Matrix4f originalShadowPose = new Matrix4f(shadowPose);
        Matrix4f destination = new Matrix4f();
        Vector3f vertex = new Vector3f(2, 0, 0);

        Matrix4f result = BaseModelInstance.composeModelViewMatrix(shadowPose, destination);
        assertSame(destination, result);
        assertTrue(result.equals(shadowPose, EPSILON));
        assertTrue(shadowPose.equals(originalShadowPose, EPSILON));
        assertTrue(result.transformPosition(vertex, new Vector3f())
                .equals(new Vector3f(3, -4, 3), EPSILON));
    }

    @Test
    void worldPoseShouldIncludeCameraExactlyOnce() {
        Matrix4f cameraView = new Matrix4f().rotateY((float) Math.PI / 2);
        Matrix4f entity = new Matrix4f().translate(2, 3, 4).scale(0.5f);
        Matrix4f worldPose = cameraView.mul(entity, new Matrix4f());
        Matrix4f originalWorldPose = new Matrix4f(worldPose);

        Matrix4f result = BaseModelInstance.composeModelViewMatrix(worldPose, new Matrix4f());
        assertTrue(result.equals(worldPose, EPSILON));
        assertTrue(worldPose.equals(originalWorldPose, EPSILON));
        assertTrue(result.transformPosition(new Vector3f(2, 0, 0), new Vector3f())
                .equals(new Vector3f(4, 3, -3), EPSILON));
    }

    @Test
    void reusingDestinationShouldNotMutateWorldOrShadowPoses() {
        Matrix4f worldPose = new Matrix4f().rotateY(0.6f)
                .rotateX(0.3f).translate(2, 3, 4);
        Matrix4f shadowPose = new Matrix4f().rotateX((float) Math.PI / 2)
                .translate(-2, 5, 1).scale(0.25f);
        Matrix4f originalWorldPose = new Matrix4f(worldPose);
        Matrix4f originalShadowPose = new Matrix4f(shadowPose);
        Matrix4f destination = new Matrix4f();

        BaseModelInstance.composeModelViewMatrix(worldPose, destination);
        BaseModelInstance.composeModelViewMatrix(shadowPose, destination);
        BaseModelInstance.composeModelViewMatrix(worldPose, destination);

        assertTrue(worldPose.equals(originalWorldPose, EPSILON));
        assertTrue(shadowPose.equals(originalShadowPose, EPSILON));
        assertTrue(destination.equals(originalWorldPose, EPSILON));
    }
}
