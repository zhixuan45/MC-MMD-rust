package com.shiroha.mmdskin.neoforge.register;

import com.shiroha.mmdskin.neoforge.network.MmdSkinNetworkPack;
import com.shiroha.mmdskin.neoforge.stage.NeoForgeStageSessionRegistry;
import com.shiroha.mmdskin.player.sync.ServerModelRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge 通用与服务端网络及事件注册
 */
public class MmdSkinRegisterCommon {
    static String networkVersion = "1";

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(MmdSkinRegisterCommon::onRegisterPayloads);

        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            ServerModelRegistry.onPlayerLeave(event.getEntity().getUUID());
            if (event.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
                NeoForgeStageSessionRegistry.getInstance().onPlayerDisconnect(player.getServer(), player);
            }
        });
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(networkVersion).optional();
        registrar.playBidirectional(
                MmdSkinNetworkPack.TYPE,
                MmdSkinNetworkPack.STREAM_CODEC,
                MmdSkinNetworkPack::handle
        );
    }
}
