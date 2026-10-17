/** 文件职责：负责女仆实体的 MMD 渲染与基础动画切换。 */
package com.shiroha.mmdskin.compat.maid.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.compat.maid.runtime.MaidMMDModelManager;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import java.util.UUID;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MaidMMDRenderer {
    private static final Logger logger = LogManager.getLogger();

    public static boolean render(
            LivingEntity entity,
            UUID maidUUID,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            int packedLight) {
        if (!MaidMMDModelManager.hasMMDModel(maidUUID)) {
            return false;
        }

        ManagedModel modelData = MaidMMDModelManager.getModel(maidUUID);
        if (modelData == null || modelData.modelInstance() == null) {
            return false;
        }

        try {
            RenderSystem.setShader(GameRenderer::getRendertypeEntityTranslucentShader);
            MaidRenderPreparation.render(entity, modelData, entityYaw, partialTicks, poseStack, packedLight);
            return true;
        } catch (Exception e) {
            logger.error("Failed to render maid MMD model {}", maidUUID, e);
            return false;
        }
    }

    public static void setEntityVelocity(UUID maidUUID, float vx, float vy, float vz) {
        ManagedModel modelData = MaidMMDModelManager.getModel(maidUUID);
        if (modelData != null && modelData.modelInstance() != null) {
        }
    }
}
