package com.shiroha.mmdskin.render.pipeline;

import com.shiroha.mmdskin.compat.maid.render.MaidRenderPreparation;
import com.shiroha.mmdskin.compat.maid.render.MaidRenderOwnership;
import com.shiroha.mmdskin.compat.maid.runtime.MaidMMDModelManager;
import com.shiroha.mmdskin.compat.tacz.TaczGunDetector;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.model.runtime.ModelRequestKey;
import com.shiroha.mmdskin.player.runtime.FirstPersonManager;
import com.shiroha.mmdskin.player.render.PlayerEvaluationAdapter;
import com.shiroha.mmdskin.render.backend.BaseModelInstance;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.render.entity.EntityAnimationResolver;
import com.shiroha.mmdskin.render.entity.MobReplacementService;
import com.shiroha.mmdskin.render.scene.MutableRenderPose;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** 收集当前 pass 首次需要姿态的替换实体，所有实体访问都留在渲染线程。 */
public final class WorldEntityEvaluationPrepass {
    @FunctionalInterface
    public interface MaidEligibility {
        boolean canReplace(LivingEntity entity);
    }

    @FunctionalInterface
    public interface MaidYawResolver {
        float renderYaw(LivingEntity entity, float tickDelta);
    }

    private record Candidate(Entity entity, ManagedModel data, BaseModelInstance model,
                             Kind kind, float yaw, float tickDelta) {}
    private enum Kind { PLAYER, MOB, MAID }

    private WorldEntityEvaluationPrepass() {}

    public static void prepareWorld(Camera camera, Frustum frustum, DeltaTracker delta,
            Predicate<BlockPos> sectionCompiled, Predicate<AbstractClientPlayer> ysmActive,
            MaidEligibility maidEligibility) {
        prepareWorld(camera, frustum, delta, sectionCompiled, ysmActive, maidEligibility,
                (entity, tick) -> net.minecraft.util.Mth.lerp(tick, entity.yRotO, entity.getYRot()));
    }

