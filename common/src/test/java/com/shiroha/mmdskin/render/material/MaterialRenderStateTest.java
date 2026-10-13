package com.shiroha.mmdskin.render.material;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MaterialRenderStateTest {
    @Test
    void defaultMaterialPreservesTextureAndPartialOpacity() {
        MaterialRenderState state = new MaterialRenderState();
        state.resolve(new ModelMaterial(), null, 0, 0.4f);
        assertArrayEquals(new float[] {1, 1, 1}, state.color);
        assertArrayEquals(new float[] {0, 0, 0}, state.specular);
        assertEquals(0.4f, state.alpha);
    }

    @Test
    void negativeAmbientCanMakeWhiteTextureBlackWithoutChangingOpacity() {
        ModelMaterial material = new ModelMaterial();
        material.ambient = new float[] {-10, -10, -10};
        MaterialRenderState state = new MaterialRenderState();
        state.resolve(material, null, 0, 0.35f);
        assertArrayEquals(new float[] {0, 0, 0}, state.color);
        assertEquals(0.35f, state.alpha);
    }

    @Test
    void morphUsesCorrectMaterialAndAppliesTextureOpacityOnce() {
        ByteBuffer morphs = identityMorphs(2);
        int base = 56 * Float.BYTES;
        morphs.putFloat(base + (28 + 8) * Float.BYTES, -2); // ambient R additive
        morphs.putFloat(base + 4 * Float.BYTES, 2); // specular R multiplicative
        morphs.putFloat(base + 7 * Float.BYTES, 2); // specular power multiplicative
        morphs.putFloat(base + 16 * Float.BYTES, 0.5f); // texture R multiplicative
        morphs.putFloat(base + 19 * Float.BYTES, 0.5f); // texture alpha multiplicative
        morphs.putFloat(base + (28 + 19) * Float.BYTES, 0.1f);
        ModelMaterial material = new ModelMaterial();
        material.specular = new float[] {0.2f, 0.3f, 0.4f};
        material.specularPower = 5;
        MaterialRenderState state = new MaterialRenderState();
        // effectiveAlpha=.4 already includes material alpha morph and global fade.
        state.resolve(material, morphs, 1, 0.4f);
        assertArrayEquals(new float[] {0, 1, 1}, state.color);
        assertArrayEquals(new float[] {0.4f, 0.3f, 0.4f}, state.specular);
        assertEquals(10, state.specularPower);
        assertEquals(0.24f, state.alpha, 1e-6f);
        state.resolve(material, morphs, 0, 0.4f);
        assertArrayEquals(new float[] {1, 1, 1}, state.color);
        assertEquals(0.4f, state.alpha);
    }

    @Test
    void missingMorphRecordFallsBackAndOpacityIsClamped() {
        MaterialRenderState state = new MaterialRenderState();
        state.resolve(new ModelMaterial(), identityMorphs(1), 1, 2);
        assertArrayEquals(new float[] {1, 1, 1}, state.color);
        assertEquals(1, state.alpha);
        state.resolve(new ModelMaterial(), null, 0, -1);
        assertEquals(0, state.alpha);
    }

    private static ByteBuffer identityMorphs(int count) {
        ByteBuffer morphs = ByteBuffer.allocate(count * 56 * Float.BYTES).order(ByteOrder.nativeOrder());
        for (int material = 0; material < count; material++) {
            for (int i = 0; i < 28; i++) morphs.putFloat((material * 56 + i) * Float.BYTES, 1);
        }
        return morphs;
    }
}
