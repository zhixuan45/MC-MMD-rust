package com.shiroha.mmdskin.render.effect;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class SwimmingVeilPoseTest {
    @Test void followsHipTranslationAndHorizontalSwimmingPose() {
        var layout = new SwimmingVeilPose(new String[]{"左足", "右足", "首"});
        ByteBuffer positions = positions(-1, 10, 0, 1, 10, 0, 0, 17, 0);
        Matrix4f standing = layout.resolve(positions, 3);
        Matrix4f swim = new Matrix4f().translation(4, 8, -3).rotateX((float) Math.PI / 2);
        ByteBuffer moved = ByteBuffer.allocate(36).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 3; i++) {
            Vector3f p = swim.transformPosition(new Vector3f(positions.getFloat(i * 12),
                    positions.getFloat(i * 12 + 4), positions.getFloat(i * 12 + 8)));
            moved.putFloat(p.x).putFloat(p.y).putFloat(p.z);
        }
        assertTrue(new Matrix4f(swim).mul(standing).equals(layout.resolve(moved, 3), 0.00001f));
    }

    @Test void enclosesBothLegRootsAndExtendsBelowThem() {
        var layout = new SwimmingVeilPose(new String[]{"LeftUpperLeg", "RightUpperLeg", "Neck"});
        Matrix4f veil = layout.resolve(positions(-1.2f, 10, 0, 1.2f, 10, 0, 0, 17, 0), 3);
        Matrix4f inverse = new Matrix4f(veil).invert();
        for (Vector3f p : new Vector3f[]{new Vector3f(-1.2f, 10, 0), new Vector3f(1.2f, 10, 0), new Vector3f(0, 7.5f, 0)}) {
            assertTrue(inverse.transformPosition(p).lengthSquared() < 1.0f);
        }
        assertTrue(veil.determinant() > 0, "闭合网格的正面绕序保持一致");
    }

    @Test void missingOrInvalidBonesHaveFiniteFallback() {
        var layout = new SwimmingVeilPose(new String[]{"hips", "neck"});
        Matrix4f pose = layout.resolve(positions(Float.NaN, 1, 0, 0, Float.POSITIVE_INFINITY, 0), 2);
        assertTrue(pose.isFinite());
        assertTrue(pose.determinant() > 0);
        assertTrue(new SwimmingVeilPose(new String[0]).resolve(positions(), 0).isFinite());
    }

    private static ByteBuffer positions(float... values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.nativeOrder());
        for (float value : values) bytes.putFloat(value);
        return bytes;
    }
}
