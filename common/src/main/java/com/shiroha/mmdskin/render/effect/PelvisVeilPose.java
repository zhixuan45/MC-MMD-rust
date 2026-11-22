package com.shiroha.mmdskin.render.effect;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * 以实时腿根、骨盆与躯干骨骼在模型空间构造紧贴骨盆的椭球包络，
 * 并基于视图空间骨盆朝向计算纯视角触发的易走光曝光度。
 */
final class PelvisVeilPose {
    /** 相机视线与骨盆尾侧（-up）夹角小于等于 70° 时开始淡入。 */
    static final float EXPOSURE_START_COS = (float) Math.cos(Math.toRadians(70.0));
    /** 相机视线与骨盆尾侧（-up）夹角小于等于 35° 时达到满遮挡强度。 */
    static final float EXPOSURE_FULL_COS = (float) Math.cos(Math.toRadians(35.0));

    private final int leftHip, rightHip, pelvis, neck;

    PelvisVeilPose(String[] names) {
        leftHip = find(names,
                "左足", "左足d", "左足根",
                "leftupleg", "leftupperleg", "mixamorig:leftupleg", "j_bip_l_upperleg");
        rightHip = find(names,
                "右足", "右足d", "右足根",
                "rightupleg", "rightupperleg", "mixamorig:rightupleg", "j_bip_r_upperleg");
        pelvis = find(names,
                "下半身", "腰", "グルーブ", "センター",
                "hips", "pelvis", "mixamorig:hips", "j_bip_c_hips");
        neck = find(names,
                "首", "上半身2", "上半身",
                "neck", "mixamorig:neck", "j_bip_c_neck", "spine2", "j_bip_c_chest");
    }

    Matrix4f resolve(ByteBuffer positions, int count, PelvisVeilMode mode) {
        Vector3f left = position(positions, count, leftHip);
        Vector3f right = position(positions, count, rightHip);
        Vector3f center = left != null && right != null
                ? new Vector3f(left).add(right).mul(0.5f)
                : position(positions, count, pelvis);
        if (center == null) center = new Vector3f(0.0f, 10.0f, 0.0f);

        Vector3f top = position(positions, count, neck);
        Vector3f up = top != null ? new Vector3f(top).sub(center) : new Vector3f(0.0f, 1.0f, 0.0f);
        float torso = up.length();
        if (torso < 0.001f) {
            up.set(0.0f, 1.0f, 0.0f);
        } else {
            up.div(torso);
        }

        Vector3f across = left != null && right != null
                ? new Vector3f(right).sub(left)
                : new Vector3f(1.0f, 0.0f, 0.0f);
        float hipSpan = (left != null && right != null) ? across.length() : 0.0f;
        across.sub(new Vector3f(up).mul(across.dot(up)));
        if (across.lengthSquared() < 0.0001f) {
            across.set(Math.abs(up.x) < 0.9f ? 1.0f : 0.0f, Math.abs(up.x) < 0.9f ? 0.0f : 1.0f, 0.0f);
            across.sub(new Vector3f(up).mul(across.dot(up)));
        }
        across.normalize();
        Vector3f forward = new Vector3f(across).cross(up).normalize();

        // 以左右腿根间距为主尺度，躯干高度做上下限钳制，紧贴骨盆而不膨胀成大球。
        float baseSize;
        if (hipSpan > 0.001f) {
            baseSize = hipSpan * 1.18f;
            if (top != null && torso > 0.001f) {
                baseSize = clamp(baseSize, torso * 0.24f, torso * 0.38f);
            }
        } else if (top != null && torso > 0.001f) {
            baseSize = torso * 0.30f;
        } else {
            baseSize = 2.1f;
        }
        float size = Math.max(baseSize, 0.1f);

        boolean windStyle = mode == PelvisVeilMode.ELYTRA_WIND;
        float radiusAcross = size * (windStyle ? 1.12f : 1.16f);
        float radiusUp = size * (windStyle ? 1.04f : 0.88f);
        float radiusForward = size * (windStyle ? 0.94f : 0.96f);
        float centerDrop = size * (windStyle ? 0.24f : 0.20f);

        // 中心略低于腿根，贴合骨盆下缘与大腿上段，并沿鞘翅气流方向略微后延。
        center.sub(new Vector3f(up).mul(centerDrop));
        return new Matrix4f()
                .setColumn(0, new Vector4f(across.mul(radiusAcross), 0.0f))
                .setColumn(1, new Vector4f(up.mul(radiusUp), 0.0f))
                .setColumn(2, new Vector4f(forward.mul(radiusForward), 0.0f))
                .setColumn(3, new Vector4f(center, 1.0f));
    }

    /**
     * 计算相机相对骨盆尾侧（-up，即脚底/裙底开口方向）的视角曝光度 [0, 1]。
     * 正面视角与正上方俯视返回 0；后方及斜后下方视角平滑过渡到 1。
     */
    static float computeViewExposure(Matrix4f viewSpaceVeil) {
        if (viewSpaceVeil == null) return 0.0f;
        Vector3f center = new Vector3f(viewSpaceVeil.m30(), viewSpaceVeil.m31(), viewSpaceVeil.m32());
        if (!center.isFinite()) return 0.0f;
        float distSq = center.lengthSquared();
        if (distSq < 1e-6f) return 1.0f;
        Vector3f toCamera = center.negate().div((float) Math.sqrt(distSq));

        Vector3f upAxis = new Vector3f(viewSpaceVeil.m10(), viewSpaceVeil.m11(), viewSpaceVeil.m12());
        if (!upAxis.isFinite()) return 0.0f;
        float upLenSq = upAxis.lengthSquared();
        if (upLenSq < 1e-6f) return 0.0f;
        upAxis.div((float) Math.sqrt(upLenSq));

        float tailAlignment = -toCamera.dot(upAxis);
        float t = clamp((tailAlignment - EXPOSURE_START_COS) / (EXPOSURE_FULL_COS - EXPOSURE_START_COS), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static Vector3f position(ByteBuffer positions, int count, int index) {
        if (positions == null || index < 0 || index >= count || index * 12L + 12 > positions.capacity()) return null;
        int offset = index * 12;
        Vector3f value = new Vector3f(
                positions.getFloat(offset),
                positions.getFloat(offset + 4),
                positions.getFloat(offset + 8));
        return value.isFinite() ? value : null;
    }

    private static int find(String[] names, String... aliases) {
        if (names == null || names.length == 0) return -1;
        for (String alias : aliases) {
            String normalizedAlias = normalizeName(alias);
            for (int i = 0; i < names.length; i++) {
                if (names[i] != null && normalizedAlias.equals(normalizeName(names[i]))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
