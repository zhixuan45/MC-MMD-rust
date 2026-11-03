package com.shiroha.mmdskin.compat.iris;

import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.render.pipeline.toon.ToonOffscreenRenderer;
import com.shiroha.mmdskin.render.shader.ToonRenderStrategy;
import net.minecraft.client.Minecraft;

/** The sole Minecraft-specific final target adapter; never binds an inferred pack color buffer. */
public final class IrisToonLifecycle {
    private IrisToonLifecycle() {}

    public static void begin(Object pipeline) {
        if (!ConfigManager.isToonRenderingEnabled() || IrisCompat.isRenderingShadows()) return;
        // Known profiles keep the existing route, without observing every world draw in AUTO.
        if (ConfigManager.getToonRenderStrategy() == ToonRenderStrategy.AUTO && !IrisToonCompat.integratedCompileFailed()) {
            var shader = IrisWorldTransparencyTarget.entityShader(null).shader();
            if (IrisCompat.isIrisProgram(shader) && IrisToonMetadata.read(shader).profile().isSupported()) {
                ToonOffscreenRenderer.release();
                return;
            }
        }
        ToonOffscreenRenderer.begin(pipeline);
    }

    public static void beforeFinal(Object pipeline) {
        var main = Minecraft.getInstance().getMainRenderTarget();
        ToonOffscreenRenderer.beforeFinal(pipeline, main.frameBufferId, main.getColorTextureId(), main.width, main.height);
    }

    public static void afterFinal(Object pipeline) {
        var main = Minecraft.getInstance().getMainRenderTarget();
        ToonOffscreenRenderer.afterFinal(pipeline, main.frameBufferId, main.getColorTextureId(), main.width, main.height);
    }
}
