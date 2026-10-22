package com.shiroha.mmdskin.render.pipeline.transparency;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldTransparencyStagePolicyTest {
    private static final int GL_TEXTURE = 0x1702;
    private static final int GL_RENDERBUFFER = 0x8D41;

    private static FramebufferColorTarget color(int id) {
        return new FramebufferColorTarget(GL_TEXTURE, id, 1920, 1080);
    }

    @Test
    void verifiedShaderAndAllMatchingStageBackingsPermitDeferredAlpha() {
        assertTrue(WorldTransparencyStagePolicy.canDeferShaderPack(
                true, color(1), color(1), color(1)));
    }

    @Test
    void stablePrimaryDoesNotOverrideMismatchedBlockEntityOrLateStages() {
        // Regression: Iris may report colortex0 unflipped even if its public
        // callbacks bind a separate FBO whose backing color is not the model's.
        assertFalse(WorldTransparencyStagePolicy.canDeferShaderPack(
                true, color(1), color(2), color(1)));
        assertFalse(WorldTransparencyStagePolicy.canDeferShaderPack(
                true, color(1), color(1), color(3)));
        assertFalse(WorldTransparencyStagePolicy.canDeferShaderPack(
                true, color(1), null, color(1)));
        assertFalse(WorldTransparencyStagePolicy.canDeferShaderPack(
                true, color(1), color(1), null));
        assertFalse(WorldTransparencyStagePolicy.canDeferShaderPack(
                false, color(1), color(1), color(1)));
    }

    @Test
    void sameColorDoesNotProveThatChestDepthIsPresent() {
        // Rebinding the saved entity FBO gives the same colortex0 but can
        // still read entity-time depth, missing later-rendered chest faces.
        assertFalse(WorldTransparencyStagePolicy.canCaptureOpaqueDepth(true, false, true, true));
        assertFalse(WorldTransparencyStagePolicy.canCaptureOpaqueDepth(false, true, true, true));
        assertFalse(WorldTransparencyStagePolicy.canCaptureOpaqueDepth(true, true, false, true));
        assertFalse(WorldTransparencyStagePolicy.canCaptureOpaqueDepth(true, true, true, false));
        assertTrue(WorldTransparencyStagePolicy.canCaptureOpaqueDepth(true, true, true, true));
    }

    @Test
    void lateMainAlphaOverlayRequiresTheActualMainColorAndSharedOpaqueDepth() {
        assertTrue(WorldTransparencyStagePolicy.canComposeOnLateMain(
                true, color(10), color(20), color(20), 20, true));
        assertFalse(WorldTransparencyStagePolicy.canComposeOnLateMain(
                true, color(10), color(20), color(20), 20, false));
        assertFalse(WorldTransparencyStagePolicy.canComposeOnLateMain(
                true, color(10), color(20), color(21), 20, true));
        assertFalse(WorldTransparencyStagePolicy.canComposeOnLateMain(
                true, color(10), color(20), color(20), 21, true));
        assertFalse(WorldTransparencyStagePolicy.canComposeOnLateMain(
                false, color(10), color(20), color(20), 20, true));
        assertFalse(WorldTransparencyStagePolicy.canComposeOnLateMain(
                true, color(10), color(10), color(10), 10, true));
        assertFalse(WorldTransparencyStagePolicy.matchesLateStage(true, color(20), color(21), true));
        assertFalse(WorldTransparencyStagePolicy.matchesLateStage(true, color(20), color(20), false));
        assertTrue(WorldTransparencyStagePolicy.matchesLateStage(true, color(20), color(20), true));
    }

    @Test
    void lateMainRejectsStaleOrDifferentDepthStorage() {
        var a = new com.shiroha.mmdskin.render.gl.DepthAttachment(
                GL_TEXTURE, 19, 0x81A6, 0x81A6, 24, 0, 0x8C17, 1920, 1080, 0);
        var otherObject = new com.shiroha.mmdskin.render.gl.DepthAttachment(
                GL_TEXTURE, 20, 0x81A6, 0x81A6, 24, 0, 0x8C17, 1920, 1080, 0);
        var wrongFormat = new com.shiroha.mmdskin.render.gl.DepthAttachment(
                GL_TEXTURE, 19, 0x81A7, 0x81A7, 32, 0, 0x8C17, 1920, 1080, 0);
        var wrongSize = new com.shiroha.mmdskin.render.gl.DepthAttachment(
                GL_TEXTURE, 19, 0x81A6, 0x81A6, 24, 0, 0x8C17, 1280, 720, 0);
        assertTrue(WorldTransparencyStagePolicy.sameDepthStorage(a, a));
        assertFalse(WorldTransparencyStagePolicy.sameDepthStorage(a, otherObject));
        assertFalse(WorldTransparencyStagePolicy.sameDepthStorage(a, wrongFormat));
        assertFalse(WorldTransparencyStagePolicy.sameDepthStorage(a, wrongSize));
        assertFalse(WorldTransparencyStagePolicy.sameDepthStorage(a, null));
    }

    @Test
    void sameObjectIdWithDifferentBackingTypeIsNotEquivalent() {
        assertFalse(WorldTransparencyStagePolicy.canDeferShaderPack(
                true, color(1), new FramebufferColorTarget(GL_RENDERBUFFER, 1, 1920, 1080), color(1)));
    }
}
