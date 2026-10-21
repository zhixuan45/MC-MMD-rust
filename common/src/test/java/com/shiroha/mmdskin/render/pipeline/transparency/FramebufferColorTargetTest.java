package com.shiroha.mmdskin.render.pipeline.transparency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FramebufferColorTargetTest {
    @Test
    void matchesBackingAttachmentAcrossDifferentFboWrappers() {
        var original = new FramebufferColorTarget(0x1702, 42, 2560, 1440);
        assertTrue(FramebufferColorTarget.compatible(original,
                new FramebufferColorTarget(0x1702, 42, 2560, 1440)));
        assertFalse(FramebufferColorTarget.compatible(original,
                new FramebufferColorTarget(0x1702, 43, 2560, 1440)));
        assertFalse(FramebufferColorTarget.compatible(original,
                new FramebufferColorTarget(0x8D41, 42, 2560, 1440)));
        assertFalse(FramebufferColorTarget.compatible(original,
                new FramebufferColorTarget(0x1702, 42, 1920, 1080)));
        assertFalse(FramebufferColorTarget.compatible(original, null));
    }
}
