package com.shiroha.mmdskin.player.runtime;

import com.shiroha.mmdskin.config.UIConstants;
import com.shiroha.mmdskin.player.animation.AnimationStateManager;
import com.shiroha.mmdskin.player.animation.PendingAnimSignalCache;
import com.shiroha.mmdskin.player.model.PlayerModelResolver;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.model.runtime.ModelInstance;
import com.shiroha.mmdskin.stage.client.sync.StageAnimSyncHelper;
import com.shiroha.mmdskin.player.sync.PlayerModelSyncService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 玩家动画控制辅助类。
 */
public final class MmdSkinRendererPlayerHelper {
    private static final float STAGE_TRANSITION_TIME = 0.3f;

    public static boolean isUsingMmdModel(Player player) {
        if (player == null) return false;
        String playerName = player.getName().getString();
        Minecraft mc = Minecraft.getInstance();
        boolean isLocalPlayer = mc.player != null && mc.player.getUUID().equals(player.getUUID());
        String selectedModel = PlayerModelSyncService.getPlayerModel(player.getUUID(), playerName, isLocalPlayer);
        return selectedModel != null && !selectedModel.isEmpty() && !selectedModel.equals(UIConstants.DEFAULT_MODEL_NAME);
    }

    private MmdSkinRendererPlayerHelper() {
    }

    public static void ResetPhysics(Player player) {
        PlayerModelResolver.Result resolved = PlayerModelResolver.resolve(player);
        if (resolved == null) return;

        resetModelAnimationState(player, resolved.model());
    }

    public static void CustomAnim(Player player, String id) {
        tryCustomAnim(player, id);
    }

    public static boolean tryCustomAnim(Player player, String id) {
        if (player == null || id == null || id.isEmpty()) return false;
        PlayerModelResolver.Result resolved = PlayerModelResolver.resolve(player);
        if (resolved == null) return false;

        ManagedModel managedModel = resolved.model();
        ModelInstance model = managedModel.modelInstance();
        if (id.equals(com.shiroha.mmdskin.asset.catalog.AnimationInfo.RESET_POSE_ACTION)) {
            return com.shiroha.mmdskin.expression.ExpressionApplicationService.apply(model.getModelHandle(),
                    com.shiroha.mmdskin.expression.ExpressionSelection.resetPose(), resolved.playerName());
        }
        if (id.startsWith(com.shiroha.mmdskin.asset.catalog.AnimationInfo.VPD_ACTION_PREFIX)) {
            var pose = com.shiroha.mmdskin.asset.catalog.AnimationInfo.findPoseAction(id, managedModel.requestKey().modelName());
            return pose != null && com.shiroha.mmdskin.expression.ExpressionApplicationService.apply(model.getModelHandle(),
                    com.shiroha.mmdskin.expression.ExpressionSelection.file(pose.getFilePath()), resolved.playerName());
        }
        com.shiroha.mmdskin.expression.ExpressionApplicationService.apply(model.getModelHandle(),
                com.shiroha.mmdskin.expression.ExpressionSelection.resetPose(), resolved.playerName());
        managedModel.entityState().playCustomAnim = true;

        managedModel.entityState().invalidateStateLayers();
        model.changeAnim(managedModel.animationLibrary().animation(id), 0);
        model.setLayerLoop(1, true);
        model.changeAnim(0, 1);
        model.changeAnim(0, 2);
        return true;
    }

    public static void startStageAnimation(ManagedModel modelData, long animHandle) {
        if (modelData == null || modelData.modelInstance() == null || modelData.entityState() == null || animHandle == 0) return;

        ModelInstance model = modelData.modelInstance();
        clearOverlayLayers(model);
        if (com.shiroha.mmdskin.config.ConfigManager.isStagePhysicsResetOnFirstFrame()) {
            model.resetPhysics();
        }
        modelData.entityState().invalidateStateLayers();
        model.transitionAnim(animHandle, 0, STAGE_TRANSITION_TIME);
        // 舞台帧由 StageAnimSyncHelper 外部 seek，禁止 native elapsed 再推进一次。
        model.setLayerSpeed(0, 0.0f);
        modelData.entityState().playCustomAnim = true;
        modelData.entityState().playStageAnim = true;
    }

    public static void resetModelAnimationState(ManagedModel modelData) {
        resetModelAnimationState(null, modelData);
    }

    public static void resetModelAnimationState(Player player, ManagedModel modelData) {
        if (modelData == null || modelData.modelInstance() == null || modelData.entityState() == null) return;

        ModelInstance model = modelData.modelInstance();
        model.setLayerSpeed(0, 1.0f);
        modelData.entityState().playCustomAnim = false;
        modelData.entityState().playStageAnim = false;
        model.changeAnim(modelData.animationLibrary().animation("idle"), 0);
        clearOverlayLayers(model);
        model.resetPhysics();
        modelData.entityState().invalidateStateLayers();

        if (player instanceof AbstractClientPlayer clientPlayer) {
            AnimationStateManager.updateAnimationState(clientPlayer, modelData);
        }
    }

    public static void suppressDefaultAnimationState(ManagedModel modelData) {
        if (modelData == null || modelData.modelInstance() == null || modelData.entityState() == null) return;

        ModelInstance model = modelData.modelInstance();
        model.setLayerSpeed(0, 1.0f);
        modelData.entityState().playCustomAnim = false;
        modelData.entityState().playStageAnim = false;
        model.changeAnim(0, 0);
        clearOverlayLayers(model);
        model.resetPhysics();
        modelData.entityState().invalidateStateLayers();
    }

    private static void clearOverlayLayers(ModelInstance model) {
        model.setLayerLoop(1, true);
        model.changeAnim(0, 1);
        model.changeAnim(0, 2);
    }

    public static void onDisconnect() {
        StageAnimSyncHelper.onDisconnect();
        PendingAnimSignalCache.onDisconnect();
    }
}
