package com.shiroha.mmdskin.forge.network;

import com.shiroha.mmdskin.compat.maid.runtime.MaidMMDModelManager;
import com.shiroha.mmdskin.player.animation.PendingAnimSignalCache;
import com.shiroha.mmdskin.player.runtime.MmdSkinRendererPlayerHelper;
import com.shiroha.mmdskin.player.sync.MorphSyncHelper;
import com.shiroha.mmdskin.player.sync.PlayerModelSyncService;
import com.shiroha.mmdskin.stage.client.StageClientPacketHandler;
import com.shiroha.mmdskin.ui.network.NetworkOpCode;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 隔离客户端专属类，确保专用服务端加载网络包时不解析 Minecraft 客户端实现。
 */
@OnlyIn(Dist.CLIENT)
final class MmdSkinNetworkPackClientHandler {
    private MmdSkinNetworkPackClientHandler() {
    }

    static void handle(MmdSkinNetworkPack pack) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (pack.opCode == NetworkOpCode.STAGE_MULTI) {
            StageClientPacketHandler.getInstance().handle(pack.playerUUID, pack.animId);
            return;
        }
        if (pack.playerUUID.equals(minecraft.player.getUUID())) {
            return;
        }
        if (minecraft.level == null) {
            return;
        }

        Player target = minecraft.level.getPlayerByUUID(pack.playerUUID);
        switch (pack.opCode) {
            case NetworkOpCode.CUSTOM_ANIM -> {
                if (target != null) {
                    MmdSkinRendererPlayerHelper.CustomAnim(target, pack.animId);
                }
            }
            case NetworkOpCode.RESET_PHYSICS -> {
                if (target != null) {
                    MmdSkinRendererPlayerHelper.ResetPhysics(target);
                } else {
                    PendingAnimSignalCache.put(pack.playerUUID, PendingAnimSignalCache.SignalType.RESET);
                }
            }
            case NetworkOpCode.MODEL_SELECT ->
                    PlayerModelSyncService.onRemotePlayerModelReceived(pack.playerUUID, pack.animId);
            case NetworkOpCode.MAID_MODEL -> {
                Entity maidEntity = minecraft.level.getEntity(pack.arg0);
                if (maidEntity != null) {
                    MaidMMDModelManager.bindModel(maidEntity.getUUID(), pack.animId);
                }
            }
            case NetworkOpCode.MAID_ACTION -> {
                Entity maidEntity = minecraft.level.getEntity(pack.arg0);
                if (maidEntity != null) {
                    MaidMMDModelManager.playAnimation(maidEntity.getUUID(), pack.animId);
                }
            }
            case NetworkOpCode.MORPH_SYNC -> {
                if (target != null) {
                    MorphSyncHelper.applyRemoteMorph(target, pack.animId);
                }
            }
            default -> {
            }
        }
    }
}
