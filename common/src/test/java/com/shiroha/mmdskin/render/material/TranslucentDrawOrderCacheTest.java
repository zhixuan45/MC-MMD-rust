package com.shiroha.mmdskin.render.material;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shiroha.mmdskin.texture.runtime.TextureAlphaCoverage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class TranslucentDrawOrderCacheTest {
    private static final int INDEX_BYTES = 12;

    private record Fixture(
            TranslucentDrawOrder order,
            TranslucentDrawOrder.Inputs inputs,
            ByteBuffer submeshes,
            ByteBuffer uvs,
            ModelMaterial[] materials,
            int[] batches,
            boolean[] includeOpaque) {}

    private Fixture fixture() throws Exception {
        ByteBuffer positions = ByteBuffer.allocate(3 * 12);
        ByteBuffer uvs = ByteBuffer.allocate(3 * 8);
        uvs.putFloat(0, .2f); uvs.putFloat(4, .2f);
        uvs.putFloat(8, .3f); uvs.putFloat(12, .2f);
        uvs.putFloat(16, .2f); uvs.putFloat(20, .3f);
        ByteBuffer submeshes = ByteBuffer.allocate(20);
        submeshes.putInt(0, 0);
        submeshes.putInt(4, 0);
        submeshes.putInt(8, 3);
        submeshes.putFloat(12, 1.0f);
        submeshes.put(16, (byte) 1);
        submeshes.put(17, (byte) 0);

        ModelMaterial material = new ModelMaterial();
        material.hasAlpha = true;
        material.alphaCoverage = coverage();
        ModelMaterial[] materials = {material};
        var inputs = new TranslucentDrawOrder.Inputs(
                positions, 11, 3, nullSafeMatrix(), null, 1, false, uvs, 22, 1);
        TranslucentDrawOrder order = new TranslucentDrawOrder();
        set(order, "sourceBuffer", 7);
        set(order, "sourceBytes", INDEX_BYTES);
        set(order, "sourceIndexSize", 4);
        set(order, "indexRevision", 0L);
        set(order, "sourceIndices", new int[] {0, 1, 2});
        int[] batches = {0};
        boolean[] includeOpaque = {false};
        build(order, inputs, submeshes, materials, batches, includeOpaque, true, uvs);
        return new Fixture(order, inputs, submeshes, uvs, materials, batches, includeOpaque);
    }

    @Test
    void stableUvCacheSurvivesGeometryRevisionChanges() throws Exception {
        Fixture f = fixture();
        var nextGeometry = inputs(f, f.uvs(), 2, 1);
        assertTrue(matches(f, nextGeometry, f.submeshes(), f.materials(), f.batches(), f.includeOpaque()));
        assertTrue(f.order().candidateBuildCount() == 1);
    }

    @Test
    void uvRevisionAndUvBufferIdentityInvalidateCandidates() throws Exception {
        Fixture f = fixture();
        assertFalse(matches(f, inputs(f, f.uvs(), 2, 2), f.submeshes(), f.materials(), f.batches(), f.includeOpaque()));

        ByteBuffer replacement = ByteBuffer.allocate(3 * 8);
        replacement.putFloat(0, .2f); replacement.putFloat(4, .2f);
        replacement.putFloat(8, .3f); replacement.putFloat(12, .2f);
        replacement.putFloat(16, .2f); replacement.putFloat(20, .3f);
        assertFalse(matches(f, inputs(f, replacement, 2, 1), f.submeshes(), f.materials(), f.batches(), f.includeOpaque()));
    }

    @Test
    void alphaEligibilityAndSubmeshLayoutInvalidateCandidates() throws Exception {
        Fixture f = fixture();
        assertFalse(matches(f, f.inputs(), f.submeshes(), f.materials(), f.batches(), new boolean[] {true}));

        f.submeshes().put(16, (byte) 0);
        assertFalse(matches(f, f.inputs(), f.submeshes(), f.materials(), f.batches(), f.includeOpaque()));
        f.submeshes().put(16, (byte) 1);
        f.submeshes().putInt(8, 6);
        assertFalse(matches(f, f.inputs(), f.submeshes(), f.materials(), f.batches(), f.includeOpaque()));
    }

    @Test
    void alphaCoverageIdentityInvalidatesCandidates() throws Exception {
        Fixture f = fixture();
        f.materials()[0].alphaCoverage = coverage();
        assertFalse(matches(f, f.inputs(), f.submeshes(), f.materials(), f.batches(), f.includeOpaque()));
    }

    private static TranslucentDrawOrder.Inputs inputs(Fixture f, ByteBuffer uvs, long geometryRevision, long uvRevision) {
        return new TranslucentDrawOrder.Inputs(
                f.inputs().positions(), 11, 3, nullSafeMatrix(), null,
                geometryRevision, false, uvs, 22, uvRevision);
    }

    private static boolean matches(
            Fixture f, TranslucentDrawOrder.Inputs inputs, ByteBuffer submeshes,
            ModelMaterial[] materials, int[] batches, boolean[] includeOpaque) throws Exception {
        Method method = TranslucentDrawOrder.class.getDeclaredMethod(
                "candidateCacheMatches", TranslucentDrawOrder.Inputs.class, ByteBuffer.class,
                int.class, int.class, int.class, boolean.class, int[].class, boolean[].class,
                ModelMaterial[].class);
        method.setAccessible(true);
        return (boolean) method.invoke(f.order(), inputs, submeshes, 1, INDEX_BYTES, 4,
                true, batches, includeOpaque, materials);
    }

    private static void build(
            TranslucentDrawOrder order, TranslucentDrawOrder.Inputs inputs, ByteBuffer submeshes,
            ModelMaterial[] materials, int[] batches, boolean[] includeOpaque,
            boolean coverageSafe, ByteBuffer uvs) throws Exception {
        Method method = TranslucentDrawOrder.class.getDeclaredMethod(
                "buildCandidates", TranslucentDrawOrder.Inputs.class, ByteBuffer.class, int.class,
                ModelMaterial[].class, int[].class, boolean[].class, boolean.class, ByteBuffer.class,
                int.class);
        method.setAccessible(true);
        method.invoke(order, inputs, submeshes, 1, materials, batches, includeOpaque,
                coverageSafe, uvs, INDEX_BYTES);
    }

    private static TextureAlphaCoverage coverage() {
        ByteBuffer pixels = ByteBuffer.allocate(8 * 8 * 4);
        for (int i = 0; i < 64; i++) pixels.put(i * 4 + 3, (byte) 255);
        return TextureAlphaCoverage.fromRgba(pixels, 8, 8);
    }

    private static java.nio.FloatBuffer nullSafeMatrix() {
        return java.nio.ByteBuffer.allocate(16 * Float.BYTES).asFloatBuffer();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = TranslucentDrawOrder.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
