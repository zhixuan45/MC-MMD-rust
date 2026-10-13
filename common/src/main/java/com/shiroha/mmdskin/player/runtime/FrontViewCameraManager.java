package com.shiroha.mmdskin.player.runtime;

import com.shiroha.mmdskin.bridge.runtime.NativeModelPort;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.player.animation.AnimationStateManager;
import com.shiroha.mmdskin.player.model.PlayerModelResolver;
import com.shiroha.mmdskin.player.render.InventoryRenderHelper;
import com.shiroha.mmdskin.player.render.PlayerRenderHelper;
import com.shiroha.mmdskin.render.backend.BaseModelInstance;
import com.shiroha.mmdskin.render.scene.MutableRenderPose;
import com.shiroha.mmdskin.render.scene.RenderScene;
import com.shiroha.mmdskin.stage.client.camera.MMDCameraController;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** 维护 F5 前视相机当前帧的模型眼高。 */
public final class FrontViewCameraManager {
    private static final FrontViewCameraSnapshot SNAPSHOT = new FrontViewCameraSnapshot();
    private static BaseModelInstance preparedModel;

    private FrontViewCameraManager() {}

    /** 在相机设置前，复用本帧 WORLD 姿态计算最终蒙皮双眼锚点。 */
    public static void prepare(float partialTick, NativeModelPort modelPort) {
        Minecraft minecraft = Minecraft.getInstance();
        AbstractClientPlayer player = minecraft.player;
        if (preparedModel != null || !isEligible(minecraft, player) || modelPort == null) return;

        BaseModelInstance baseModel = null;
        try {
            PlayerModelResolver.Result resolved = PlayerModelResolver.resolve(player);
            if (resolved == null || !(resolved.model().modelInstance() instanceof BaseModelInstance model)) return;
            baseModel = model;
            long modelHandle = model.getModelHandle();
            if (modelHandle == 0L || model.isVrActive()) return;

            float tickDelta = Float.isFinite(partialTick) ? Mth.clamp(partialTick, 0.0f, 1.0f) : 0.0f;
            ManagedModel managedModel = resolved.model();
            AnimationStateManager.updateAnimationState(player, managedModel);
            MutableRenderPose pose = PlayerRenderHelper.calculateMutableRenderPose(player, managedModel, tickDelta);
            if (!model.prepareCameraPose(player, pose.bodyYaw, tickDelta, RenderScene.WORLD)) return;

            // 无效锚点也保留已准备姿态，避免后续世界渲染再次推进动画。
            preparedModel = model;
            float[] eye = new float[3];
            modelPort.getFirstPersonCameraAnchorPosition(modelHandle, eye);
            double baseY = Mth.lerp(tickDelta, player.yo, player.getY());
            SNAPSHOT.capture(player, minecraft.getCameraEntity(),
                    FrontViewCameraSnapshot.worldHeight(baseY, eye, model.getModelScale()));
        } catch (RuntimeException | LinkageError ignored) {
            if (baseModel != null && baseModel.hasPreparedFirstPersonPose()) preparedModel = baseModel;
            SNAPSHOT.clear();
        }
    }

    /** 丢弃上一帧预求值和相机高度。 */
    public static void clear() {
        BaseModelInstance previous = preparedModel;
        preparedModel = null;
        SNAPSHOT.clear();
        if (previous != null) previous.discardPreparedFirstPersonPose();
    }

    /** 返回只对当前本地玩家和 F5 前视有效的世界眼高。 */
    public static Double getPivotHeight(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || entity != player
                || minecraft.getCameraEntity() != player
                || minecraft.options.getCameraType() != CameraType.THIRD_PERSON_FRONT) return null;
        return SNAPSHOT.get(entity, player, minecraft.getCameraEntity());
    }

    private static boolean isEligible(Minecraft minecraft, Player player) {
        if (player == null || minecraft.getCameraEntity() != player
                || minecraft.options.getCameraType() != CameraType.THIRD_PERSON_FRONT
                || FirstPersonManager.vrRuntime().isLocalPlayerInVr()
                || InventoryRenderHelper.isInventoryScreen()
                || MMDCameraController.getInstance().isActive()) return false;
        return !player.isSwimming() && !player.isVisuallySwimming() && !player.isFallFlying()
                && !player.isSleeping() && !player.isPassenger() && !player.isVisuallyCrawling();
    }
}
