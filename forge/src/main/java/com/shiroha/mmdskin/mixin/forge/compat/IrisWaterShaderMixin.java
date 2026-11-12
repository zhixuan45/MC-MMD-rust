package com.shiroha.mmdskin.mixin.forge.compat;

import com.shiroha.mmdskin.compat.iris.IrisWaterShaderPatcher;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.lwjgl.opengl.GL;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Map;

/** Optional Iris shader-source adapter; no terrain renderer internals are intercepted. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
public abstract class IrisWaterShaderMixin {
    @Inject(method = "patchSodium(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/gl/blending/AlphaTest;Lnet/irisshaders/iris/gl/state/ShaderAttributeInputs;Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;)Ljava/util/Map;",
            at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void mmdskin$waterSource(String name, String vertex, String geometry, String tessControl,
            String tessEval, String fragment, @Coerce Object alphaTest, @Coerce Object shaderAttributes,
            Object2ObjectMap<?, ?> textures,
            CallbackInfoReturnable<Map<Object, String>> cir) {
        if (!"gbuffers_water".equals(name)) return;
        try {
            if (!GL.getCapabilities().OpenGL43 || geometry != null || tessControl != null || tessEval != null) return;
            var original = cir.getReturnValue();
            var patched = IrisWaterShaderPatcher.patch(name, fragment, original);
            if (patched != original) {
                cir.setReturnValue(patched);
                org.apache.logging.log4j.LogManager.getLogger().info(
                        "[MMD transparency] prepared verified Iris water shader capture: {}", name);
            } else {
                org.apache.logging.log4j.LogManager.getLogger().info(
                        "[MMD transparency] kept native water shader: unverified Iris contract for {}", name);
            }
        } catch (RuntimeException unavailable) {
            org.apache.logging.log4j.LogManager.getLogger().warn(
                    "[MMD transparency] keeping native water shader: {}", unavailable.toString());
        }
    }
}
