/* 文件职责：协调玩家模型在普通视角、第一人称与 VR 场景中的渲染切换。 */
package com.shiroha.mmdskin.player.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.compat.iris.IrisCompat;
import com.shiroha.mmdskin.compat.tacz.TaczFirstPersonPostRenderer;
import com.shiroha.mmdskin.compat.tacz.TaczGunDetector;
import com.shiroha.mmdskin.config.ModelConfigManager;
import com.shiroha.mmdskin.config.RuntimeConfigPortHolder;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.player.animation.AnimationStateManager;
import com.shiroha.mmdskin.player.animation.PendingAnimSignalCache;
import com.shiroha.mmdskin.player.port.VrRuntimePort;
import com.shiroha.mmdskin.player.runtime.FirstPersonManager;
import com.shiroha.mmdskin.player.runtime.MmdSkinRendererPlayerHelper;
import com.shiroha.mmdskin.model.runtime.ModelInstance;
import com.shiroha.mmdskin.render.scene.RenderScene;
import com.shiroha.mmdskin.render.scene.MutableRenderPose;
import com.shiroha.mmdskin.render.backend.BaseModelInstance;
import com.shiroha.mmdskin.render.effect.PelvisVeilMode;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;

/** 文件职责：协调玩家模型在普通视角、第一人称与 VR 场景中的渲染切换。 */
final class PlayerModelRenderCoordinator {

    private PlayerModelRenderCoordinator() {
    }

    static PlayerRenderAction render(PlayerRenderSelection selection,
                                                    AbstractClientPlayer player,
                                                   float entityYaw,
                                                   float tickDelta,
                                                   PoseStack matrixStack,
                                                   MultiBufferSource vertexConsumers,
                                                   int packedLight,
                                                   ManagedModel modelData) {
        ModelInstance model = modelData.modelInstance();
        VrRuntimePort vrRuntime = FirstPersonManager.vrRuntime();

        float[] size = PlayerRenderHelper.getModelSize(modelData);
        boolean isVr = selection.isLocalPlayer() && vrRuntime.isLocalPlayerInVr();
        boolean shadowPass = IrisCompat.isRenderingShadows();
        boolean inventoryRender = selection.isLocalPlayer()
                && !shadowPass
                && (InventoryRenderScope.isActive() || PaperDollRenderScope.isActive());
        syncVrState(modelData, player, tickDelta, isVr, vrRuntime);

        ModelConfigData modelConfig = ModelConfigManager.getConfig(selection.selectedModel());
        float combinedScale = size[0] * modelConfig.modelScale;
        long modelHandle = model.getModelHandle();
        if (selection.isLocalPlayer()) {
            FirstPersonManager.preRender(modelHandle, combinedScale, true);
        }
        boolean firstPersonView = selection.isLocalPlayer()
                && !inventoryRender
                && !shadowPass
                && ((!isVr && FirstPersonManager.isActive())
                || (isVr && FirstPersonManager.isVrEyeCameraActive()));
        boolean reusePreparedFirstPersonPose = !isVr
                && selection.isLocalPlayer()
                && !inventoryRender
                && model instanceof BaseModelInstance baseModel
                && baseModel.hasPreparedFirstPersonPose();

        // 相机预更新已经刷新过动画状态，正式 pass 只复用同一姿态。
        if (!isVr && !reusePreparedFirstPersonPose) {
            AnimationStateManager.updateAnimationState(player, modelData);
        }
        consumePendingSignals(player, modelData, selection.isLocalPlayer());

        MutableRenderPose params = PlayerRenderHelper.calculateMutableRenderPose(player, modelData, tickDelta);
        boolean needsPostRenderSync = selection.isLocalPlayer() && !inventoryRender && !shadowPass;
        boolean deferTaczFirstPerson = firstPersonView
                && !isVr
                && reusePreparedFirstPersonPose
                && TaczGunDetector.isGun(player.getMainHandItem())
                && model instanceof BaseModelInstance;

        // 防走光与游泳状态判定（针对本地玩家）
        AntiPeekEvaluator.Result antiPeek = AntiPeekEvaluator.evaluate(
                player,
                selection.isLocalPlayer(),
                firstPersonView,
                inventoryRender,
                shadowPass);

        if (antiPeek.fullyHidden()) {
            if (needsPostRenderSync) {
                FirstPersonManager.postRender(modelHandle, player, tickDelta);
            }
            return PlayerRenderAction.CANCEL;
        }

        if (model instanceof BaseModelInstance baseModel) {
            baseModel.setGlobalAlpha(antiPeek.alpha());
            // 阴影与背包/纸娃娃 pass 不改写世界通道的骨盆遮罩平滑状态。
            if (!shadowPass && !inventoryRender) {
                PelvisVeilMode veilMode = selection.isLocalPlayer() ? antiPeek.veilMode() : PelvisVeilMode.NONE;
                float seconds = (player.tickCount + tickDelta) / 20.0f;
                applyPelvisVeilTarget(baseModel, player, veilMode, seconds);
            }
        }

        matrixStack.pushPose();
        try {
            if (inventoryRender) {
                InventoryRenderHelper.renderInInventory(player, model, tickDelta, matrixStack, packedLight, size);
            } else {
                if (deferTaczFirstPerson) {
                    // 枪械先由 TaCZ 完整绘制，MMD 在其 RETURN 后使用同帧手部锚点后置绘制。
                    TaczFirstPersonPostRenderer.defer(player, player.getMainHandItem(), modelData,
                            (BaseModelInstance) model, params, matrixStack, tickDelta, packedLight, size[0]);
                    needsPostRenderSync = false;
                } else {
                    TaczFirstPersonPostRenderer.clearDeferredDraw();
                    RenderSystem.setShader(GameRenderer::getRendertypeEntityTranslucentShader);
                    RenderScene context = firstPersonView ? RenderScene.FIRST_PERSON : RenderScene.WORLD;
                    model.render(player, params.bodyYaw, params.bodyPitch, params.translation, tickDelta, matrixStack, packedLight, context);
                }
            }

            if (needsPostRenderSync) {
                FirstPersonManager.postRender(modelHandle, player, tickDelta);
                needsPostRenderSync = false;
            }

            ItemRenderHelper.renderItems(
                    player,
                    modelData,
                    matrixStack,
                    vertexConsumers,
                    packedLight,
                    modelConfig.heldItemScale,
                    tickDelta,
                    size[0]);
            return PlayerRenderAction.CANCEL;
        } finally {
            try {
                if (needsPostRenderSync) {
                    FirstPersonManager.postRender(modelHandle, player, tickDelta);
                }
            } finally {
                matrixStack.popPose();
            }
        }
    }