    public static void prepareWorld(Camera camera, Frustum frustum, DeltaTracker delta,
            Predicate<BlockPos> sectionCompiled, Predicate<AbstractClientPlayer> ysmActive,
            MaidEligibility maidEligibility, MaidYawResolver maidYawResolver) {
        if (!NativeEvaluationBatch.enabled() || frustum == null || IrisCompatBridge.shadows()
                || FirstPersonManager.vrRuntime().isLocalPlayerInVr()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        var position = camera.getPosition();
        List<Candidate> candidates = new ArrayList<>();
        Set<java.util.UUID> entityIds = new HashSet<>();
        Set<Long> handles = new HashSet<>();
        long prepareTimer = RenderPerformanceProfiler.get().startTimer();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.isSpectator()
                    || !mc.getEntityRenderDispatcher().shouldRender(entity, frustum,
                            position.x, position.y, position.z)) continue;
            if (entity instanceof LivingEntity living && !isCompiled(sectionCompiled, living.blockPosition())) continue;
            addCandidate(entity, mc.player, entity == camera.getEntity(), ysmActive, maidEligibility,
                    RenderPerformanceProfiler.Scene.WORLD, delta, 0.0f,
                    mc.getEntityRenderDispatcher(), frustum, position.x, position.y, position.z,
                    maidYawResolver, entityIds, handles, candidates);
        }
        run(candidates, RenderPerformanceProfiler.Scene.WORLD, prepareTimer);
    }

    /** Iris 已按阴影视锥筛出实体；这里仍用同一个 dispatcher 复核可见性。 */
    public static void prepareShadow(Iterable<? extends Entity> shadowCandidates,
            net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher, Frustum frustum,
            double cameraX, double cameraY, double cameraZ, float frozenPartialTick, float realTickDelta,
            Predicate<AbstractClientPlayer> ysmActive, MaidEligibility maidEligibility,
            MaidYawResolver maidYawResolver) {
        if (!NativeEvaluationBatch.enabled() || shadowCandidates == null || frustum == null
                || !IrisCompatBridge.shadows() || FirstPersonManager.vrRuntime().isLocalPlayerInVr()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        List<Candidate> candidates = new ArrayList<>();
        Set<java.util.UUID> entityIds = new HashSet<>();
        Set<Long> handles = new HashSet<>();
        long prepareTimer = RenderPerformanceProfiler.get().startTimer();
        for (Entity entity : shadowCandidates) {
            if (entity == null || entity.isSpectator()
                    || !dispatcher.shouldRender(entity, frustum, cameraX, cameraY, cameraZ)) continue;
            float tick = mc.level.tickRateManager().isEntityFrozen(entity) ? frozenPartialTick : realTickDelta;
            addCandidate(entity, mc.player, entity == mc.player, ysmActive, maidEligibility,
                    RenderPerformanceProfiler.Scene.SHADOW, null, tick, dispatcher, frustum,
                    cameraX, cameraY, cameraZ, maidYawResolver, entityIds, handles, candidates);
        }
        run(candidates, RenderPerformanceProfiler.Scene.SHADOW, prepareTimer);
    }

    private static void addCandidate(Entity entity, Player localPlayer, boolean cameraEntity,
            Predicate<AbstractClientPlayer> ysmActive, MaidEligibility maidEligibility,
            RenderPerformanceProfiler.Scene scene, DeltaTracker delta, float tickDelta,
            net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher, Frustum frustum,
            double cameraX, double cameraY, double cameraZ, MaidYawResolver maidYawResolver,
            Set<java.util.UUID> entityIds,
            Set<Long> handles, List<Candidate> candidates) {
        if (!(entity instanceof LivingEntity living) || !entityIds.add(entity.getUUID())) return;
        if (scene == RenderPerformanceProfiler.Scene.WORLD && delta != null) {
            tickDelta = delta.getGameTimeDeltaPartialTick(
                    !Minecraft.getInstance().level.tickRateManager().isEntityFrozen(entity));
        }
        ManagedModel data = null;
        Kind kind;
        float yaw;
        if (living instanceof AbstractClientPlayer player) {
            if (player == localPlayer || cameraEntity || player.tickCount == 0 || player.isSpectator()
                    || ysmActive.test(player) || TaczGunDetector.isGun(player.getMainHandItem())) return;
            data = PlayerEvaluationAdapter.peekLoadedRemoteModel(player);
            if (data == null) return;
            yaw = player.getYRot();
            kind = Kind.PLAYER;
        } else if (MobReplacementService.isMaidEntity(living)) {
            if (maidEligibility == null || !maidEligibility.canReplace(living)) return;
            data = MaidMMDModelManager.peekLoadedModel(living.getUUID());
            if (data == null) return;
            yaw = maidYawResolver == null ? net.minecraft.util.Mth.lerp(tickDelta, living.yRotO, living.getYRot())
                    : maidYawResolver.renderYaw(living, tickDelta);
            kind = Kind.MAID;
        } else {
            String modelName = MobReplacementService.getReplacementModelName(living);
            if (modelName == null || !RenderPriorityServiceBridge.shouldUseMobReplacement(living)) return;
            data = ClientRenderRuntime.get().modelRepository().peek(ModelRequestKey.mob(living, modelName));
            if (data == null) return;
            yaw = living.getYRot();
            kind = Kind.MOB;
        }
        if (!(data.modelInstance() instanceof BaseModelInstance model) || !model.canPrepareWorldEvaluation()) return;
        long handle = model.getModelHandle();
        if (handle == 0 || !handles.add(handle)) return;
        candidates.add(new Candidate(entity, data, model, kind, yaw, tickDelta));
    }

    private static void run(List<Candidate> candidates, RenderPerformanceProfiler.Scene scene, long prepareTimer) {
        Minecraft mc = Minecraft.getInstance();
        List<NativeEvaluationBatch.Work> jobs = new ArrayList<>(candidates.size());
        int[] jobKinds = new int[3];
        for (Candidate candidate : candidates) {
            switch (candidate.kind()) {
                case PLAYER -> {
                    AbstractClientPlayer player = (AbstractClientPlayer) candidate.entity();
                    addJob(jobs, PlayerEvaluationAdapter.prepare(player, candidate.data(),
                            candidate.tickDelta(), scene), jobKinds, 0);
                }
                case MOB -> {
                    MutableRenderPose pose = new MutableRenderPose();
                    EntityAnimationResolver.resolve(candidate.entity(), candidate.data(), candidate.yaw(),
                            candidate.tickDelta(), pose);
                    addJob(jobs, candidate.model().prepareWorldEvaluation((LivingEntity) candidate.entity(),
                            pose.bodyYaw, candidate.tickDelta(), scene), jobKinds, 1);
                }
                case MAID -> addJob(jobs, MaidRenderPreparation.prepareEvaluation(
                        (LivingEntity) candidate.entity(), candidate.data(), candidate.yaw(),
                        candidate.tickDelta(), scene), jobKinds, 2);
            }
        }
        RenderPerformanceProfiler profiler = RenderPerformanceProfiler.get();
        profiler.endTimer("native.batchPrepare." + scene.name(), prepareTimer);
        if (jobs.isEmpty()) return;
        long executeTimer = profiler.startTimer();
        NativeEvaluationBatch.BatchStats stats = NativeEvaluationBatch.run(jobs);
        profiler.endTimer("native.batchExecuteJoin." + scene.name(), executeTimer);
        profiler.recordParallelBatch(scene, jobKinds[0], jobKinds[1], jobKinds[2], stats);
    }

    private static void addJob(List<NativeEvaluationBatch.Work> jobs, NativeEvaluationBatch.Work work,
                               int[] kinds, int kind) {
        if (work == null) return;
        jobs.add(work);
        kinds[kind]++;
    }

    private static boolean isCompiled(Predicate<BlockPos> compiled, BlockPos pos) {
        if (compiled == null || Minecraft.getInstance().level.isOutsideBuildHeight(pos.getY())) return true;
        return compiled.test(pos);
    }

    /** 独立薄适配器使预处理器不缓存策略服务引用。 */
    private static final class RenderPriorityServiceBridge {
        static boolean shouldUseMobReplacement(LivingEntity entity) {
            return com.shiroha.mmdskin.render.policy.RenderPriorityService.get().shouldUseMobReplacement(entity);
        }
    }

    private static final class IrisCompatBridge {
        static boolean shadows() { return com.shiroha.mmdskin.compat.iris.IrisCompat.isRenderingShadows(); }
    }
}
