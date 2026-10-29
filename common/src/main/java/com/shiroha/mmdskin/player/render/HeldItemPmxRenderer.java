package com.shiroha.mmdskin.player.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.asset.catalog.ModelCatalogEntry;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.config.ModelConfigManager;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.model.runtime.ModelRequestKey;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.render.scene.RenderScene;
import net.minecraft.client.player.AbstractClientPlayer;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Renders an independent PMX prop in the character's selected evaluated bone space.
 * A missing, pending or failed prop never suppresses the normal Minecraft item.
 */
final class HeldItemPmxRenderer {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final float RAD_PER_DEGREE = (float) Math.PI / 180.0f;

    private HeldItemPmxRenderer() { }

    /**
     * @return true only if a ready prop model was submitted for rendering.
     */
    static boolean renderMainHand(AbstractClientPlayer player, ManagedModel character,
                                  PoseStack poseStack, int packedLight, float tickDelta) {
        if (player.getMainHandItem().isEmpty()) return false;

        String modelName = character.requestKey().modelName();
        ModelConfigData config = ModelConfigManager.getConfig(modelName);
        var stageRuntime = com.shiroha.mmdskin.stage.client.StageClientRuntime.peek();
        var propAnimations = stageRuntime != null ? stageRuntime.animSyncHelper().propAnimations() : null;
        boolean inStage = character.entityState().playStageAnim;
        var assignment = inStage && propAnimations != null ? propAnimations.assignment(player.getUUID()) : null;
        String pmx = assignment != null ? assignment.pmxFile() : config.heldPmxFile;
        if (pmx == null || pmx.isEmpty()) return false;

        // Restrict the separately loaded prop to an existing PMX in this character folder.
        if (!ModelCatalogEntry.listPmxFiles(modelName).contains(pmx)) return false;

        ModelRequestKey propKey = ModelRequestKey.heldItem(player, modelName, pmx);
        ManagedModel prop = ClientRenderRuntime.get().modelRepository().acquire(propKey);
        if (prop == null || prop.modelInstance().getModelHandle() == 0L) return false;

        if (inStage && propAnimations != null) propAnimations.bind(player.getUUID(), prop.modelInstance(), java.nio.file.Path.of(prop.modelInstance().getModelDir()), pmx);
        else if (propAnimations != null) propAnimations.stop(player.getUUID());

        // All geometry transforms and draw state are local to this prop.
        poseStack.pushPose();
        try {
            var attachment = ItemRenderHelper.getPropAttachment(character, config.heldPmxBone);
            poseStack.last().pose().mul(attachment);
            poseStack.translate(config.heldPmxOffsetX, config.heldPmxOffsetY, config.heldPmxOffsetZ);
            poseStack.mulPose(new Quaternionf().rotationXYZ(
                    config.heldPmxRotX * RAD_PER_DEGREE,
                    config.heldPmxRotY * RAD_PER_DEGREE,
                    config.heldPmxRotZ * RAD_PER_DEGREE));
            // The character stack already has its MMD world scale; the prop
            // renderer applies MMD scale once more. Like the vanilla item path,
            // compensate with a 10x hand-space factor before the prop render.
            float scale = 10.0f * config.heldItemScale * config.heldPmxScale;
            poseStack.scale(scale, scale, scale);

            float ownerScale = character.modelInstance() instanceof com.shiroha.mmdskin.render.backend.BaseModelInstance base
                    ? base.getModelScale() : 0.09f * config.modelScale;
            float propScale = prop.modelInstance() instanceof com.shiroha.mmdskin.render.backend.BaseModelInstance base
                    ? base.getModelScale() : 0.09f * config.modelScale;
            ItemRenderHelper.syncPropPhysics(character, prop,
                    ItemRenderHelper.propPhysicsTransform(attachment, config, ownerScale, propScale));

            // NULL entity avoids living-entity animation/IK state being applied to the prop.
            // ITEM scene avoids a second full character world render or player pose mutation.
            prop.modelInstance().render(null, 0.0f, 0.0f, new Vector3f(),
                    tickDelta, poseStack, packedLight, config.heldPmxPhysicsEnabled ? RenderScene.HELD_ITEM : RenderScene.HELD_ITEM_NO_PHYSICS);
            return true;
        } catch (RuntimeException e) {
            LOGGER.debug("PMX main-hand prop render failed for {}: {}", modelName, pmx, e);
            return false;
        } finally {
            poseStack.popPose();
        }
    }
}
