package com.shiroha.mmdskin.render.policy;

import com.shiroha.mmdskin.config.UIConstants;
import com.shiroha.mmdskin.compat.maid.runtime.MaidMMDModelManager;
import com.shiroha.mmdskin.player.sync.PlayerModelSyncService;
import com.shiroha.mmdskin.render.entity.MobReplacementService;
import com.shiroha.mmdskin.render.pipeline.RenderPerformanceProfiler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** 文件职责：基于可见性与距离预算决定渲染更新优先级。 */
public final class RenderPriorityService {
    private static final RenderPriorityService INSTANCE = new RenderPriorityService();
    private final RenderPerformanceConfig config = ConfigManagerRenderPerformanceConfig.get();

    private final ConcurrentMap<Long, AnimationUpdateSchedule> animationSchedules = new ConcurrentHashMap<>();
    private final Set<UUID> prioritizedVisibleEntities = new HashSet<>();
    private final Set<UUID> prioritizedPhysicsEntities = new HashSet<>();

    private long currentFrameKey = Long.MIN_VALUE;
    private long currentFrameIndex = 0L;
    private int visibleModelsThisFrame = 0;
    private int physicsModelsThisFrame = 0;

    private RenderPriorityService() {
    }

    public static RenderPriorityService get() {
        return INSTANCE;
    }

    public synchronized void beginWorldFrame() {
        long nextFrameKey = computeFrameKey();
        if (nextFrameKey == currentFrameKey) {
            return;
        }

        currentFrameKey = nextFrameKey;
        currentFrameIndex++;
        rebuildPrioritySets();
    }

