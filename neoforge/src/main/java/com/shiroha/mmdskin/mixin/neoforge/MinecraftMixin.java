package com.shiroha.mmdskin.mixin.neoforge;

import com.shiroha.mmdskin.stage.client.camera.MMDCameraController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft Mixin — 舞台模式下拦截暂停和游戏按键 (NeoForge 1.21.1)
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void releaseOutlineOnClose(CallbackInfo ci) {
        com.shiroha.mmdskin.render.outline.OutlineCoverage.release();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void releaseDisabledOutline(CallbackInfo ci) {
        if (!com.shiroha.mmdskin.config.ConfigManager.isToonRenderingEnabled()
                || !com.shiroha.mmdskin.config.ConfigManager.isToonOutlineEnabled()) {
            com.shiroha.mmdskin.render.outline.OutlineCoverage.release();
        }
    }

    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void onPauseGame(boolean showPauseMenu, CallbackInfo ci) {
        if (MMDCameraController.getInstance().isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private void onHandleKeybinds(CallbackInfo ci) {
        if (MMDCameraController.getInstance().shouldBlockInput()) {
            ci.cancel();
        }
    }
}
