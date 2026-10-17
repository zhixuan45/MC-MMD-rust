package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.shiroha.mmdskin.compat.iris.IrisCompat;
import com.shiroha.mmdskin.neoforge.YsmCompat;
import com.shiroha.mmdskin.render.pipeline.WorldEntityEvaluationPrepass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicBoolean;

/** Iris 阴影视锥完成后、实体提交前准备 MMD 姿态。 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public abstract class IrisShadowEvaluationMixin {
    private static final AtomicBoolean HIT_LOGGED = new AtomicBoolean();

    @Inject(method = "renderEntities(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;FLnet/minecraft/client/renderer/culling/Frustum;DDD)I",
            at = @At("HEAD"), require = 0, remap = false)
    private void mmdskin$prepareShadowEntities(@Coerce Object levelAccessor,
            EntityRenderDispatcher dispatcher, MultiBufferSource.BufferSource buffers,
            com.mojang.blaze3d.vertex.PoseStack poseStack, float partialTick, Frustum frustum,
            double cameraX, double cameraY, double cameraZ,
            CallbackInfoReturnable<Integer> cir) {
        if (!HIT_LOGGED.getAndSet(true))
            org.apache.logging.log4j.LogManager.getLogger().info("[MMD] Iris 阴影求值入口已接入");
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float realDelta = IrisCompat.shadowRealTickDelta(partialTick);
        WorldEntityEvaluationPrepass.prepareShadow(mc.level.entitiesForRendering(), dispatcher, frustum,
                cameraX, cameraY, cameraZ, partialTick, realDelta, YsmCompat::isYsmModelActive,
                entity -> com.shiroha.mmdskin.neoforge.maid.MaidRenderEventHandler.isTouhouLittleMaidLoaded()
                        && com.shiroha.mmdskin.compat.maid.render.MaidRenderOwnership.isVanillaRendererPath(
                                entity, dispatcher.getRenderer(entity)),
                (entity, tick) -> entity.getYRot());
    }
}
