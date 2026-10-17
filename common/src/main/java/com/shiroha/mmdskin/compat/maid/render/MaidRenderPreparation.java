package com.shiroha.mmdskin.compat.maid.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.player.runtime.EntityAnimState;
import com.shiroha.mmdskin.render.backend.BaseModelInstance;
import com.shiroha.mmdskin.render.pipeline.NativeEvaluationBatch;
import com.shiroha.mmdskin.render.pipeline.RenderPerformanceProfiler;
import com.shiroha.mmdskin.render.scene.RenderScene;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3f;

/** 女仆正式绘制和预处理共用动画状态及缩放姿态作用域。 */
public final class MaidRenderPreparation {
    private MaidRenderPreparation() {}

    public static NativeEvaluationBatch.Work prepareEvaluation(LivingEntity entity, ManagedModel data,
            float entityYaw, float partialTick, RenderPerformanceProfiler.Scene scene) {
        if (entity == null || data == null || !(data.modelInstance() instanceof BaseModelInstance model)
                || !model.canPrepareWorldEvaluation()) return null;
        updateAnimationState(entity, data);
        return withScaledPitch(entity, data, () -> model.prepareWorldEvaluation(entity, entityYaw, partialTick, scene));
    }

    public static void render(LivingEntity entity, ManagedModel data, float entityYaw, float partialTick,
            PoseStack poseStack, int packedLight) {
        updateAnimationState(entity, data);
        Vector3f translation = getEntityTranslation(data);
        float modelSize = data.renderProperties().modelScale();
        poseStack.scale(modelSize, modelSize, modelSize);
        withScaledPitch(entity, data, () -> {
            data.modelInstance().render(entity, entityYaw, 0.0f, translation, partialTick,
                    poseStack, packedLight, RenderScene.WORLD);
            return null;
        });
    }

    private static <T> T withScaledPitch(LivingEntity entity, ManagedModel data,
                                         java.util.function.Supplier<T> action) {
        float size = data.renderProperties().modelScale();
        if (size == 1.0f || size <= 0.0f) return action.get();
        float pitch = entity.getXRot();
        float oldPitch = entity.xRotO;
        try {
            entity.setXRot(pitch * size);
            entity.xRotO = oldPitch * size;
            return action.get();
        } finally {
            entity.setXRot(pitch);
            entity.xRotO = oldPitch;
        }
    }

    private static void updateAnimationState(LivingEntity entity, ManagedModel data) {
        EntityAnimState state = data.entityState();
        EntityAnimState.State target;
        if (entity.getHealth() <= 0) target = EntityAnimState.State.Die;
        else if (entity.isSleeping()) target = EntityAnimState.State.Sleep;
        else if (entity.isPassenger()) target = EntityAnimState.State.Ride;
        else if (entity.isSwimming()) target = EntityAnimState.State.Swim;
        else if (entity.onClimbable()) target = EntityAnimState.State.OnClimbable;
        else if (entity.isSprinting()) target = EntityAnimState.State.Sprint;
        else if (entity.getX() - entity.xo != 0.0 || entity.getZ() - entity.zo != 0.0)
            target = EntityAnimState.State.Walk;
        else target = EntityAnimState.State.Idle;

        if (state.stateLayers[0] != target) {
            state.stateLayers[0] = target;
            data.modelInstance().changeAnim(data.animationLibrary().animation(EntityAnimState.getPropertyName(target)), 0);
        }
    }

    private static Vector3f getEntityTranslation(ManagedModel data) {
        String value = data.properties.getProperty("entityTrans");
        if (value == null || value.isBlank()) return new Vector3f();
        return com.shiroha.mmdskin.util.VectorParseUtil.parseVec3f(value);
    }
}
