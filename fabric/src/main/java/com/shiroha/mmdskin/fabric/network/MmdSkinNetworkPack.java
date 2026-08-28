package com.shiroha.mmdskin.fabric.network;

import com.shiroha.mmdskin.compat.maid.runtime.MaidMMDModelManager;
import com.shiroha.mmdskin.fabric.stage.FabricStageSessionRegistry;
import com.shiroha.mmdskin.player.animation.PendingAnimSignalCache;
import com.shiroha.mmdskin.player.runtime.MmdSkinRendererPlayerHelper;
import com.shiroha.mmdskin.player.sync.ClientNetworkBindings;
import com.shiroha.mmdskin.player.sync.MorphSyncHelper;
import com.shiroha.mmdskin.player.sync.PlayerModelSyncService;
import com.shiroha.mmdskin.player.sync.ServerModelRegistry;
import com.shiroha.mmdskin.stage.client.StageClientPacketHandler;
import com.shiroha.mmdskin.ui.network.NetworkOpCode;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/**
 * Fabric 1.21.1 网络包序列化与分发处理，实现 CustomPacketPayload。
 */
public class MmdSkinNetworkPack implements CustomPacketPayload {
    private static final Logger logger = LogManager.getLogger();

    public static final Type<MmdSkinNetworkPack> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("3d-skin", "network_pack")
    );

    public static final StreamCodec<FriendlyByteBuf, MmdSkinNetworkPack> STREAM_CODEC = StreamCodec.ofMember(
            MmdSkinNetworkPack::write,
            MmdSkinNetworkPack::new
    );

    public int opCode;
    public UUID playerUUID;
    public String animId;
    public int arg0;
    public byte[] binaryPayload;

    public static int toOpCode(ClientNetworkBindings.NetworkMessageType messageType) {
        return switch (messageType) {
            case CUSTOM_ANIM -> NetworkOpCode.CUSTOM_ANIM;
            case RESET_PHYSICS -> NetworkOpCode.RESET_PHYSICS;
            case MODEL_SELECT -> NetworkOpCode.MODEL_SELECT;
            case MORPH_SYNC -> NetworkOpCode.MORPH_SYNC;
            case STAGE_MULTI -> NetworkOpCode.STAGE_MULTI;
            case BONE_SYNC -> NetworkOpCode.BONE_SYNC;
        };
    }

    public MmdSkinNetworkPack(int opCode, UUID playerUUID, String animId) {
        this.opCode = opCode;
        this.playerUUID = playerUUID;
        this.animId = animId != null ? animId : "";
        this.arg0 = 0;
        this.binaryPayload = new byte[0];
    }

    public MmdSkinNetworkPack(int opCode, UUID playerUUID, int arg0) {
        this.opCode = opCode;
        this.playerUUID = playerUUID;
        this.animId = "";
        this.arg0 = arg0;
        this.binaryPayload = new byte[0];
    }

    public MmdSkinNetworkPack(int opCode, UUID playerUUID, int entityId, String modelName) {
        this.opCode = opCode;
        this.playerUUID = playerUUID;
        this.animId = modelName != null ? modelName : "";
        this.arg0 = entityId;
        this.binaryPayload = new byte[0];
    }

    public MmdSkinNetworkPack(int opCode, UUID playerUUID, byte[] binaryPayload) {
        this.opCode = opCode;
        this.playerUUID = playerUUID;
        this.animId = "";
        this.arg0 = 0;
        this.binaryPayload = binaryPayload != null ? binaryPayload : new byte[0];
    }

    public MmdSkinNetworkPack(FriendlyByteBuf buffer) {
        this.opCode = buffer.readInt();
        this.playerUUID = buffer.readUUID();

        if (this.opCode == NetworkOpCode.BONE_SYNC) {
            this.animId = "";
            this.arg0 = 0;
            this.binaryPayload = buffer.readByteArray();
        } else if (NetworkOpCode.isStringPayload(this.opCode)) {
            this.animId = buffer.readUtf();
            this.arg0 = 0;
            this.binaryPayload = new byte[0];
        } else if (NetworkOpCode.isEntityStringPayload(this.opCode)) {
            this.arg0 = buffer.readInt();
            this.animId = buffer.readUtf();
            this.binaryPayload = new byte[0];
        } else {
            this.animId = "";
            this.arg0 = buffer.readInt();
            this.binaryPayload = new byte[0];
        }
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeInt(this.opCode);
        buffer.writeUUID(this.playerUUID);

        if (this.opCode == NetworkOpCode.BONE_SYNC) {
            buffer.writeByteArray(this.binaryPayload != null ? this.binaryPayload : new byte[0]);
        } else if (NetworkOpCode.isStringPayload(this.opCode)) {
            buffer.writeUtf(this.animId != null ? this.animId : "");
        } else if (NetworkOpCode.isEntityStringPayload(this.opCode)) {
            buffer.writeInt(this.arg0);
            buffer.writeUtf(this.animId != null ? this.animId : "");
        } else {
            buffer.writeInt(this.arg0);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handleOnServer(ServerPlayer sender) {
        if (!sender.getUUID().equals(playerUUID)) {
            logger.warn("UUID 不匹配，丢弃数据包: claimed={}, real={}", playerUUID, sender.getUUID());
            return;
        }

        if (opCode == NetworkOpCode.MODEL_SELECT) {
            ServerModelRegistry.updateModel(playerUUID, animId);
        }

        if (opCode == NetworkOpCode.REQUEST_ALL_MODELS) {
            ServerModelRegistry.sendAllTo((modelOwnerUUID, modelName) ->
                    ServerPlayNetworking.send(
                            sender,
                            new MmdSkinNetworkPack(NetworkOpCode.MODEL_SELECT, modelOwnerUUID, modelName)
                    ));
            return;
        }

        if (opCode == NetworkOpCode.STAGE_MULTI) {
            if (sender.getServer() != null) {
                FabricStageSessionRegistry.getInstance().handlePacket(sender.getServer(), sender, animId);
            }
            return;
        }

        // 服务端向全服其他玩家广播
        for (ServerPlayer otherPlayer : PlayerLookup.all(sender.server)) {
            if (!otherPlayer.equals(sender)) {
                ServerPlayNetworking.send(otherPlayer, this);
            }
        }
    }

    public void doInClient() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (opCode == NetworkOpCode.STAGE_MULTI) {
            StageClientPacketHandler.getInstance().handle(playerUUID, animId);
            return;
        }
        if (playerUUID.equals(mc.player.getUUID())) return;
        if (mc.level == null) return;

        Player target = mc.level.getPlayerByUUID(playerUUID);

        switch (opCode) {
            case NetworkOpCode.CUSTOM_ANIM -> {
                if (target != null) MmdSkinRendererPlayerHelper.CustomAnim(target, animId);
            }
            case NetworkOpCode.RESET_PHYSICS -> {
                if (target != null) {
                    MmdSkinRendererPlayerHelper.ResetPhysics(target);
                } else {
                    PendingAnimSignalCache.put(playerUUID, PendingAnimSignalCache.SignalType.RESET);
                }
            }
            case NetworkOpCode.MODEL_SELECT -> {
                PlayerModelSyncService.onRemotePlayerModelReceived(playerUUID, animId);
            }
            case NetworkOpCode.MAID_MODEL -> {
                Entity maidEntity = mc.level.getEntity(arg0);
                if (maidEntity != null) MaidMMDModelManager.bindModel(maidEntity.getUUID(), animId);
            }
            case NetworkOpCode.MAID_ACTION -> {
                Entity maidEntity = mc.level.getEntity(arg0);
                if (maidEntity != null) MaidMMDModelManager.playAnimation(maidEntity.getUUID(), animId);
            }
            case NetworkOpCode.MORPH_SYNC -> {
                if (target != null) MorphSyncHelper.applyRemoteMorph(target, animId);
            }
            default -> {}
        }
    }
}
