package com.shiroha.mmdskin.render.effect;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PelvisVeilPoseTest {
    @Test void followsHipTranslationAndHorizontalSwimmingPose() {
        var layout = new PelvisVeilPose(new String[]{"左足", "右足", "首"});
        ByteBuffer positions = positions(-1, 10, 0, 1, 10, 0, 0, 17, 0);
        Matrix4f standing = layout.resolve(positions, 3, PelvisVeilMode.SWIMMING_WATER);
        Matrix4f swim = new Matrix4f().translation(4, 8, -3).rotateX((float) Math.PI / 2);
        ByteBuffer moved = ByteBuffer.allocate(36).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 3; i++) {
            Vector3f p = swim.transformPosition(new Vector3f(
                    positions.getFloat(i * 12),
                    positions.getFloat(i * 12 + 4),
                    positions.getFloat(i * 12 + 8)));
            moved.putFloat(p.x).putFloat(p.y).putFloat(p.z);
        }
        assertTrue(new Matrix4f(swim).mul(standing).equals(layout.resolve(moved, 3, PelvisVeilMode.SWIMMING_WATER), 0.0001f));
    }

    @Test void tightEnvelopeCoversLegRootsWithoutOversizingAndSupportsVroidBones() {
        var vrmLayout = new PelvisVeilPose(new String[]{"J_Bip_L_UpperLeg", "J_Bip_R_UpperLeg", "J_Bip_C_Neck"});
        ByteBuffer positions = positions(-1.0f, 10.0f, 0.0f, 1.0f, 10.0f, 0.0f, 0.0f, 17.0f, 0.0f);
        Matrix4f waterVeil = vrmLayout.resolve(positions, 3, PelvisVeilMode.SWIMMING_WATER);
        Matrix4f windVeil = vrmLayout.resolve(positions, 3, PelvisVeilMode.ELYTRA_WIND);

        Matrix4f inverse = new Matrix4f(waterVeil).invert();
        for (Vector3f p : new Vector3f[]{
                new Vector3f(-1.0f, 10.0f, 0.0f),
                new Vector3f(1.0f, 10.0f, 0.0f),
                new Vector3f(0.0f, 8.6f, 0.0f)}) {
            assertTrue(inverse.transformPosition(p).lengthSquared() < 1.0f, "骨盆与腿根应位于包络内部: " + p);
        }
        // 收紧后的横向半径应贴合骨盆，避免过度膨胀成覆盖半个身高的球体。
        float acrossRadius = new Vector3f(waterVeil.m00(), waterVeil.m01(), waterVeil.m02()).length();
        assertTrue(acrossRadius < 3.2f && acrossRadius > 2.0f, "横向半径异常: " + acrossRadius);
        assertTrue(waterVeil.determinant() > 0, "水纹包络行列式应保持正手系");
        assertTrue(windVeil.determinant() > 0, "气流包络行列式应保持正手系");

        // 鞘翅气流模式沿躯干轴略长于游泳水纹，形成尾迹动势。
        float waterUpRadius = new Vector3f(waterVeil.m10(), waterVeil.m11(), waterVeil.m12()).length();
        float windUpRadius = new Vector3f(windVeil.m10(), windVeil.m11(), windVeil.m12()).length();
        assertTrue(windUpRadius > waterUpRadius);
    }

    @Test void missingOrInvalidBonesHaveFiniteFallback() {
        var layout = new PelvisVeilPose(new String[]{"hips", "neck"});
        Matrix4f pose = layout.resolve(positions(Float.NaN, 1, 0, 0, Float.POSITIVE_INFINITY, 0), 2, PelvisVeilMode.SWIMMING_WATER);
        assertTrue(pose.isFinite());
        assertTrue(pose.determinant() > 0);
        assertTrue(new PelvisVeilPose(new String[0]).resolve(positions(), 0, PelvisVeilMode.ELYTRA_WIND).isFinite());
    }

    @Test void viewExposureTriggersOnlyFromRearAndUnderAngles() {
        // 相机在原点，角色在视图空间 (0, 0, -4)，指向相机向量为 (0, 0, +1)。
        // 1) 背后跟拍：头朝前 (-Z)，骨盆 up=(0, 0, -1)，尾侧 -up=(0, 0, +1) 正对相机 -> 夹角 0° -> 1.0。
        Matrix4f rearFollow = viewMatrixWithUp(new Vector3f(0, 0, -1), new Vector3f(0, 0, -4));
        assertEquals(1.0f, PelvisVeilPose.computeViewExposure(rearFollow), 1e-4f);

        // 2) 正面视角 (F5 看脸)：头朝向相机 (+Z)，up=(0, 0, +1) -> 夹角 180° -> 0.0。
        Matrix4f frontView = viewMatrixWithUp(new Vector3f(0, 0, 1), new Vector3f(0, 0, -4));
        assertEquals(0.0f, PelvisVeilPose.computeViewExposure(frontView), 1e-4f);

        // 3) 正上方俯视 / 正侧面：up 垂直于视线 (0, 1, 0) -> 夹角 90° -> 0.0。
        Matrix4f sideOrTopView = viewMatrixWithUp(new Vector3f(0, 1, 0), new Vector3f(0, 0, -4));
        assertEquals(0.0f, PelvisVeilPose.computeViewExposure(sideOrTopView), 1e-4f);

        // 4) 过渡窗口 (70° -> 35°)：单调递增且位于 (0, 1) 内。
        float at60Deg = PelvisVeilPose.computeViewExposure(viewMatrixAtTailAngleDegrees(60.0));
        float at50Deg = PelvisVeilPose.computeViewExposure(viewMatrixAtTailAngleDegrees(50.0));
        float at40Deg = PelvisVeilPose.computeViewExposure(viewMatrixAtTailAngleDegrees(40.0));
        assertTrue(at60Deg > 0.0f && at60Deg < at50Deg && at50Deg < at40Deg && at40Deg < 1.0f,
                "过渡窗口应平滑递增: " + at60Deg + ", " + at50Deg + ", " + at40Deg);
    }

    @Test void stateSmoothlyFadesInAndRetainsStyleWhileFadingOut() {
        PelvisVeilState state = new PelvisVeilState();
        assertFalse(state.shouldEvaluate());

        long t = 1_000_000_000L;
        state.updateTarget(PelvisVeilMode.SWIMMING_WATER, 1.0f, 0.2f, 0.7f, 0.9f);
        assertTrue(state.shouldEvaluate());

        float first = state.advance(1.0f, t);
        assertTrue(first > 0.0f && first < 0.3f, "首帧应平滑起步: " + first);

        // 持续 1 秒处于可触发视角后趋近 1.0。
        float opacity = first;
        for (int i = 0; i < 60; i++) {
            t += 16_666_667L;
            opacity = state.advance(1.0f, t);
        }
        assertTrue(opacity > 0.95f, "淡入后应接近满强度: " + opacity);

        // 退出游泳状态：在完全淡出前仍保留 SWIMMING_WATER 风格渲染，避免瞬间闪断。
        state.updateTarget(PelvisVeilMode.NONE, 2.0f, 0.2f, 0.7f, 0.9f);
        t += 50_000_000L;
        float fading = state.advance(1.0f, t);
        assertTrue(fading < opacity && fading > 0.1f);
        assertEquals(PelvisVeilMode.SWIMMING_WATER, state.renderMode());
        assertTrue(state.shouldEvaluate());

        // 充分淡出后自动归零休眠。
        for (int i = 0; i < 120; i++) {
            t += 33_333_333L;
            fading = state.advance(0.0f, t);
        }
        assertEquals(0.0f, fading);
        assertEquals(PelvisVeilMode.NONE, state.renderMode());
        assertFalse(state.shouldEvaluate());
    }

    @Test void shaderResourcesSatisfyTransparencyPipelineContract() throws IOException {
        String vert = readResource("/assets/mmdskin/shader/pelvis_veil.vert.glsl");
        String frag = readResource("/assets/mmdskin/shader/pelvis_veil.frag.glsl");

        assertTrue(vert.contains("uniform mat4 ModelViewMat;"));
        assertTrue(vert.contains("uniform mat4 ProjMat;"));
        assertTrue(vert.contains("uniform int VeilStyle;"));

        // TransparencyProgram 要求单个 location=0 输出、标准 void main() 且不写 gl_FragDepth。
        assertTrue(frag.contains("layout(location = 0) out vec4 fragColor;"));
        assertTrue(frag.contains("void main() {"));
        assertFalse(frag.contains("gl_FragDepth"));
        assertTrue(frag.contains("uniform float Opacity;"));
        assertTrue(frag.contains("uniform int VeilStyle;"));
        assertTrue(frag.contains("uniform float LightIntensity;"));
        assertTrue(frag.contains("uniform vec3 TintColor;"));
        assertTrue(frag.contains("uniform int OutputEncoding;"));
    }

    @Test void colorSlotResolverPrefersVerifiedShaderSlotAndSkipsDisabledBuffers() {
        assertEquals(2, PelvisVeilRenderer.resolveColorSlot(2, new int[]{0x8CE0, 0x8CE1, 0x8CE2}));
        assertEquals(0, PelvisVeilRenderer.resolveColorSlot(-1, new int[]{0x8CE0, 0x8CE1}));
        assertEquals(0, PelvisVeilRenderer.resolveColorSlot(1, new int[]{0x8CE0, 0}));
        assertEquals(-1, PelvisVeilRenderer.resolveColorSlot(0, new int[]{0}));
        assertEquals(-1, PelvisVeilRenderer.resolveColorSlot(0, new int[0]));
    }

    private static Matrix4f viewMatrixAtTailAngleDegrees(double degrees) {
        double rad = Math.toRadians(degrees);
        // toCamera = (0, 0, +1)，希望 dot(toCamera, -up) = cos(rad)，故 up.z = -cos(rad), up.y = sin(rad)。
        return viewMatrixWithUp(new Vector3f(0.0f, (float) Math.sin(rad), (float) -Math.cos(rad)), new Vector3f(0, 0, -4));
    }

    private static Matrix4f viewMatrixWithUp(Vector3f up, Vector3f center) {
        Vector3f u = new Vector3f(up).normalize();
        Vector3f right = new Vector3f(1, 0, 0);
        Vector3f forward = new Vector3f(right).cross(u).normalize();
        return new Matrix4f()
                .setColumn(0, new org.joml.Vector4f(right, 0))
                .setColumn(1, new org.joml.Vector4f(u, 0))
                .setColumn(2, new org.joml.Vector4f(forward, 0))
                .setColumn(3, new org.joml.Vector4f(center, 1));
    }

    private static ByteBuffer positions(float... values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.nativeOrder());
        for (float value : values) bytes.putFloat(value);
        return bytes;
    }

    private static String readResource(String path) throws IOException {
        try (var stream = PelvisVeilPoseTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "缺少着色器资源: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
