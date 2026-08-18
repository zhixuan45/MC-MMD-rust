package com.shiroha.mmdskin.render.pipeline;

import com.shiroha.mmdskin.bridge.runtime.NativeScenePort;
import com.shiroha.mmdskin.render.scene.RenderScene;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** 文件职责：计算并同步模型头部朝向。 */
public final class HeadAngleHelper {

    private static final float MAX_PITCH = 50.0f;

    private static final float MAX_YAW = 80.0f;

    private HeadAngleHelper() {
    }

    public static void updateHeadAngle(NativeScenePort scenePort,
                                       long modelHandle,
                                       LivingEntity entity,
                                       float entityYaw,
                                       float tickDelta,
                                       RenderScene context) {
        float headAngleX = Mth.clamp(entity.getXRot(), -MAX_PITCH, MAX_PITCH);
        float headYaw = Mth.rotLerp(tickDelta, entity.yHeadRotO, entity.yHeadRot);
        float bodyYaw = entity instanceof Player player
                ? Mth.rotLerp(tickDelta, player.yBodyRotO, player.yBodyRot)
                : entityYaw;
        float headAngleY = Mth.wrapDegrees(bodyYaw - headYaw);
        headAngleY = Mth.clamp(headAngleY, -MAX_YAW, MAX_YAW);

        float pitchRad = headAngleX * ((float) Math.PI / 180F);
        // 原版背包临时角度已经表达头部相对身体的反向关系，不能再次取反。
        float yawRad = headAngleY * ((float) Math.PI / 180F);

        scenePort.setHeadAngle(modelHandle, pitchRad, yawRad, 0.0f, context.isWorldScene());
    }
}
