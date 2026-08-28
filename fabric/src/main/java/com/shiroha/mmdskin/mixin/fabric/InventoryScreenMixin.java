package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.player.render.InventoryRenderScope;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 将背包人物预览限制在明确的渲染调用作用域内 (Fabric 1.21.1)。 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {

    @Inject(method = "renderEntityInInventory(Lnet/minecraft/client/gui/GuiGraphics;FFFLorg/joml/Vector3f;Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/world/entity/LivingEntity;)V", at = @At("HEAD"), require = 0)
    private static void mmdskin$enterInventoryRender121(CallbackInfo callbackInfo) {
        InventoryRenderScope.enter();
    }

    @Inject(method = "renderEntityInInventory(Lnet/minecraft/client/gui/GuiGraphics;FFFLorg/joml/Vector3f;Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/world/entity/LivingEntity;)V", at = @At("RETURN"), require = 0)
    private static void mmdskin$exitInventoryRender121(CallbackInfo callbackInfo) {
        InventoryRenderScope.exit();
    }
}
