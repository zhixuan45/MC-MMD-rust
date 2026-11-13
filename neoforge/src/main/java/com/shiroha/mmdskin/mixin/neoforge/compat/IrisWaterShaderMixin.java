package com.shiroha.mmdskin.mixin.neoforge.compat;

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
    @Inject(method = "patchSodium(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/gl/blending/AlphaTest;"
            + "Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;)Ljava/util/Map;",
            at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void mmdskin$waterSource(String name, String vertex, String geometry, String tessControl,
            String tessEval, String fragment, @Coerce Object alphaTest, Object2ObjectMap<?, ?> textures,
            CallbackInfoReturnable<Map<Object, String>> cir) {
        if (!"gbuffers_water".equals(name)) return;
        var logger = org.apache.logging.log4j.LogManager.getLogger();
        if (geometry != null || tessControl != null || tessEval != null) {
            logger.info("[MMD transparency] water capture rejected for {}: unsupported shader stages "
                    + "(geometry={}, tessControl={}, tessEval={})", name,
                    geometry != null, tessControl != null, tessEval != null);
            return;
        }
        var capabilities = GL.getCapabilities();
        if (capabilities == null || !capabilities.OpenGL43) {
            logger.info("[MMD transparency] water capture rejected for {}: OpenGL 4.3 is required", name);
            return;
        }
        try {
            var original = cir.getReturnValue();
            var diagnostic = IrisWaterShaderPatcher.diagnose(name, fragment, original);
            if (!diagnostic.accepted()) {
                logger.info("[MMD transparency] water capture rejected for {}: family={}, reason={}",
                        name, diagnostic.family(), diagnostic.reason());
                return;
            }
            var patched = IrisWaterShaderPatcher.patch(name, fragment, original);
            if (patched != original) {
                cir.setReturnValue(patched);
                logger.info("[MMD transparency] prepared {} water capture: {}",
                        diagnostic.family(), name);
            } else {
                logger.info("[MMD transparency] water capture rejected for {}: family={}, "
                        + "reason=adapter returned the original transformed source", name, diagnostic.family());
            }
        } catch (RuntimeException unavailable) {
            logger.warn("[MMD transparency] keeping native water shader for {}: adapter exception: {}",
                    name, unavailable.toString());
        }
    }
}
