package com.shiroha.mmdskin.render.pipeline.transparency;

/** Conservative rules for composing MMD alpha in foreign shader-pack framebuffers. */
final class WorldTransparencyStagePolicy {
    private WorldTransparencyStagePolicy() {}

    /**
     * An Iris flip-state check for colortex0 is not sufficient to enable deferral:
     * the entity, block-entity and final translucency callbacks must have been
     * observed using the same backing color attachment.
     */
    static boolean canDeferShaderPack(boolean verifiedOutput, FramebufferColorTarget entity,
                                      FramebufferColorTarget blockEntities,
                                      FramebufferColorTarget postTranslucency) {
        return verifiedOutput
                && FramebufferColorTarget.compatible(entity, blockEntities)
                && FramebufferColorTarget.compatible(entity, postTranslucency);
    }

    /**
     * A second, strictly guarded destination: the public post-block-entity and
     * post-translucency callbacks both use Minecraft's main color texture, not
     * the entity G-buffer. The opaque callback must share the entity depth
     * storage so the late resolve can correctly distinguish chest surfaces.
     */
    static boolean canComposeOnLateMain(boolean verifiedOutput,
                                        FramebufferColorTarget entity,
                                        FramebufferColorTarget blockEntities,
                                        FramebufferColorTarget postTranslucency,
                                        int mainColorTexture,
                                        boolean sameOpaqueDepth) {
        return verifiedOutput && sameOpaqueDepth && mainColorTexture > 0
                && entity != null && blockEntities != null
                && !FramebufferColorTarget.compatible(entity, blockEntities)
                && blockEntities.type() == 0x1702 // GL_TEXTURE
                && blockEntities.objectId() == mainColorTexture
                && FramebufferColorTarget.compatible(blockEntities, postTranslucency);
    }

    static boolean sameDepthStorage(com.shiroha.mmdskin.render.gl.DepthAttachment a,
                                    com.shiroha.mmdskin.render.gl.DepthAttachment b) {
        return a != null && b != null && a.objectType() == b.objectType()
                && a.objectId() == b.objectId() && a.format() == b.format()
                && a.samples() == 0 && b.samples() == 0
                && a.width() == b.width() && a.height() == b.height();
    }

    /** Fail closed if the current post-particle draw target is not the proven late target. */
    static boolean matchesLateStage(boolean queuedForLateMain,
                                    FramebufferColorTarget expected,
                                    FramebufferColorTarget current,
                                    boolean opaqueDepthCaptured) {
        return queuedForLateMain && opaqueDepthCaptured
                && FramebufferColorTarget.compatible(expected, current);
    }

    /**
     * A later depth snapshot must come from the currently active public stage,
     * and must refer to the same actual depth storage as the model capture.
     * A matching color texture alone does not meet this contract.
     */
    static boolean canCaptureOpaqueDepth(boolean colorMatches, boolean depthStorageMatches,
                                         boolean depthFormatMatches, boolean viewportInside) {
        return colorMatches && depthStorageMatches && depthFormatMatches && viewportInside;
    }
}
