package com.shiroha.mmdskin.neoforge.register;

import com.shiroha.mmdskin.compat.maid.network.MaidActionNetworkHandler;
import com.shiroha.mmdskin.compat.maid.network.MaidModelNetworkHandler;
import com.shiroha.mmdskin.neoforge.network.MmdSkinNetworkPack;
import com.shiroha.mmdskin.player.sync.ClientNetworkBindings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 绑定 NeoForge 客户端各类网络发送器。
 */
@OnlyIn(Dist.CLIENT)
final class NeoForgeClientNetworkBindings {
    private boolean registered;

    void register() {
        if (registered) {
            return;
        }
        registered = true;

        Minecraft minecraft = Minecraft.getInstance();

        ClientNetworkBindings.bind(new ClientNetworkBindings.ClientNetworkSender() {
            @Override
            public void sendString(java.util.UUID playerUUID,
                                   ClientNetworkBindings.NetworkMessageType messageType,
                                   String payload) {
                LocalPlayer player = minecraft.player;
                java.util.UUID resolvedPlayerUuid = playerUUID;
                if (resolvedPlayerUuid == null) {
                    if (player == null) {
                        return;
                    }
                    resolvedPlayerUuid = player.getUUID();
                }
                PacketDistributor.sendToServer(
                        new MmdSkinNetworkPack(MmdSkinNetworkPack.toOpCode(messageType), resolvedPlayerUuid, payload));
            }

            @Override
            public void sendInt(ClientNetworkBindings.NetworkMessageType messageType, int payload) {
                LocalPlayer player = minecraft.player;
                if (player == null) {
                    return;
                }
                PacketDistributor.sendToServer(
                        new MmdSkinNetworkPack(MmdSkinNetworkPack.toOpCode(messageType), player.getUUID(), payload));
            }

            @Override
            public void sendBinary(ClientNetworkBindings.NetworkMessageType messageType, byte[] payload) {
                LocalPlayer player = minecraft.player;
                if (player == null) {
                    return;
                }
                PacketDistributor.sendToServer(
                        new MmdSkinNetworkPack(MmdSkinNetworkPack.toOpCode(messageType), player.getUUID(), payload));
            }
        });

        MaidModelNetworkHandler.getInstance().setNetworkSender((entityId, modelName) -> {
            LocalPlayer player = minecraft.player;
            if (player != null) {
                PacketDistributor.sendToServer(
                        new MmdSkinNetworkPack(com.shiroha.mmdskin.ui.network.NetworkOpCode.MAID_MODEL, player.getUUID(), entityId, modelName));
            }
        });

        MaidActionNetworkHandler.getInstance().setNetworkSender((entityId, animId) -> {
            LocalPlayer player = minecraft.player;
            if (player != null) {
                PacketDistributor.sendToServer(
                        new MmdSkinNetworkPack(com.shiroha.mmdskin.ui.network.NetworkOpCode.MAID_ACTION, player.getUUID(), entityId, animId));
            }
        });
    }
}
