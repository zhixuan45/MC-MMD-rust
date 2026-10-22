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
    void verifiedIrisBinderCanFollowColortex0AcrossADeferredFlip() {
        assertTrue(WorldTransparencyStagePolicy.canResolveIrisColor(color(10), color(20), true));
        assertTrue(WorldTransparencyStagePolicy.canResolveIrisColor(color(10), color(10), true));
        assertFalse(WorldTransparencyStagePolicy.canResolveIrisColor(color(10), color(20), false));
    }

    @Test
    void irisResolveRejectsMissingTargetsAndChangedViewportOrStorageType() {
        assertFalse(WorldTransparencyStagePolicy.canResolveIrisColor(color(10), null, true));
        assertFalse(WorldTransparencyStagePolicy.canResolveIrisColor(null, color(20), true));
        assertFalse(WorldTransparencyStagePolicy.canResolveIrisColor(color(10),
                new FramebufferColorTarget(GL_TEXTURE, 20, 1280, 720), true));
        assertFalse(WorldTransparencyStagePolicy.canResolveIrisColor(color(10),
                new FramebufferColorTarget(GL_RENDERBUFFER, 20, 1920, 1080), true));
    }

    @Test
    void sameObjectIdWithDifferentBackingTypeIsNotEquivalent() {
        assertFalse(WorldTransparencyStagePolicy.canDeferShaderPack(
                true, color(1), new FramebufferColorTarget(GL_RENDERBUFFER, 1, 1920, 1080), color(1)));
    }
}
