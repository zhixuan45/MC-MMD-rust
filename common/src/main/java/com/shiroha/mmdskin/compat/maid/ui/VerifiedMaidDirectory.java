/** 文件职责：只记录当前客户端实体验证过的本机主人女仆及其会话上下文。 */
package com.shiroha.mmdskin.compat.maid.ui;

import com.shiroha.mmdskin.compat.maid.runtime.MaidMMDModelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 历史只在同一个 ClientLevel 对象存活期间保留，避免把旧偏好误当作归属证据。
 */
public final class VerifiedMaidDirectory {
    private static final ResourceLocation MAID_TYPE = new ResourceLocation("touhou_little_maid", "maid");
    private static final Map<UUID, MaidRecord> SESSION_RECORDS = new LinkedHashMap<>();
    private static ClientLevel activeContext;
    private static UUID activeOwnerId;

    private VerifiedMaidDirectory() {}

    public static synchronized List<MaidRecord> listForLocalPlayer(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) return List.of();
        ClientLevel level = minecraft.level;
        UUID localPlayerId = minecraft.player.getUUID();
        if (activeContext != level || !localPlayerId.equals(activeOwnerId)) {
            // ClientLevel 身份即本地世界上下文，切换维度或服务器时丢弃旧目录。
            SESSION_RECORDS.clear();
            activeContext = level;
            activeOwnerId = localPlayerId;
        }
        Map<UUID, Entity> loadedMaids = new HashMap<>();
        Map<UUID, Entity> observedMaids = new HashMap<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!MAID_TYPE.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))
                    || !(entity instanceof TamableAnimal tameable)) continue;
            observedMaids.put(entity.getUUID(), entity);
            LivingEntity owner = tameable.getOwner();
            if (owner == null || !localPlayerId.equals(owner.getUUID())) continue;
            loadedMaids.put(entity.getUUID(), entity);
            String name = entity.getName().getString();
            SESSION_RECORDS.put(entity.getUUID(), new MaidRecord(entity.getUUID(), entity.getId(), name, owner.getUUID(), level));
        }
        // 已加载但主人变化的女仆立即移出目录，避免保留过期归属证明。
        SESSION_RECORDS.entrySet().removeIf(entry -> {
            Entity entity = observedMaids.get(entry.getKey());
            return entity instanceof TamableAnimal tameable
                    && (tameable.getOwner() == null || !localPlayerId.equals(tameable.getOwner().getUUID()));
        });
        // 仅返回当前上下文里曾由真实已加载实体验证过的 UUID。
        List<MaidRecord> result = new ArrayList<>();
        for (MaidRecord record : SESSION_RECORDS.values()) {
            if (record.context() == level && record.ownerUUID().equals(localPlayerId)) {
                Entity loaded = loadedMaids.get(record.maidUUID());
                int entityId = loaded == null ? 0 : loaded.getId();
                result.add(new MaidRecord(record.maidUUID(), entityId, record.name(), record.ownerUUID(), level));
            }
        }
        return List.copyOf(result);
    }

    public static synchronized void clearSession() {
        SESSION_RECORDS.clear();
        activeContext = null;
        activeOwnerId = null;
    }

    public record MaidRecord(UUID maidUUID, int entityId, String name, UUID ownerUUID, ClientLevel context) {
        public String currentModel() {
            return MaidMMDModelManager.getBindingModelName(maidUUID);
        }
    }
}
