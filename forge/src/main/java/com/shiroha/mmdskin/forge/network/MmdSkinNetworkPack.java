package com.shiroha.mmdskin.forge.network;

import com.shiroha.mmdskin.forge.register.MmdSkinRegisterCommon;
import com.shiroha.mmdskin.forge.stage.ForgeStageSessionRegistry;
import com.shiroha.mmdskin.player.sync.ClientNetworkBindings;
import com.shiroha.mmdskin.player.sync.ServerModelRegistry;
import com.shiroha.mmdskin.ui.network.NetworkOpCode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Forge 1.20.1 网络包序列化与分发处理。
 */
public class MmdSkinNetworkPack {
    private static final Logger logger = LogManager.getLogger();

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

    public void encode(FriendlyByteBuf buffer) {
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

    /**
     * Forge 数据包接收主入口
     */
    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getDirection().getReceptionSide() == LogicalSide.CLIENT) {
                DistExecutor.unsafeRunWhenOn(
                        Dist.CLIENT,
                        () -> () -> MmdSkinNetworkPackClientHandler.handle(this)
                );
            } else {
                handleOnServer(context);
            }
        });
        context.setPacketHandled(true);
    }

    private void handleOnServer(NetworkEvent.Context context) {
        ServerPlayer sender = context.getSender();
        if (sender == null) {
            return;
        }

        if (!sender.getUUID().equals(playerUUID)) {
            logger.warn("UUID 不匹配，丢弃数据包: claimed={}, real={}", playerUUID, sender.getUUID());
            return;
        }

        if (opCode == NetworkOpCode.MODEL_SELECT) {
            ServerModelRegistry.updateModel(playerUUID, animId);
        }

        if (opCode == NetworkOpCode.REQUEST_ALL_MODELS) {
            ServerModelRegistry.sendAllTo((modelOwnerUUID, modelName) ->
                    MmdSkinRegisterCommon.channel.send(
                            PacketDistributor.PLAYER.with(() -> sender),
                            new MmdSkinNetworkPack(NetworkOpCode.MODEL_SELECT, modelOwnerUUID, modelName)
                    ));
            return;
        }

        if (opCode == NetworkOpCode.STAGE_MULTI) {
            if (sender.getServer() != null) {
                ForgeStageSessionRegistry.getInstance().handlePacket(sender.getServer(), sender, animId);
            }
            return;
        }

        // 服务端向全服玩家广播
        MmdSkinRegisterCommon.channel.send(PacketDistributor.ALL.noArg(), this);
    }
}
