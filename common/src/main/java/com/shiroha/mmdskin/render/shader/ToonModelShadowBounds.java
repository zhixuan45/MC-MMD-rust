package com.shiroha.mmdskin.render.shader;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;

/** One conservative receiver volume per model, independent of its material partitions. */
public record ToonModelShadowBounds(float x, float y, float z, float radius) {
    public static final ToonModelShadowBounds EMPTY = new ToonModelShadowBounds(0, 0, 0, 0);

    public static ToonModelShadowBounds read(ByteBuffer positions, int count) {
        if (count <= 0 || (long) count * 12 > positions.limit()) return EMPTY;
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = 0; i < count; i++) {
            float px = positions.getFloat(i * 12), py = positions.getFloat(i * 12 + 4), pz = positions.getFloat(i * 12 + 8);
            if (!Float.isFinite(px) || !Float.isFinite(py) || !Float.isFinite(pz)) return EMPTY;
            minX = Math.min(minX, px); minY = Math.min(minY, py); minZ = Math.min(minZ, pz);
            maxX = Math.max(maxX, px); maxY = Math.max(maxY, py); maxZ = Math.max(maxZ, pz);
        }
        float dx = (maxX - minX) * .5f, dy = (maxY - minY) * .5f, dz = (maxZ - minZ) * .5f;
        float radius = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 1.05f;
        if (!Float.isFinite(radius) || radius <= 0) return EMPTY;
        return new ToonModelShadowBounds(minX + dx, minY + dy, minZ + dz, radius);
    }

    public Vector4f transform(Matrix4f modelView, Vector4f result) {
        if (radius <= 0) return result.zero();
        modelView.transform(result.set(x, y, z, 1));
        float sx = modelView.m00() * modelView.m00() + modelView.m01() * modelView.m01() + modelView.m02() * modelView.m02();
        float sy = modelView.m10() * modelView.m10() + modelView.m11() * modelView.m11() + modelView.m12() * modelView.m12();
        float sz = modelView.m20() * modelView.m20() + modelView.m21() * modelView.m21() + modelView.m22() * modelView.m22();
        result.w = radius * (float) Math.sqrt(Math.max(sx, Math.max(sy, sz)));
        return Float.isFinite(result.x) && Float.isFinite(result.y) && Float.isFinite(result.z)
                && Float.isFinite(result.w) ? result : result.zero();
    }
}
