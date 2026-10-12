package com.shiroha.mmdskin.render.material;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Locale;

/** 按眼骨权重划定眼瞳，保留黑瞳与高光，不从贴图颜色猜范围。 */
public final class EyeBoneMask {
    private EyeBoneMask() {}

    public static boolean isEyeBone(String name) {
        if (name == null) return false;
        String label = name.toLowerCase(Locale.ROOT);
        for (String excluded : new String[]{"brow", "lash", "lid", "眉", "睫", "瞼", "縁", "まぶた"}) {
            if (label.contains(excluded)) return false;
        }
        // 返回骨与眼部辅助骨仍属于眼球，头骨和眼睑不能参与。
        String compact = label.replaceAll("[\\s._-]", "");
        return label.startsWith("左目") || label.startsWith("右目") || label.startsWith("両目")
                || label.startsWith("左眼") || label.startsWith("右眼")
                || compact.matches("(?:left|right|l|r)?(?:eye|eyeball)(?:left|right|l|r)?(?:return|helper|end|ctrl|[0-9]+)?")
                || compact.matches("jadj[lr]faceeye");
    }

    public static float[] build(String[] names, IntBuffer indices, FloatBuffer weights, int vertexCount) {
        if (vertexCount < 0 || indices.remaining() < (long) vertexCount * 4
                || weights.remaining() < (long) vertexCount * 4) {
            throw new IllegalArgumentException("眼骨权重数据不完整");
        }
        boolean[] eyes = new boolean[names.length];
        for (int i = 0; i < names.length; i++) eyes[i] = isEyeBone(names[i]);
        float[] mask = new float[vertexCount];
        int indexStart = indices.position(), weightStart = weights.position();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            float eye = 0.0f;
            for (int slot = 0; slot < 4; slot++) {
                int offset = vertex * 4 + slot;
                int bone = indices.get(indexStart + offset);
                float weight = weights.get(weightStart + offset);
                if (bone >= 0 && bone < eyes.length && eyes[bone] && Float.isFinite(weight) && weight > 0) {
                    eye += Math.min(weight, 1.0f);
                }
            }
            mask[vertex] = Math.min(eye, 1.0f);
        }
        return mask;
    }
}
