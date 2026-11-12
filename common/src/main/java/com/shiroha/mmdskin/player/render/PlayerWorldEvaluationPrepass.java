package com.shiroha.mmdskin.player.render;

import com.shiroha.mmdskin.render.pipeline.WorldEntityEvaluationPrepass;
import net.minecraft.client.Camera;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;

import java.util.function.Predicate;

/** 保留旧世界入口，实际候选由跨实体调度器统一处理。 */
public final class PlayerWorldEvaluationPrepass {
    private PlayerWorldEvaluationPrepass() {}

    public static void prepare(Camera camera, Frustum frustum, float partialTick,
            Predicate<BlockPos> sectionCompiled, Predicate<AbstractClientPlayer> ysmActive) {
        prepare(camera, frustum, partialTick, sectionCompiled, ysmActive, null);
    }

    public static void prepare(Camera camera, Frustum frustum, float partialTick,
            Predicate<BlockPos> sectionCompiled, Predicate<AbstractClientPlayer> ysmActive,
            WorldEntityEvaluationPrepass.MaidEligibility maidEligibility) {
        prepare(camera, frustum, partialTick, sectionCompiled, ysmActive, maidEligibility, null);
    }

    public static void prepare(Camera camera, Frustum frustum, float partialTick,
            Predicate<BlockPos> sectionCompiled, Predicate<AbstractClientPlayer> ysmActive,
            WorldEntityEvaluationPrepass.MaidEligibility maidEligibility,
            WorldEntityEvaluationPrepass.MaidYawResolver maidYawResolver) {
        WorldEntityEvaluationPrepass.prepareWorld(camera, frustum, partialTick,
                sectionCompiled, ysmActive, maidEligibility, maidYawResolver);
    }
}
