package com.shiroha.mmdskin.mixin.fabric.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.shiroha.mmdskin.render.pipeline.toon.ToonOffscreenRenderer;
@Pseudo
@Mixin(targets = {"net.irisshaders.iris.pipeline.programs.ExtendedShader",
        "net.irisshaders.iris.pipeline.programs.FallbackShader"}, remap = false)
public abstract class IrisToonShaderObserverMixin {
    // ShaderInstance.apply is method_34586 in the published 1.21.1 Fabric jar.
    @Inject(method = {"apply", "method_34586"}, at = @At("RETURN"), require = 0, remap = false)
    private void mmdskin$toonObserve(CallbackInfo ci) { ToonOffscreenRenderer.observeShader(this); }
}
