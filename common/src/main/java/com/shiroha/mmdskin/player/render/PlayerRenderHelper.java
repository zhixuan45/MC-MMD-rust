package com.shiroha.mmdskin.player.render;

import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.model.runtime.ModelRenderProperties;
import com.shiroha.mmdskin.player.runtime.FirstPersonManager;
import com.shiroha.mmdskin.render.scene.MutableRenderPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;

/** 文件职责：集中计算玩家模型渲染姿态与模型属性读取。 */
public final class PlayerRenderHelper {

    private PlayerRenderHelper() {}

    public static MutableRenderPose calculateMutableRenderPose(AbstractClientPlayer player, ManagedModel modelData, float tickDelta) {
        MutableRenderPose params = new MutableRenderPose();
        ModelRenderProperties renderProperties = modelData.renderProperties();
        float vrBodyYaw = FirstPersonManager.vrRuntime().getBodyYawDegrees(player, tickDelta);
        // 使用 rotLerp 对身体朝向进行平滑插值，保证与原版平滑插值位置保持同帧同步
        float fallbackYaw = Float.isFinite(vrBodyYaw) ? vrBodyYaw : Mth.rotLerp(tickDelta, player.yBodyRotO, player.yBodyRot);
        params.bodyYaw = FirstPersonManager.resolveFirstPersonModelYaw(player, tickDelta, fallbackYaw);
        params.bodyPitch = 0.0f;
        params.translation.zero();

        if (player.isFallFlying()) {
            params.bodyPitch = player.getXRot() + renderProperties.flyingPitch();
            params.translation.set(renderProperties.flyingTranslation());
        } else if (player.isSleeping()) {
            params.bodyYaw = player.getBedOrientation().toYRot() + 180.0f;
            params.bodyPitch = renderProperties.sleepingPitch();
            params.translation.set(renderProperties.sleepingTranslation());
        } else if (player.isSwimming()) {
            params.bodyPitch = player.getXRot() + renderProperties.swimmingPitch();
            params.translation.set(renderProperties.swimmingTranslation());
        } else if (player.isVisuallyCrawling()) {
            params.bodyPitch = renderProperties.crawlingPitch();
            params.translation.set(renderProperties.crawlingTranslation());
        }

        return params;
    }

    public static float[] getModelSize(ManagedModel modelData) {
        return new float[] {
                modelData.renderProperties().modelScale(),
                modelData.renderProperties().inventoryScale()
        };
    }
}
