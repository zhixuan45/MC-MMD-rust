package com.shiroha.mmdskin.render.pipeline.toon;

import com.shiroha.mmdskin.render.material.ModelMaterial;
import com.shiroha.mmdskin.texture.runtime.TextureAlphaCoverage;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class ToonOpaqueEligibilityTest {
    private final ByteBuffer meshes = ByteBuffer.allocate(40).order(ByteOrder.nativeOrder());
    private final ModelMaterial material = new ModelMaterial();

    ToonOpaqueEligibilityTest() {
        meshes.putInt(8, 3).putFloat(12, 1).put(16, (byte) 1);
    }

    private String rejection(float alpha, ByteBuffer morphs) {
        return ToonOpaqueEligibility.rejection(meshes, 2, new ModelMaterial[]{material}, morphs, (id, base) -> alpha * base);
    }

    @Test void opaqueAndHiddenSubmeshesAreAccepted() { assertNull(rejection(1, null)); }

    @Test void globalAndMaterialAlphaNeverDisableTransparencyToGainAdmission() {
        assertEquals("TRANSLUCENT_MATERIAL", rejection(.5f, null));
        assertNull(rejection(0, null));
    }

    @Test void textureAlphaMorphIsEvaluated() {
        var morphs = ByteBuffer.allocate(56 * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 28; i++) morphs.putFloat(i * 4, 1);
        morphs.putFloat(19 * 4, .4f);
        assertEquals("TRANSLUCENT_MATERIAL", rejection(1, morphs));
    }

    @Test void binaryCutoutsAndMissingCoverageFailClosed() {
        material.hasAlpha = true;
        assertEquals("TEXTURE_ALPHA_REQUIRES_SHARED_TRANSPARENCY", rejection(1, null));
        material.alphaCoverage = TextureAlphaCoverage.fromRgba(ByteBuffer.wrap(new byte[]{0, 0, 0, -1, 0, 0, 0, 0}), 2, 1);
        assertEquals("TEXTURE_ALPHA_REQUIRES_SHARED_TRANSPARENCY", rejection(1, null));
        material.alphaCoverage = TextureAlphaCoverage.fromRgba(ByteBuffer.wrap(new byte[]{0, 0, 0, -1}), 1, 1);
        assertNull(rejection(1, null));
    }

    @Test void fractionalPixelsAndInvalidMaterialFailClosed() {
        material.hasTranslucentPixels = true;
        assertEquals("TEXTURE_ALPHA_REQUIRES_SHARED_TRANSPARENCY", rejection(1, null));
        meshes.putInt(0, 10);
        assertEquals("MISSING_MATERIAL", rejection(1, null));
    }
}
