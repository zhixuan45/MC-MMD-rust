package com.shiroha.mmdskin.render.pipeline.toon;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;

/** Small CPU metadata only; never copies animated vertex data or updates the model. */
public record ToonDrawSnapshot(Matrix4f projection, Matrix4f modelView, Vector3f lightDirection) {
    public static ToonDrawSnapshot capture(FloatBuffer projection, FloatBuffer modelView, Vector3f direction) {
        return new ToonDrawSnapshot(new Matrix4f(projection.duplicate().position(0)),
                new Matrix4f(modelView.duplicate().position(0)), new Vector3f(direction));
    }

    public void restore(FloatBuffer projection, FloatBuffer modelView, Vector3f direction) {
        this.projection.get(projection.clear());
        this.modelView.get(modelView.clear());
        direction.set(lightDirection);
    }

    public static BooleanSupplier unchangedSubmeshes(ByteBuffer buffer, int count) {
        byte[] bytes = new byte[count * 20];
        buffer.duplicate().position(0).get(bytes);
        return () -> {
            if (buffer.limit() < bytes.length) return false;
            for (int i = 0; i < bytes.length; i++) if (buffer.get(i) != bytes[i]) return false;
            return true;
        };
    }
}
