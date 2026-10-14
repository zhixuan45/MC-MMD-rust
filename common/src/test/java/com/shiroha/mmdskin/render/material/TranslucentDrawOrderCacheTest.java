package com.shiroha.mmdskin.render.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        return fixture(false);
    }

    private Fixture fixture(boolean translucent) throws Exception {
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
        material.alphaCoverage = coverage(translucent);
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
    void staticGpuUvsReuseCoverageDespiteAllocatedComputeBufferAndAnimationUpdates() throws Exception {
        Fixture f = fixture(true);
        var first = backendInputs(f, true, false, 1);
        assertSame(f.uvs(), first.uvs());
        assertEquals(22, first.uvBuffer());
        assertEquals(0, first.uvRevision());
        build(f.order(), first, f.submeshes(), f.materials(), f.batches(),
                f.includeOpaque(), true, f.uvs());
        assertEquals(1, f.order().candidateTriangleCount());
        int builds = f.order().candidateBuildCount();
        for (long revision = 2; revision <= 120; revision++) {
            var next = backendInputs(f, true, false, revision);
            assertEquals(revision, next.geometryRevision());
            assertTrue(matches(f, next, f.submeshes(), f.materials(), f.batches(), f.includeOpaque()));
        }
        assertEquals(builds, f.order().candidateBuildCount());
    }

    @Test
    void staticCpuUvsReuseCoverageAcrossAnimationUpdates() throws Exception {
        Fixture f = fixture(true);
        var first = backendInputs(f, false, false, 1);
        build(f.order(), first, f.submeshes(), f.materials(), f.batches(),
                f.includeOpaque(), true, f.uvs());
        assertEquals(1, f.order().candidateTriangleCount());
        var next = backendInputs(f, false, false, 2);
        assertSame(f.inputs().positions(), next.positions());
        assertEquals(2, next.geometryRevision());
        assertEquals(0, next.uvRevision());
        assertTrue(matches(f, next, f.submeshes(), f.materials(), f.batches(), f.includeOpaque()));
    }

    @Test
    void animatedGpuUvsUseComputeOutputAndInvalidateCoverage() throws Exception {
        Fixture f = fixture(true);
        var first = backendInputs(f, true, true, 1);
        assertNull(first.uvs());
        assertEquals(33, first.uvBuffer());
        build(f.order(), first, f.submeshes(), f.materials(), f.batches(),
                f.includeOpaque(), true, f.uvs());
        assertEquals(1, f.order().candidateTriangleCount());
        var next = backendInputs(f, true, true, 2);
        assertEquals(2, next.uvRevision());
        assertFalse(matches(f, next, f.submeshes(), f.materials(), f.batches(), f.includeOpaque()));
    }

    @Test
    void animatedCpuUvsInvalidateCoverage() throws Exception {
        Fixture f = fixture(true);
        var first = backendInputs(f, false, true, 1);
        build(f.order(), first, f.submeshes(), f.materials(), f.batches(),
                f.includeOpaque(), true, f.uvs());
        assertEquals(1, f.order().candidateTriangleCount());
        var next = backendInputs(f, false, true, 2);
        assertEquals(2, next.uvRevision());
        assertFalse(matches(f, next, f.submeshes(), f.materials(), f.batches(), f.includeOpaque()));
    }

    private static TranslucentDrawOrder.Inputs backendInputs(
            Fixture f, boolean gpu, boolean hasUvMorph, long revision) {
        return gpu
                ? TranslucentDrawOrder.Inputs.gpu(11, 3, nullSafeMatrix(), null, revision,
                        false, f.uvs(), 22, 33, hasUvMorph)
                : TranslucentDrawOrder.Inputs.cpu(f.inputs().positions(), 11, 3,
                        nullSafeMatrix(), null, revision, false, f.uvs(), 22, hasUvMorph);
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
        return coverage(false);
    }

    private static TextureAlphaCoverage coverage(boolean translucent) {
        ByteBuffer pixels = ByteBuffer.allocate(8 * 8 * 4);
        for (int i = 0; i < 64; i++) pixels.put(i * 4 + 3, (byte) (translucent ? 128 : 255));
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
