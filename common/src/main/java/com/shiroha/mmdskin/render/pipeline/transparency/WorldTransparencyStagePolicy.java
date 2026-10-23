package com.shiroha.mmdskin.render.pipeline.transparency;

/** Conservative rules for composing MMD alpha in foreign shader-pack framebuffers. */
final class WorldTransparencyStagePolicy {
    private WorldTransparencyStagePolicy() {}

    /** Compare actual Iris attachments, not unrelated FBO wrapper handles. */
    static boolean canShareModelTarget(FramebufferColorTarget first, FramebufferColorTarget next,
                                       boolean sharedDepth, boolean sameViewport) {
        return sameViewport && sharedDepth && FramebufferColorTarget.compatible(first, next);
    }

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
     * The Iris binder was matched to the verified entity output at admission.
     * Its late color ID may differ after deferred flips colortex0; depth storage
     * and viewport must still match. Never infer a destination from loader main color.
     */
    static boolean canResolveIrisColor(FramebufferColorTarget entity,
                                       FramebufferColorTarget boundIrisColor,
                                       boolean sameDepthAndViewport) {
        return sameDepthAndViewport && entity != null && boundIrisColor != null
                && entity.type() == boundIrisColor.type()
                && entity.width() == boundIrisColor.width()
                && entity.height() == boundIrisColor.height();
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
