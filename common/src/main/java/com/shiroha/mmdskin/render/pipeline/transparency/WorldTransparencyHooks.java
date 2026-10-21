package com.shiroha.mmdskin.render.pipeline.transparency;

import com.shiroha.mmdskin.compat.iris.IrisCompat;
import org.apache.logging.log4j.LogManager;

/** Loader-independent eligibility checks; resolve happens at a public translucent stage. */
public final class WorldTransparencyHooks {
    private static boolean warned;

    private WorldTransparencyHooks() {}

    public static void beginFrame(boolean fabulous) {
        boolean shaderPack = IrisCompat.isIrisShaderActive();
        boolean supported = !IrisCompat.isRenderingShadows();
        if (shaderPack && !warned) {
            warned = true;
            LogManager.getLogger().info("Iris/NeOculus transparent queue requires matching entity and post-particle primary color attachments.");
        }
        WorldTransparencyPipeline.beginFrame(fabulous, supported, shaderPack);
    }
}
