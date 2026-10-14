package com.shiroha.mmdskin.texture.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

class TextureAlphaCoverageTest {
    private TextureAlphaCoverage atlas() {
        ByteBuffer rgba = ByteBuffer.allocate(64 * 64 * 4);
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++)
                rgba.putInt((y * 64 + x) * 4, x < 32 ? 0xffffffff : 0xffffff00);
        // putInt above uses big endian: alpha is last in RGBA.
        return TextureAlphaCoverage.fromRgba(rgba, 64, 64);
    }

    @Test
    void solidPixelsOnlyBlendWhenMaterialOpacityRequiresIt() {
        var coverage = atlas();
        assertFalse(coverage.mayBlend(.15f, .3f, .2f, .4f, .15f, .4f, false));
        assertTrue(coverage.mayBlend(.15f, .3f, .2f, .4f, .15f, .4f, true));
    }

    @Test
    void keepsBilinearTransitionsAcrossBinaryAlphaTiles() {
        assertTrue(atlas().mayBlend(.49f, .3f, .51f, .3f, .5f, .4f, false));
    }

    @Test
    void fullyTransparentInteriorCannotContributeColor() {
        assertFalse(atlas().mayBlend(.65f, .3f, .7f, .4f, .65f, .4f, true));
    }

    @Test
    void repeatEdgesAndFractionalPixelsStayConservative() {
        assertTrue(atlas().mayBlend(-.2f, .3f, .1f, .3f, .1f, .4f, false));
        ByteBuffer rgba = ByteBuffer.allocate(64 * 64 * 4);
        for (int p = 0; p < 64 * 64; p++) rgba.put(p * 4 + 3, (byte) 128);
        var coverage = TextureAlphaCoverage.fromRgba(rgba, 64, 64);
        assertTrue(coverage.mayBlend(.2f, .3f, .3f, .3f, .2f, .4f, false));
    }
}