    public synchronized boolean shouldUsePlayerModel(AbstractClientPlayer player) {
        beginWorldFrame();
        if (player == null) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.getUUID().equals(player.getUUID())) {
            return true;
        }
        return prioritizedVisibleEntities.contains(player.getUUID());
    }

    public synchronized boolean shouldUseMobReplacement(LivingEntity entity) {
        beginWorldFrame();
        return entity != null && prioritizedVisibleEntities.contains(entity.getUUID());
    }

    public boolean shouldUpdateAnimation(long modelHandle, double distanceSq, boolean localPlayer) {
        beginWorldFrame();
        return animationSchedules.computeIfAbsent(modelHandle, ignored -> new AnimationUpdateSchedule()).due(
                modelHandle, currentFrameIndex, distanceSq, localPlayer,
                config.getAnimationLodMediumDistance(), config.getAnimationLodFarDistance(),
                config.getAnimationLodMediumUpdateInterval(), config.getAnimationLodFarUpdateInterval());
    }

    public synchronized boolean shouldEnablePhysics(Entity entity, boolean localPlayer) {
        beginWorldFrame();
        if (!config.isPhysicsEnabled()) {
            return false;
        }
        if (localPlayer) {
            return true;
        }
        return entity != null && prioritizedPhysicsEntities.contains(entity.getUUID());
    }

    public double distanceSqToCamera(Entity entity, boolean localPlayer) {
        if (entity == null || localPlayer) {
            return 0.0d;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Entity cameraEntity = minecraft.getCameraEntity();
        if (cameraEntity == null) {
            return 0.0d;
        }

        return entity.distanceToSqr(cameraEntity);
    }

    private long computeFrameKey() {
        return com.shiroha.mmdskin.render.pipeline.RenderFrameClock.current();
    }

    public synchronized void completeRenderFrame() {
        boolean worldFrame = currentFrameKey == computeFrameKey();
        RenderPerformanceProfiler.get().completeFrame(worldFrame ? visibleModelsThisFrame : 0,
                worldFrame ? physicsModelsThisFrame : 0);
    }

    public void forgetModel(long handle) { animationSchedules.remove(handle); }

    private void rebuildPrioritySets() {
        Set<UUID> previousVisible = new HashSet<>(prioritizedVisibleEntities);
        Set<UUID> previousPhysics = new HashSet<>(prioritizedPhysicsEntities);
        prioritizedVisibleEntities.clear();
        prioritizedPhysicsEntities.clear();
        visibleModelsThisFrame = 0;
        physicsModelsThisFrame = 0;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        List<PrioritizedEntity> candidates = new ArrayList<>();

        for (AbstractClientPlayer player : minecraft.level.players()) {
            if (shouldConsiderPlayer(player)) {
                boolean localPlayer = minecraft.player != null && minecraft.player.getUUID().equals(player.getUUID());
                candidates.add(prioritize(player, localPlayer, previousVisible));
            }
        }

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && !(entity instanceof AbstractClientPlayer)) {
                String replacementModel = MobReplacementService.getReplacementModelName(living);
                // 女仆模型由独立绑定管理器提供，不经过普通生物替换配置。
                boolean maidModel = replacementModel == null
                        && MobReplacementService.isMaidEntity(living)
                        && MaidMMDModelManager.hasMMDModel(living.getUUID());
                if (replacementModel != null || maidModel) {
                    candidates.add(prioritize(living, false, previousVisible));
                }
            }
        }

        candidates.sort(Comparator
                .comparing(PrioritizedEntity::localPlayer).reversed()
                .thenComparingDouble(PrioritizedEntity::score)
                .thenComparing(candidate -> candidate.entity().getUUID()));

        int visibleCap = config.getMaxVisibleModelsPerFrame();
        int physicsCap = config.getMaxPhysicsModelsPerFrame();
        double physicsDistance = config.getPhysicsLodMaxDistance();

        for (PrioritizedEntity candidate : candidates) {
            Entity entity = candidate.entity();
            UUID uuid = entity.getUUID();
            if (candidate.localPlayer() || visibleCap <= 0 || prioritizedVisibleEntities.size() < visibleCap) {
                prioritizedVisibleEntities.add(uuid);
            }

            if (!config.isPhysicsEnabled()) {
                continue;
            }
            if (candidate.localPlayer()) {
                prioritizedPhysicsEntities.add(uuid);
                continue;
            }
            double boundary = physicsDistance * (AnimationUpdateSchedule.enabled() && previousPhysics.contains(uuid) ? 1.1 : 1.0);
            if (physicsDistance > 0.0d && candidate.distanceSq() > boundary * boundary) {
                continue;
            }
            if (physicsCap <= 0 || prioritizedPhysicsEntities.size() < physicsCap) {
                prioritizedPhysicsEntities.add(uuid);
            }
        }

        visibleModelsThisFrame = prioritizedVisibleEntities.size();
        physicsModelsThisFrame = prioritizedPhysicsEntities.size();
    }

    private PrioritizedEntity prioritize(Entity entity, boolean local, Set<UUID> previous) {
        double distance = distanceSqToCamera(entity, local);
        double score = distance;
        Minecraft minecraft = Minecraft.getInstance();
        var camera = minecraft.gameRenderer.getMainCamera();
        if (AnimationUpdateSchedule.enabled() && camera.isInitialized() && !local) {
            var offset = entity.getBoundingBox().getCenter().subtract(camera.getPosition());
            var forward = camera.getLookVector();
            var left = camera.getLeftVector();
            var up = camera.getUpVector();
            // Extra margin covers ordinary custom meshes. Even very large models remain
            // candidates: the approximation affects budgets, never hard visibility tests.
            double radius = Math.max(2, entity.getBoundingBox().getSize() * .75);
            double tangent = Math.tan(Math.toRadians(minecraft.options.fov().get()) * .5);
            double aspect = (double) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight());
            score = ScreenPriority.score(distance,
                    offset.x * left.x + offset.y * left.y + offset.z * left.z,
                    offset.x * up.x + offset.y * up.y + offset.z * up.z,
                    offset.x * forward.x + offset.y * forward.y + offset.z * forward.z,
                    radius, tangent, aspect, previous.contains(entity.getUUID()));
        }
        return new PrioritizedEntity(entity, distance, local, score);
    }

    private boolean shouldConsiderPlayer(AbstractClientPlayer player) {
        if (player == null || player.isSpectator()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        boolean localPlayer = minecraft.player != null && minecraft.player.getUUID().equals(player.getUUID());
        String selectedModel = PlayerModelSyncService.getPlayerModel(player.getUUID(), player.getName().getString(), localPlayer);
        return selectedModel != null
                && !selectedModel.isBlank()
                && !UIConstants.DEFAULT_MODEL_NAME.equals(selectedModel);
    }

    private record PrioritizedEntity(Entity entity, double distanceSq, boolean localPlayer, double score) {
    }
}
