package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.compat.vr.VRArmHider;
import com.shiroha.mmdskin.compat.vr.VRHandRenderer;
import com.shiroha.mmdskin.compat.tacz.TaczGunDetector;
import com.shiroha.mmdskin.fabric.YsmCompat;
import com.shiroha.mmdskin.player.runtime.FirstPersonManager;
import com.shiroha.mmdskin.player.sync.PlayerModelSyncService;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ItemInHandRenderer Mixin — 第一人称手臂隐藏 + VR 手部渲染
 */
@Mixin(value = ItemInHandRenderer.class, priority = 900)
public abstract class ItemInHandRendererMixin {

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void onRenderHandsWithItems(float partialTick, PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource, LocalPlayer player, int packedLight,
            CallbackInfo ci) {
        if (VRArmHider.shouldHideVRArms()) {
            ci.cancel();
            return;
        }

        String playerName = player.getName().getString();
        String selectedModel = PlayerModelSyncService.getPlayerModel(player.getUUID(), playerName, true);

        boolean isMmdDefault = selectedModel == null || selectedModel.isEmpty() || selectedModel.equals("默认 (原版渲染)");
        boolean isMmdActive = !isMmdDefault;
        boolean isVanilaMmdModel = isMmdActive && (selectedModel.equals("VanilaModel") || selectedModel.equalsIgnoreCase("vanila"));

        if (YsmCompat.isYsmModelActive(player)) {
            if (YsmCompat.isDisableSelfHands()) {
                ci.cancel();
            }
            return;
        }

        // 入水/游泳状态判定：在使用 MMD 皮肤时，第一人称入水游泳彻底隐藏手部渲染，防止原版手臂穿插
        if ((player.isSwimming() || player.isVisuallySwimming()) && isMmdActive && !isVanilaMmdModel) {
            ci.cancel();
            return;
        }

        // TaCZ 必须继续执行原生第一人称枪模流程，才能使用每把枪和瞄具的数据驱动 ADS 节点。
        boolean taczMainHand = TaczGunDetector.isGun(player.getMainHandItem());
        if (FirstPersonManager.shouldRenderFirstPerson() && isMmdActive && !isVanilaMmdModel
                && !taczMainHand) {
            ci.cancel();
        }
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void onRenderArmWithItem(AbstractClientPlayer player, float partialTick,
            float pitch, InteractionHand hand, float swingProgress, ItemStack itemStack,
            float equippedProgress, PoseStack poseStack, MultiBufferSource buffer,
            int combinedLight, CallbackInfo ci) {
        if (VRArmHider.shouldHideVRArms()) {

            VRHandRenderer.renderHandItem(poseStack, buffer, combinedLight, hand);
            ci.cancel();
            return;
        }
        if (hand == InteractionHand.OFF_HAND
                && FirstPersonManager.shouldRenderFirstPerson()
                && TaczGunDetector.isGun(player.getMainHandItem())) {
            // 放行 TaCZ 主手枪模时仍由 MMD 绘制副手，避免副手物品出现两份。
            ci.cancel();
        }
    }

    @Inject(method = "renderPlayerArm", at = @At("HEAD"), cancellable = true)
    private void onRenderPlayerArm(PoseStack poseStack, MultiBufferSource buffer,
            int combinedLight, float equippedProgress, float swingProgress,
            HumanoidArm side, CallbackInfo ci) {
        if (VRArmHider.shouldHideVRArms()) {
            ci.cancel();
            return;
        }
        LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) {
            String selectedModel = PlayerModelSyncService.getPlayerModel(player.getUUID(), player.getName().getString(), true);
            boolean isMmdActive = selectedModel != null && !selectedModel.isEmpty() && !selectedModel.equals("默认 (原版渲染)");
            boolean isVanilaMmdModel = isMmdActive && (selectedModel.equals("VanilaModel") || selectedModel.equalsIgnoreCase("vanila"));
            if (isMmdActive && !isVanilaMmdModel) {
                if (player.isSwimming() || player.isVisuallySwimming()
                        || (FirstPersonManager.shouldRenderFirstPerson() && TaczGunDetector.isGun(player.getMainHandItem()))) {
                    // 游泳入水时或 TaCZ 枪模下隐藏原版手臂
                    ci.cancel();
                }
            }
        }
    }
}

