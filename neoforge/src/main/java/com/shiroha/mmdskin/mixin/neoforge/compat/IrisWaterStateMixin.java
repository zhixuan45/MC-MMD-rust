package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyPipeline;
import com.shiroha.mmdskin.render.pipeline.transparency.WorldWaterCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pair native water setup/reset with a bounded shared-list and primary-blend scope. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.SodiumShader", remap = false)
public abstract class IrisWaterStateMixin {
    @Inject(method = "setupState", at = @At("HEAD"), require = 0, remap = false)
    private void mmdskin$closePreviousWater(CallbackInfo ci) {
        WorldWaterCapture.endActive();
    }

    @Inject(method = "setupState", at = @At("RETURN"), require = 0, remap = false)
    private void mmdskin$beginWater(CallbackInfo ci) {
        WorldTransparencyPipeline.beginWaterCapture();
    }

    @Inject(method = "resetState", at = @At("HEAD"), require = 0, remap = false)
    private void mmdskin$endWater(CallbackInfo ci) {
        WorldTransparencyPipeline.afterWaterCapture();
    }
}
