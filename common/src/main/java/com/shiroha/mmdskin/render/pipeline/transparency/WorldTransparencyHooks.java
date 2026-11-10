package com.shiroha.mmdskin.render.pipeline.transparency;

import com.shiroha.mmdskin.render.port.IrisBackend;
import org.apache.logging.log4j.LogManager;

/** Loader-independent eligibility checks; resolve happens at a public translucent stage. */
public final class WorldTransparencyHooks {
    private static final IrisBackend IRIS = IrisBackend.current();
    private static boolean warned;

    private WorldTransparencyHooks() {}

    public static void beginFrame(boolean fabulous) {
        boolean shaderPack = IRIS.isShaderPackActive();
        boolean supported = !IRIS.isShadowPass();
        if (shaderPack && !warned) {
            warned = true;
            LogManager.getLogger().info("Iris/NeOculus transparent queue verifies primary color attachments or stable colortex0 via Iris buffer-flip state.");
        }
        WorldTransparencyPipeline.beginFrame(fabulous, supported, shaderPack);
    }
}
