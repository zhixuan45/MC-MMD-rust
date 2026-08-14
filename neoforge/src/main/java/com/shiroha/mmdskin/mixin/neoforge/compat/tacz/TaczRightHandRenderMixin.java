package com.shiroha.mmdskin.mixin.neoforge.compat.tacz;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.shiroha.mmdskin.compat.tacz.TaczFirstPersonFrameSnapshot;
import com.shiroha.mmdskin.compat.tacz.TaczOriginalArmFallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.tacz.guns.client.model.functional.RightHandRender", remap = false)
public abstract class TaczRightHandRenderMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/item/ItemDisplayContext;II)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V", shift = At.Shift.AFTER, remap = true), remap = false, require = 0)
    private void mmdskin$captureFinalRightMatrix(PoseStack poseStack, VertexConsumer vertexBuffer,
                                                 ItemDisplayContext transformType, int light, int overlay,
                                                 CallbackInfo ci) {
        if (transformType.firstPerson()) {
            TaczFirstPersonFrameSnapshot.captureHand(Minecraft.getInstance().player,
                    TaczFirstPersonFrameSnapshot.Hand.RIGHT, poseStack.last().pose(), poseStack.last().normal(),
                    Minecraft.getInstance().isSameThread());
        }
    }

    @Redirect(method = "lambda$render$0(Lorg/joml/Matrix3f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/item/ItemDisplayContext;II)V", at = @At(value = "INVOKE", target = "Lcom/tacz/guns/util/RenderHelper;renderFirstPersonArm(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;I)V"), remap = false, require = 0)
    private static void mmdskin$cancelOriginalRightArm(LocalPlayer player, HumanoidArm hand, PoseStack poseStack, int light) {
        if (!TaczFirstPersonFrameSnapshot.shouldSuppressOriginalArm(player,
                TaczFirstPersonFrameSnapshot.Hand.RIGHT, Minecraft.getInstance().isSameThread())) {
            TaczOriginalArmFallback.render(player, hand, poseStack, light);
        }
    }
}
