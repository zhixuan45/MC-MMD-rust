package com.shiroha.mmdskin.texture.runtime;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAlphaTest {
    @Test
    void distinguishesCutoutFromTranslucentRgbaPixels() {
        ByteBuffer pixels = ByteBuffer.wrap(new byte[] {
                127, 127, 127, 0, 127, 127, 127, (byte) 255});
        assertFalse(TextureGpuLoader.hasTranslucentPixels(pixels, true));
        pixels.put(7, (byte) 128);
        assertTrue(TextureGpuLoader.hasTranslucentPixels(pixels, true));
        assertFalse(TextureGpuLoader.hasTranslucentPixels(pixels, false));
    }

    @Test
    void respectsPixelBufferPositionAndLimit() {
        ByteBuffer pixels = ByteBuffer.wrap(new byte[] {
                0, 0, 0, 127, 0, 0, 0, (byte) 255, 0, 0, 0, 127});
        pixels.position(4).limit(8);
        assertFalse(TextureGpuLoader.hasTranslucentPixels(pixels, true));
        assertTrue(pixels.position() == 4 && pixels.limit() == 8);
    }
}
