package com.shiroha.mmdskin.compat.iris;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL46C;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisTerrainTransmissionTest {
    @Test
    void acceptsUnsizedRgbaOnlyWhenActualStorageIsEightBit() {
        assertTrue(IrisTerrainTransmission.declaredColorFormatMatches(
                GL46C.GL_RGBA, GL46C.GL_RGBA8, GL46C.GL_RGBA8));
        assertTrue(IrisTerrainTransmission.declaredColorFormatMatches(
                GL46C.GL_RGBA, GL46C.GL_RGBA, GL46C.GL_RGBA8));
        assertTrue(IrisTerrainTransmission.declaredColorFormatMatches(
                GL46C.GL_RGBA8, GL46C.GL_RGBA, GL46C.GL_RGBA8));
        assertFalse(IrisTerrainTransmission.declaredColorFormatMatches(
                GL46C.GL_RGBA, GL46C.GL_RGBA16F, GL46C.GL_RGBA16F));
        assertFalse(IrisTerrainTransmission.declaredColorFormatMatches(
                GL46C.GL_RGBA, GL46C.GL_RGBA32F, GL46C.GL_RGBA32F));
    }

    @Test
    void requiresExactFloatFormatMatch() {
        assertTrue(IrisTerrainTransmission.declaredColorFormatMatches(
                GL46C.GL_RGBA16F, GL46C.GL_RGBA16F, GL46C.GL_RGBA16F));
        assertTrue(IrisTerrainTransmission.declaredColorFormatMatches(
                GL46C.GL_RGBA32F, GL46C.GL_RGBA32F, GL46C.GL_RGBA32F));
        assertFalse(IrisTerrainTransmission.declaredColorFormatMatches(
                GL46C.GL_RGBA16F, GL46C.GL_RGBA32F, GL46C.GL_RGBA32F));
        assertFalse(IrisTerrainTransmission.declaredColorFormatMatches(
                GL46C.GL_RGBA32F, GL46C.GL_RGBA16F, GL46C.GL_RGBA16F));
    }
}
