package com.shiroha.mmdskin.mixin.neoforge.compat.tacz;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.compat.tacz.TaczFirstPersonFrameSnapshot;
import com.shiroha.mmdskin.compat.tacz.TaczFirstPersonPostRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.tacz.guns.client.renderer.item.GunItemRendererWrapper", remap = false)
public abstract class TaczGunItemRendererWrapperMixin {
    @Inject(method = "renderFirstPerson(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V", at = @At("HEAD"), remap = false, require = 0)
    private void mmdskin$beginTaczFrame(LocalPlayer player, ItemStack stack, ItemDisplayContext context,
                                        PoseStack poseStack, MultiBufferSource buffers, int light, float partialTick,
                                        CallbackInfo ci) {
        TaczFirstPersonFrameSnapshot.beginFrame(player, stack, poseStack.last().pose(),
                Minecraft.getInstance().isSameThread());
        TaczFirstPersonPostRenderer.ensureDeferredAtTaczHead(player, stack, partialTick, light);
    }

    @Inject(method = "renderFirstPerson(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V", at = @At("RETURN"), remap = false, require = 0)
    private void mmdskin$finishTaczFrame(LocalPlayer player, ItemStack stack, ItemDisplayContext context,
                                         PoseStack poseStack, MultiBufferSource buffers, int light, float partialTick,
                                         CallbackInfo ci) {
        TaczFirstPersonPostRenderer.finish(TaczFirstPersonFrameSnapshot
                .finishFrame(Minecraft.getInstance().isSameThread()).orElse(null));
    }
}
