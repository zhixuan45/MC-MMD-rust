package com.shiroha.mmdskin.render.material;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class DrawMaterialCacheTest {
    @Test
    void interleavedTransparentRunsReuseEvaluationWithoutMixingMaterials() {
        ModelMaterial first = new ModelMaterial(), second = new ModelMaterial();
        second.ambient = new float[] {0, 0, 0};
        int[] evaluations = {0};
        var cache = new DrawMaterialCache(new ModelMaterial[] {first, second}, null,
                (id, alpha) -> { evaluations[0]++; return alpha; });
        var state = cache.resolve(0, .4f);
        assertArrayEquals(new float[] {.5f, .5f, .5f}, cache.resolve(1, .8f).color);
        for (int i = 0; i < 1000; i++) {
            assertSame(state, cache.resolve(0, .4f));
            assertEquals(.8f, cache.resolve(1, .8f).alpha);
        }
        assertEquals(2, evaluations[0]);
        assertEquals(.4f, state.alpha);
    }

    @Test
    void sameMaterialWithDifferentBaseAlphaReevaluates() {
        int[] evaluations = {0};
        var cache = new DrawMaterialCache(new ModelMaterial[] {new ModelMaterial()}, null,
                (id, alpha) -> { evaluations[0]++; return alpha * .5f; });
        assertEquals(.2f, cache.resolve(0, .4f).alpha);
        assertEquals(.4f, cache.resolve(0, .8f).alpha);
        assertEquals(.2f, cache.resolve(0, .4f).alpha);
        assertEquals(3, evaluations[0]);
    }

    @Test
    void nextDrawReadsChangedMorphAndGlobalAlpha() {
        var materials = new ModelMaterial[] {new ModelMaterial()};
        var morphs = ByteBuffer.allocate(56 * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 28; i++) morphs.putFloat(i * 4, 1);
        float[] globalAlpha = {1};
        SubMeshDrawHelper.AlphaResolver resolver = (id, alpha) -> alpha * globalAlpha[0];
        assertEquals(.8f, new DrawMaterialCache(materials, morphs, resolver).resolve(0, .8f).alpha);
        globalAlpha[0] = .5f;
        morphs.putFloat(19 * 4, .5f);
        morphs.putFloat(16 * 4, .25f);
        var next = new DrawMaterialCache(materials, morphs, resolver).resolve(0, .8f);
        assertEquals(.2f, next.alpha);
        assertEquals(.25f, next.color[0]);
    }

    @Test
    void textureResolverRunsOncePerMaterialWithinOneDraw() {
        int[] resolves = {0};
        var cache = new DrawMaterialCache(new ModelMaterial[] {new ModelMaterial()}, null,
                (id, alpha) -> alpha, id -> { resolves[0]++; return 100 + id; });
        assertEquals(100, cache.texture(0));
        assertEquals(100, cache.texture(0));
        assertEquals(1, resolves[0]);
    }
}
