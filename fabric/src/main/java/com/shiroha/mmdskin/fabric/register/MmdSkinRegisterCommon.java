package com.shiroha.mmdskin.fabric.register;

import com.shiroha.mmdskin.fabric.network.MmdSkinNetworkPack;
import com.shiroha.mmdskin.fabric.stage.FabricStageSessionRegistry;
import com.shiroha.mmdskin.player.sync.ServerModelRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Fabric 服务端网络与生命周期注册
 */
public class MmdSkinRegisterCommon {
    private static final Logger logger = LogManager.getLogger();

    public static void Register() {
        // 注册 CustomPacketPayload 载荷编解码器（Minecraft 1.21.1 / Fabric API）
        PayloadTypeRegistry.playC2S().register(MmdSkinNetworkPack.TYPE, MmdSkinNetworkPack.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(MmdSkinNetworkPack.TYPE, MmdSkinNetworkPack.STREAM_CODEC);

        // 注册服务端数据包接收器
        ServerPlayNetworking.registerGlobalReceiver(MmdSkinNetworkPack.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                payload.handleOnServer(context.player());
            });
        });

        // 玩家离线事件清理
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerModelRegistry.onPlayerLeave(handler.getPlayer().getUUID());
            FabricStageSessionRegistry.getInstance().onPlayerDisconnect(server, handler.getPlayer());
        });
    }
}
