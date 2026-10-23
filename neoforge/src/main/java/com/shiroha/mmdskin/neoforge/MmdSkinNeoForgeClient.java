package com.shiroha.mmdskin.neoforge;

import com.shiroha.mmdskin.MmdSkinClient;
import com.shiroha.mmdskin.neoforge.config.MmdSkinConfig;
import com.shiroha.mmdskin.neoforge.entity.MobReplacementRenderEventHandler;
import com.shiroha.mmdskin.neoforge.maid.MaidRenderEventHandler;
import com.shiroha.mmdskin.neoforge.register.MmdSkinRegisterClient;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyPipeline;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge 客户端初始化入口
 */
@OnlyIn(Dist.CLIENT)
public class MmdSkinNeoForgeClient {

    public static void init(IEventBus modEventBus) {
        MmdSkinRegisterClient.init(modEventBus);
        // The public stage is supported by loaders even when chunk submission is replaced.
        NeoForge.EVENT_BUS.addListener(MmdSkinNeoForgeClient::onRenderLevelStage);
        modEventBus.addListener(MmdSkinNeoForgeClient::clientSetup);
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
        NeoForge.EVENT_BUS.register(new MobReplacementRenderEventHandler());
        NeoForge.EVENT_BUS.register(new MaidRenderEventHandler());
    }
}
