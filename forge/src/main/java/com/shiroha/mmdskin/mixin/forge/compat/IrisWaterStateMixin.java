package com.shiroha.mmdskin.mixin.forge.compat;

import com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyPipeline;
import com.shiroha.mmdskin.render.pipeline.transparency.WorldWaterCapture;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pair native water setup/reset with a bounded shared-list and primary-blend scope. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ExtendedShader", remap = false)
public abstract class IrisWaterStateMixin {
    @Inject(method = {"apply", "m_173363_"}, at = @At("HEAD"), require = 0, remap = false)
    private void mmdskin$closePreviousWater(CallbackInfo ci) {
        if (mmdskin$isWaterShader()) {
            WorldWaterCapture.endActive();
        }
    }

    @Inject(method = {"apply", "m_173363_"}, at = @At("RETURN"), require = 0, remap = false)
    private void mmdskin$beginWater(CallbackInfo ci) {
        if (mmdskin$isWaterShader()) {
            WorldTransparencyPipeline.beginWaterCapture();
        }
    }

    @Inject(method = {"clear", "m_173362_"}, at = @At("HEAD"), require = 0, remap = false)
    private void mmdskin$endWater(CallbackInfo ci) {
        if (mmdskin$isWaterShader()) {
            WorldTransparencyPipeline.afterWaterCapture();
        }
    }

    private boolean mmdskin$isWaterShader() {
        String shaderName = ((ShaderInstance) (Object) this).getName();
        return shaderName != null
                && (shaderName.contains("gbuffers_water") || "rendertype_translucent".equals(shaderName));
    }
}
