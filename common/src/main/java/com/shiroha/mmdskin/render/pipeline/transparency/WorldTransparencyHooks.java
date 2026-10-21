package com.shiroha.mmdskin.render.pipeline.transparency;

import com.shiroha.mmdskin.compat.iris.IrisCompat;
import net.minecraft.client.renderer.GameRenderer;
import org.apache.logging.log4j.LogManager;

/** Loader-independent eligibility checks for the vanilla LevelRenderer draw hooks. */
public final class WorldTransparencyHooks {
    private static final boolean REPLACED_TERRAIN = present("net.caffeinemc.mods.sodium.client.SodiumClientMod")
            || present("me.jellysquid.mods.sodium.client.SodiumClientMod")
            || present("org.embeddedt.embeddium.Embeddium");
    private static boolean warned;

    private WorldTransparencyHooks() {}

    public static void beginFrame(boolean fabulous) {
        WorldTransparencyPipeline.endFrame();
        if (IrisCompat.isRenderingShadows()) return;
        if (REPLACED_TERRAIN || IrisCompat.isIrisShaderActive()) {
            if (!warned) {
                warned = true;
                LogManager.getLogger().info("Shared MMD/world transparency requires vanilla terrain submission; "
                        + "keeping the existing renderer with Sodium/Embeddium or an active Iris shader pack.");
            }
            return;
        }
        var shader = GameRenderer.getRendertypeTranslucentShader();
        if (shader != null) WorldTransparencyPipeline.beginFrame(shader.getId(), fabulous);
    }

    private static boolean present(String name) {
        try { Class.forName(name, false, WorldTransparencyHooks.class.getClassLoader()); return true; }
        catch (ClassNotFoundException e) { return false; }
    }
}
