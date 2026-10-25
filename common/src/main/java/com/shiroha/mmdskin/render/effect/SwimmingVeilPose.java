package com.shiroha.mmdskin.render.effect;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.Locale;

/** 以实时腿根、躯干骨骼构造水纹椭球，坐标保持在模型空间。 */
final class SwimmingVeilPose {
    private final int leftHip, rightHip, pelvis, neck;

    SwimmingVeilPose(String[] names) {
        leftHip = find(names, "左足", "左足D", "leftupleg", "leftupperleg", "mixamorig:leftupleg");
        rightHip = find(names, "右足", "右足D", "rightupleg", "rightupperleg", "mixamorig:rightupleg");
        pelvis = find(names, "下半身", "腰", "hips", "pelvis", "mixamorig:hips");
        neck = find(names, "首", "neck", "mixamorig:neck", "上半身2", "spine2");
    }

    Matrix4f resolve(ByteBuffer positions, int count) {
        Vector3f left = position(positions, count, leftHip);
        Vector3f right = position(positions, count, rightHip);
        Vector3f center = left != null && right != null ? new Vector3f(left).add(right).mul(0.5f)
                : position(positions, count, pelvis);
        if (center == null) center = new Vector3f(0, 10, 0);
        Vector3f top = position(positions, count, neck);
        Vector3f up = top != null ? new Vector3f(top).sub(center) : new Vector3f(0, 1, 0);
        float torso = up.length();
        if (torso < 0.001f) up.set(0, 1, 0); else up.div(torso);
        Vector3f across = left != null && right != null ? new Vector3f(right).sub(left) : new Vector3f(1, 0, 0);
        float hipSpan = across.length();
        across.sub(new Vector3f(up).mul(across.dot(up)));
        if (across.lengthSquared() < 0.0001f) {
            across.set(Math.abs(up.x) < 0.9f ? 1 : 0, Math.abs(up.x) < 0.9f ? 0 : 1, 0);
            across.sub(new Vector3f(up).mul(across.dot(up)));
        }
        across.normalize();
        Vector3f forward = new Vector3f(across).cross(up).normalize();
        float size = Math.max(hipSpan * 1.35f, top != null && torso > 0.001f ? torso * 0.52f : 3.5f);
        // 中心略低于腿根，使下缘包住裆部与大腿根，闭合曲面也覆盖仰视。
        center.sub(new Vector3f(up).mul(size * 0.18f));
        return new Matrix4f().setColumn(0, new org.joml.Vector4f(across.mul(size * 1.12f), 0))
                .setColumn(1, new org.joml.Vector4f(up.mul(size), 0))
                .setColumn(2, new org.joml.Vector4f(forward.mul(size * 0.92f), 0))
                .setColumn(3, new org.joml.Vector4f(center, 1));
    }

    private static Vector3f position(ByteBuffer positions, int count, int index) {
        if (index < 0 || index >= count || index * 12L + 12 > positions.capacity()) return null;
        int offset = index * 12;
        Vector3f value = new Vector3f(positions.getFloat(offset), positions.getFloat(offset + 4), positions.getFloat(offset + 8));
        return value.isFinite() ? value : null;
    }

    private static int find(String[] names, String... aliases) {
        for (String alias : aliases) {
            for (int i = 0; i < names.length; i++) {
                if (names[i] != null && alias.equals(names[i].toLowerCase(Locale.ROOT).replace("_", "").replace(" ", ""))) return i;
            }
        }
        return -1;
    }
}