    private static void applyPelvisVeilTarget(BaseModelInstance baseModel,
                                              AbstractClientPlayer player,
                                              PelvisVeilMode mode,
                                              float seconds) {
        if (mode == PelvisVeilMode.ELYTRA_WIND) {
            baseModel.setPelvisVeil(mode, seconds, 0.74f, 0.92f, 0.99f);
            return;
        }
        if (mode == PelvisVeilMode.SWIMMING_WATER) {
            float r = 0.26f, g = 0.72f, b = 0.88f;
            if (player != null && player.level() != null) {
                int waterColor = BiomeColors.getAverageWaterColor(player.level(), player.blockPosition());
                float biomeR = ((waterColor >> 16) & 0xFF) / 255.0f;
                float biomeG = ((waterColor >> 8) & 0xFF) / 255.0f;
                float biomeB = (waterColor & 0xFF) / 255.0f;
                // 40% 融合当前生物群系水色，60% 保留清透水蓝，防止暗色沼泽群系显得浑浊。
                r = r * 0.60f + biomeR * 0.40f;
                g = g * 0.60f + biomeG * 0.40f;
                b = b * 0.60f + biomeB * 0.40f;
            }
            baseModel.setPelvisVeil(mode, seconds, r, g, b);
            return;
        }
        baseModel.setPelvisVeil(PelvisVeilMode.NONE, seconds, 0.26f, 0.72f, 0.88f);
    }

    private static void syncVrState(ManagedModel modelData,
                                    AbstractClientPlayer player,
                                    float tickDelta,
                                    boolean isVr,
                                    VrRuntimePort vrRuntime) {
        ModelInstance model = modelData.modelInstance();
        if (!(model instanceof BaseModelInstance abstractModel)) {
            return;
        }

        if (isVr) {
            vrRuntime.applyMmdRenderState(true);
            if (!abstractModel.isVrActive()) {
                MmdSkinRendererPlayerHelper.suppressDefaultAnimationState(modelData);
                vrRuntime.setModelVrEnabled(model.getModelHandle(), true);
                abstractModel.setVrActive(true);
            }
            vrRuntime.updateModelVr(model.getModelHandle(), player, tickDelta, RuntimeConfigPortHolder.get().getVrArmIkStrength());
            return;
        }

        vrRuntime.applyMmdRenderState(false);
        if (abstractModel.isVrActive()) {
            vrRuntime.setModelVrEnabled(model.getModelHandle(), false);
            abstractModel.setVrActive(false);
            MmdSkinRendererPlayerHelper.resetModelAnimationState(player, modelData);
        }
    }

    static void consumePendingSignals(AbstractClientPlayer player,
                                              ManagedModel modelData,
                                              boolean isLocalPlayer) {
        if (isLocalPlayer) {
            return;
        }

        PendingAnimSignalCache.SignalType signal = PendingAnimSignalCache.consume(player.getUUID());
        if (signal == PendingAnimSignalCache.SignalType.RESET) {
            MmdSkinRendererPlayerHelper.ResetPhysics(player);
        }
    }
}
