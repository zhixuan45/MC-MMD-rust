package com.shiroha.mmdskin.render.policy;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** 文件职责：定义世界场景中的模型更新与物理策略。 */
public final class WorldRenderPolicy {
    private static final WorldRenderPolicy INSTANCE = new WorldRenderPolicy();

    private WorldRenderPolicy() {
    }

    public static WorldRenderPolicy get() {
        return INSTANCE;
    }

    public Decision resolve(long modelHandle, Entity entity) {
        RenderPriorityService priorityService = RenderPriorityService.get();
        priorityService.beginWorldFrame();

        boolean localPlayer = isLocalPlayer(entity);
        double distanceSq = priorityService.distanceSqToCamera(entity, localPlayer);
        boolean shouldUpdate = priorityService.shouldUpdateAnimation(modelHandle, distanceSq, localPlayer);
        boolean physicsEnabled = priorityService.shouldEnablePhysics(entity, localPlayer);
        return worldDecision(shouldUpdate, physicsEnabled);
    }

    static Decision worldDecision(boolean shouldUpdate, boolean physicsEnabled) {
        // 动画跳帧只暂停求值，不能反复关闭并重建物理链。
        return new Decision(true, shouldUpdate, physicsEnabled);
    }

    private boolean isLocalPlayer(Entity entity) {
        if (!(entity instanceof Player player)) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.getUUID().equals(player.getUUID());
    }

    public record Decision(boolean shouldRender, boolean shouldUpdate, boolean physicsEnabled) {
    }
}
