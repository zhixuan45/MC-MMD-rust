package com.shiroha.mmdskin.mixin.neoforge.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.shiroha.mmdskin.compat.iris.IrisToonLifecycle;
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisToonLifecycleMixin {
    @Inject(method = "beginLevelRendering", at = @At("RETURN"), require = 0, remap = false)
    private void mmdskin$toonBegin(CallbackInfo ci) { IrisToonLifecycle.begin(this); }

    @Inject(method = "finalizeLevelRendering", at = @At("HEAD"), require = 0, remap = false)
    private void mmdskin$toonBeforeFinal(CallbackInfo ci) { IrisToonLifecycle.beforeFinal(this); }

    @Inject(method = "finalizeLevelRendering", at = @At("RETURN"), require = 0, remap = false)
    private void mmdskin$toonAfterFinal(CallbackInfo ci) { IrisToonLifecycle.afterFinal(this); }
}
