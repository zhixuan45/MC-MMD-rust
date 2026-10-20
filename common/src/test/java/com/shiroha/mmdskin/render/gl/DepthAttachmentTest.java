package com.shiroha.mmdskin.render.gl;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL46C;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DepthAttachmentTest {
    @Test void resolvesUnsizedActualStorageWithoutAssumingD24() {
        assertEquals(GL46C.GL_DEPTH_COMPONENT16, DepthAttachment.resolveFormat(GL46C.GL_DEPTH_COMPONENT, 16, 0, GL46C.GL_UNSIGNED_NORMALIZED));
        assertEquals(GL46C.GL_DEPTH_COMPONENT24, DepthAttachment.resolveFormat(GL46C.GL_DEPTH_COMPONENT, 24, 0, GL46C.GL_UNSIGNED_NORMALIZED));
        assertEquals(GL46C.GL_DEPTH_COMPONENT32, DepthAttachment.resolveFormat(GL46C.GL_DEPTH_COMPONENT, 32, 0, GL46C.GL_UNSIGNED_NORMALIZED));
        assertEquals(GL46C.GL_DEPTH_COMPONENT32F, DepthAttachment.resolveFormat(GL46C.GL_DEPTH_COMPONENT, 32, 0, GL46C.GL_FLOAT));
        assertEquals(GL46C.GL_DEPTH24_STENCIL8, DepthAttachment.resolveFormat(GL46C.GL_DEPTH_STENCIL, 24, 8, GL46C.GL_UNSIGNED_NORMALIZED));
        assertEquals(GL46C.GL_DEPTH32F_STENCIL8, DepthAttachment.resolveFormat(GL46C.GL_DEPTH_STENCIL, 32, 8, GL46C.GL_FLOAT));
    }
    @Test void refusesUnknownPrecisionOrComponentType() {
        assertEquals(0, DepthAttachment.resolveFormat(GL46C.GL_DEPTH_COMPONENT, 0, 0, GL46C.GL_UNSIGNED_NORMALIZED));
        assertEquals(0, DepthAttachment.resolveFormat(GL46C.GL_DEPTH_COMPONENT, 24, 0, GL46C.GL_FLOAT));
        assertEquals(0, DepthAttachment.resolveFormat(GL46C.GL_DEPTH_COMPONENT, 32, 0, GL46C.GL_UNSIGNED_INT));
        assertEquals(0, DepthAttachment.resolveFormat(GL46C.GL_DEPTH_STENCIL, 32, 4, GL46C.GL_FLOAT));
        assertEquals(0, DepthAttachment.resolveFormat(GL46C.GL_RGBA8, 24, 0, GL46C.GL_UNSIGNED_NORMALIZED));
    }
}
