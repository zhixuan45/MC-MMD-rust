package com.shiroha.mmdskin.render.pipeline.transparency;

import com.shiroha.mmdskin.compat.iris.IrisCompat;
import org.apache.logging.log4j.LogManager;

/** Loader-independent eligibility checks; resolve happens at a public translucent stage. */
public final class WorldTransparencyHooks {
    private static boolean warned;

    private WorldTransparencyHooks() {}

    public static void beginFrame(boolean fabulous) {
        boolean supported = !IrisCompat.isRenderingShadows() && !IrisCompat.isIrisShaderActive();
        if (!supported && !warned && !IrisCompat.isRenderingShadows()) {
            warned = true;
            LogManager.getLogger().info("Shared MMD transparency retains the immediate renderer when an Iris/NeOculus shader pack is active.");
        }
        WorldTransparencyPipeline.beginFrame(fabulous, supported);
    }
}
