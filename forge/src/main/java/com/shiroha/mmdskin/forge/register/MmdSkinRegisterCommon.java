package com.shiroha.mmdskin.forge.register;

import com.shiroha.mmdskin.forge.network.MmdSkinNetworkPack;
import com.shiroha.mmdskin.forge.stage.ForgeStageSessionRegistry;
import com.shiroha.mmdskin.player.sync.ServerModelRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Forge 通用与服务端网络及事件注册
 */
public class MmdSkinRegisterCommon {
    private static final String NETWORK_VERSION = "1";
    public static SimpleChannel channel;
    private static boolean initialized;

    public static void init(IEventBus modEventBus) {
        if (initialized) {
            return;
        }
        initialized = true;

        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation("3d-skin", "network_pack"),
                () -> NETWORK_VERSION,
                // 客户端允许进入未安装本模组的服务端；安装了本模组的服务端仍要求客户端版本匹配。
                NetworkRegistry.acceptMissingOr(NETWORK_VERSION),
                version -> NETWORK_VERSION.equals(version)
        );
        channel.registerMessage(
                0,
                MmdSkinNetworkPack.class,
                MmdSkinNetworkPack::encode,
                MmdSkinNetworkPack::new,
                MmdSkinNetworkPack::handle
        );

        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            ServerModelRegistry.onPlayerLeave(event.getEntity().getUUID());
            if (event.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
                ForgeStageSessionRegistry.getInstance().onPlayerDisconnect(player.getServer(), player);
            }
        });
    }
}
