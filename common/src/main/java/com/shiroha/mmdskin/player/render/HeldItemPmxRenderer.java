package com.shiroha.mmdskin.player.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.asset.catalog.HeldPmxAssets;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.config.ModelConfigManager;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.model.runtime.ModelRequestKey;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.render.scene.RenderScene;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import net.minecraft.client.player.AbstractClientPlayer;
import org.joml.Vector3f;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Renders an independent PMX prop in the character's selected evaluated bone space.
 * A missing, pending or failed prop never suppresses the normal Minecraft item.
 */
final class HeldItemPmxRenderer {
    private static final Logger LOGGER = LogManager.getLogger();
    private HeldItemPmxRenderer() { }

    /**
     * @return true if a ready prop owns the slot, including an intentionally hidden stage prop.
     */
    static boolean renderMainHand(AbstractClientPlayer player, ManagedModel character,
                                  PoseStack poseStack, int packedLight, float tickDelta) {
        String modelName = character.requestKey().modelName();
        ModelConfigData config = ModelConfigManager.getConfig(modelName);
        var stageRuntime = com.shiroha.mmdskin.stage.client.StageClientRuntime.peek();
        var propAnimations = stageRuntime != null ? stageRuntime.animSyncHelper().propAnimations() : null;
        boolean inStage = character.entityState().playStageAnim;
        var assignment = inStage && propAnimations != null ? propAnimations.assignment(player.getUUID()) : null;
        if (assignment == null && player.getMainHandItem().isEmpty()) return false;
        String pmx = assignment != null ? assignment.pmxFile() : config.heldPmxFile;
        if (pmx == null || pmx.isEmpty()) return false;

        if (HeldPmxAssets.resolve(modelName, pmx) == null) return false;

        ModelRequestKey propKey = assignment==null?ModelRequestKey.heldItem(player, modelName, pmx)
                :propAnimations.requestKey(player.getUUID(),modelName);
        ManagedModel prop = ClientRenderRuntime.get().modelRepository().acquire(propKey);
        if (prop == null || prop.modelInstance().getModelHandle() == 0L) return false;

        if (assignment != null) {
            var result=propAnimations.bindResult(player.getUUID(), prop.modelInstance(),
                java.nio.file.Path.of(prop.modelInstance().getModelDir()), pmx,
                motion -> {
                    var stageConfig=config.normalizedCopy();
                    stageConfig.heldPmxFile=pmx;
                    return com.shiroha.mmdskin.asset.catalog.PropTimelineCalibration.capture(modelName,stageConfig,motion,null);
                });
            String warning=propAnimations.takeWarning(player.getUUID());
            if(warning!=null && player==net.minecraft.client.Minecraft.getInstance().player) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("mmdskin.stage.prop_warning",warning),false);
            }
            if(!result.ready()) return false;
        }
        var state = assignment != null ? propAnimations.state(player.getUUID()) : PropTimeline.State.DEFAULT;
        var binding = state.attachment();

        // All geometry transforms and draw state are local to this prop.
        poseStack.pushPose();
        try {
            float ownerScale = character.modelInstance() instanceof com.shiroha.mmdskin.render.backend.BaseModelInstance base
                    ? base.getModelScale() : 0.09f * config.modelScale;
            boolean independent=ItemRenderHelper.usesIndependentPropSpace(assignment!=null,binding);
            var attachment = independent ? null : binding == null ? ItemRenderHelper.getPropAttachment(character, config.heldPmxBone)
                    : ItemRenderHelper.getTimelinePropAttachment(character, binding.bone());
            if (!independent && attachment == null) {
                if(binding!=null) propAnimations.warnMissingBone(player.getUUID(),binding.bone());
                return false;
            }
            var local = ItemRenderHelper.propRenderTransform(attachment, config,
                    binding == null ? PropTimeline.Offset.IDENTITY : binding.offset(),ownerScale,independent);
            poseStack.last().pose().mul(local);
            float propScale = prop.modelInstance() instanceof com.shiroha.mmdskin.render.backend.BaseModelInstance base
                    ? base.getModelScale() : 0.09f * config.modelScale;
            if(assignment!=null && !propAnimations.applyBoneParentsResult(player.getUUID(),character.modelInstance().getModelHandle(),
                    prop.modelInstance().getModelHandle(),new org.joml.Matrix4f(local).scale(propScale)).ready()) return false;
            ItemRenderHelper.syncPropPhysics(character, prop,
                    ItemRenderHelper.propPhysicsTransform(local, 1, ownerScale, propScale));

            // NULL entity avoids living-entity animation/IK state being applied to the prop.
            // ITEM scene avoids a second full character world render or player pose mutation.
            prop.modelInstance().render(null, 0.0f, 0.0f, new Vector3f(),
                    tickDelta, poseStack, packedLight, scene(state.visible(),config.heldPmxPhysicsEnabled));
            return true;
        } catch (RuntimeException e) {
            LOGGER.debug("PMX main-hand prop render failed for {}: {}", modelName, pmx, e);
            return false;
        } finally {
            poseStack.popPose();
        }
    }

    static RenderScene scene(boolean visible, boolean physics) {
        if (visible) return physics ? RenderScene.HELD_ITEM : RenderScene.HELD_ITEM_NO_PHYSICS;
        return physics ? RenderScene.HELD_ITEM_HIDDEN : RenderScene.HELD_ITEM_HIDDEN_NO_PHYSICS;
    }
}
