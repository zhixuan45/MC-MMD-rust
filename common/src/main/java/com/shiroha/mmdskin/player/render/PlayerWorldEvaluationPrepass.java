package com.shiroha.mmdskin.player.render;

import com.shiroha.mmdskin.compat.iris.IrisCompat;
import com.shiroha.mmdskin.compat.tacz.TaczGunDetector;
import com.shiroha.mmdskin.model.runtime.ModelRequestKey;
import com.shiroha.mmdskin.player.animation.AnimationStateManager;
import com.shiroha.mmdskin.player.runtime.FirstPersonManager;
import com.shiroha.mmdskin.render.backend.BaseModelInstance;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.render.pipeline.NativeEvaluationBatch;
import com.shiroha.mmdskin.render.pipeline.RenderPerformanceProfiler;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.Predicate;

/** Runs after camera/frustum preparation, before the world entity draw loop.
 * Entity reads, animation transitions and JNI input writes finish before any worker starts.
 * Local, stage, external-pose, VR, TaCZ and shader-pack instances keep their synchronous path.
 */
public final class PlayerWorldEvaluationPrepass {
    private PlayerWorldEvaluationPrepass() {}
    public static void prepare(Camera camera, Frustum frustum, float tickDelta,
                               Predicate<BlockPos> sectionCompiled, Predicate<AbstractClientPlayer> ysmActive) {
        if (!NativeEvaluationBatch.enabled() || frustum == null || IrisCompat.isRenderingShadows()
                || IrisCompat.isIrisShaderActive() || FirstPersonManager.vrRuntime().isLocalPlayerInVr()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        var jobs = new ArrayList<NativeEvaluationBatch.Work>();
        var handles = new HashSet<Long>();
        var position = camera.getPosition();
        long timer = RenderPerformanceProfiler.get().startTimer();
        for (AbstractClientPlayer player : mc.level.players()) {
            if (player == mc.player || player == camera.getEntity() || player.tickCount == 0
                    || player.isSpectator() || ysmActive.test(player)
                    || TaczGunDetector.isGun(player.getMainHandItem())) continue;
            if (!mc.getEntityRenderDispatcher().shouldRender(player, frustum, position.x, position.y, position.z)
                    && !player.hasIndirectPassenger(mc.player)) continue;
            if (!mc.level.isOutsideBuildHeight(player.blockPosition().getY()) && !sectionCompiled.test(player.blockPosition())) continue;
            var selection = PlayerRenderSelectionResolver.resolve(player, false);
            if (selection.hasTerminalAction()) continue;
            var key = ModelRequestKey.player(player, selection.selectedModel());
            var data = ClientRenderRuntime.get().modelRepository().peek(key);
            if (data == null || data.entityState().playStageAnim
                    || !(data.modelInstance() instanceof BaseModelInstance model)
                    || !model.canPrepareWorldEvaluation() || !handles.add(model.getModelHandle())) continue;
            AnimationStateManager.updateAnimationState(player, data);
            PlayerModelRenderCoordinator.consumePendingSignals(player, data, false);
            var pose = PlayerRenderHelper.calculateMutableRenderPose(player, data, tickDelta);
            var work = model.prepareWorldEvaluation(player, pose.bodyYaw, tickDelta);
            if (work != null) jobs.add(work);
        }
        var profiler = RenderPerformanceProfiler.get();
        profiler.endTimer("native.batchPrepare", timer);
        timer = profiler.startTimer();
        NativeEvaluationBatch.run(jobs);
        profiler.endTimer("native.batchExecuteJoin", timer);
        profiler.recordParallelBatch(jobs.size());
    }
}
