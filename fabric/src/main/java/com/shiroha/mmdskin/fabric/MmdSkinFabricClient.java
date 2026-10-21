package com.shiroha.mmdskin.fabric;

import com.shiroha.mmdskin.MmdSkinClient;
import com.shiroha.mmdskin.fabric.config.MmdSkinConfig;
import com.shiroha.mmdskin.fabric.maid.MaidCompatMixinPlugin;
import com.shiroha.mmdskin.fabric.register.MmdSkinRegisterClient;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyPipeline;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric 客户端初始化
 */
public class MmdSkinFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MmdSkinConfig.init();
        MmdSkinClient.initClient();
        MmdSkinRegisterClient.Register();
        // Avoid renderer-private renderSectionLayer internals.
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> WorldTransparencyPipeline.afterOpaqueWorld());
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> WorldTransparencyPipeline.afterWorldTranslucency());
        ClientRenderRuntime.get().renderBackendSettings().setShaderEnabled(com.shiroha.mmdskin.config.ConfigManager.isMMDShaderEnabled());
    }
}
