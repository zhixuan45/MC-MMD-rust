package com.shiroha.mmdskin.forge;

import com.shiroha.mmdskin.MmdSkinClient;
import com.shiroha.mmdskin.forge.config.MmdSkinConfig;
import com.shiroha.mmdskin.forge.entity.MobReplacementRenderEventHandler;
import com.shiroha.mmdskin.forge.maid.MaidRenderEventHandler;
import com.shiroha.mmdskin.forge.register.MmdSkinRegisterClient;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyPipeline;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Forge 客户端初始化入口
 */
@OnlyIn(Dist.CLIENT)
public class MmdSkinForgeClient {

    public static void init(IEventBus modEventBus) {
        MmdSkinRegisterClient.init(modEventBus);
        // The public stage is supported by loaders even when chunk submission is replaced.
        MinecraftForge.EVENT_BUS.addListener(MmdSkinForgeClient::onRenderLevelStage);
        modEventBus.addListener(MmdSkinForgeClient::clientSetup);
    }

    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            WorldTransparencyPipeline.afterEntities();
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            WorldTransparencyPipeline.afterOpaqueWorld();
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            WorldTransparencyPipeline.afterWorldTranslucency();
        }
    }

    public static void clientSetup(FMLClientSetupEvent event) {
        MmdSkinConfig.init();
        MmdSkinClient.initClient();
        ClientRenderRuntime.get().renderBackendSettings().setShaderEnabled(com.shiroha.mmdskin.config.ConfigManager.isMMDShaderEnabled());
        MinecraftForge.EVENT_BUS.register(new MobReplacementRenderEventHandler());
        MinecraftForge.EVENT_BUS.register(new MaidRenderEventHandler());
    }
}
