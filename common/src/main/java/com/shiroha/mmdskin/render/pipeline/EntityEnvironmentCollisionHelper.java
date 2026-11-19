package com.shiroha.mmdskin.render.pipeline;

import com.shiroha.mmdskin.bridge.runtime.NativeScenePort;
import com.shiroha.mmdskin.config.ConfigManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** 文件职责：零 GC 探测实体周边地面、方块碰撞箱与双足台阶支撑面并同步至原生运行时。 */
public final class EntityEnvironmentCollisionHelper {
    private static final int MAX_BLOCKS = 16;
    private static final int MAX_SCAN_HEIGHT = 16;
    private static final float WORLD_COORDINATE_LIMIT = 30_000_000.0f;
    private static final int FLOATS_PER_BLOCK = 6;
    private static final int BUFFER_BYTES = MAX_BLOCKS * FLOATS_PER_BLOCK * Float.BYTES;
    private static final float LOD_DISTANCE_SQ = 16.0f * 16.0f;
    private static final float POS_EPSILON_SQ = 0.02f * 0.02f;
    private static final float YAW_EPSILON_RAD = (float) Math.toRadians(3.0);
    private static final float GROUND_PROBE_DEPTH = 8.0f;
    private static final float GROUND_PROBE_HALF_EXTENT = 0.25f;
    private static final float FOOT_OFFSET_SCALE = 0.11f;
    private static final float FOOT_PROBE_HALF_EXTENT = 0.22f;
    private static final float FOOT_PROBE_Y_RANGE = 0.65f;
    private static final int MAX_CACHE_ENTRIES = 256;

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private EntityEnvironmentCollisionHelper() {
    }

    /** 采样并下发实体环境碰撞数据；entityScale 为模型配置缩放，totalScale 为物理世界缩放。 */
    public static void syncEnvironmentColliders(NativeScenePort scenePort,
                                                long modelHandle,
                                                LivingEntity entity,
                                                float posX,
                                                float posY,
                                                float posZ,
                                                float bodyYaw,
                                                float entityScale,
                                                float totalScale,
                                                boolean allowFootIk) {
        if (scenePort == null || modelHandle == 0L || entity == null) {
            return;
        }
        Level level = entity.level();
        if (level == null || !Float.isFinite(totalScale) || totalScale <= 0.0f) {
            return;
        }

        boolean groundEnabled = ConfigManager.isPhysicsGroundCollisionEnabled();
        boolean blockEnabled = ConfigManager.isPhysicsBlockCollisionEnabled();
        boolean footIkConfigEnabled = ConfigManager.isPhysicsFootIkEnabled();
        if ((!groundEnabled && !blockEnabled && !footIkConfigEnabled)
                || !validProbeCoordinate(posX) || !validProbeCoordinate(posY) || !validProbeCoordinate(posZ)) {
            scenePort.setModelEnvironmentColliders(
                    modelHandle, Float.NaN, totalScale, false, Float.NaN, Float.NaN, null, 0);
            return;
        }

        float safeEntityScale = Float.isFinite(entityScale) && entityScale > 0.0f ? entityScale : 1.0f;
        boolean withinLod = isWithinLodDistance(posX, posY, posZ);
        boolean effectiveFootIk = allowFootIk && footIkConfigEnabled && withinLod;
        boolean effectiveBlock = blockEnabled && withinLod;

        Scratch scratch = SCRATCH.get();
        long gameTime = level.getGameTime();
        CachedEnvironment cached = scratch.cache.get(modelHandle);

        if (cached != null && isCacheValid(cached, gameTime, posX, posY, posZ, bodyYaw,
                safeEntityScale, groundEnabled, effectiveBlock, effectiveFootIk)) {
            dispatchCached(scenePort, modelHandle, totalScale, cached, scratch);
            return;
        }

        float groundY = groundEnabled
                ? probeGroundY(level, entity, posX, posY, posZ, scratch)
                : Float.NaN;

        int blockCount = 0;
        if (effectiveBlock) {
            collectNearbyBlocks(level, posX, posY, posZ, groundY, safeEntityScale, scratch);
            blockCount = scratch.candidateCount;
        }

        float leftFootY = Float.NaN;
        float rightFootY = Float.NaN;
        if (effectiveFootIk) {
            float offset = FOOT_OFFSET_SCALE * safeEntityScale;
            float cosYaw = Mth.cos(bodyYaw);
            float sinYaw = Mth.sin(bodyYaw);
            leftFootY = probeFootSurfaceY(level, posX + cosYaw * offset, posY, posZ + sinYaw * offset, scratch);
            rightFootY = probeFootSurfaceY(level, posX - cosYaw * offset, posY, posZ - sinYaw * offset, scratch);
        }

        if (cached == null) {
            pruneCacheIfNeeded(scratch.cache, gameTime);
            cached = new CachedEnvironment();
            scratch.cache.put(modelHandle, cached);
        }
        cached.update(gameTime, posX, posY, posZ, bodyYaw, safeEntityScale,
                groundEnabled, effectiveBlock, effectiveFootIk,
                groundY, leftFootY, rightFootY, scratch.candidateBoxes, blockCount);

        dispatchCached(scenePort, modelHandle, totalScale, cached, scratch);
    }

    private static void dispatchCached(NativeScenePort scenePort,
                                       long modelHandle,
                                       float totalScale,
                                       CachedEnvironment cached,
                                       Scratch scratch) {
        ByteBuffer buffer = null;
        int count = cached.blockCount;
        if (count > 0) {
            scratch.directBuffer.clear();
            scratch.floatView.clear();
            scratch.floatView.put(cached.boxes, 0, count * FLOATS_PER_BLOCK);
            scratch.directBuffer.limit(count * FLOATS_PER_BLOCK * Float.BYTES);
            buffer = scratch.directBuffer;
        }
        scenePort.setModelEnvironmentColliders(
                modelHandle,
                cached.groundY,
                totalScale,
                cached.allowFootIk,
                cached.leftFootY,
                cached.rightFootY,
                buffer,
                count);
    }

    private static boolean isWithinLodDistance(float posX, float posY, float posZ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null) {
            return true;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null || !camera.isInitialized()) {
            return true;
        }
        Vec3 camPos = camera.getPosition();
        double dx = posX - camPos.x;
        double dy = posY - camPos.y;
        double dz = posZ - camPos.z;
        return (dx * dx + dy * dy + dz * dz) <= LOD_DISTANCE_SQ;
    }

    private static boolean isCacheValid(CachedEnvironment entry,
                                        long gameTime,
                                        float posX,
                                        float posY,
                                        float posZ,
                                        float bodyYaw,
                                        float entityScale,
                                        boolean groundEnabled,
                                        boolean blockEnabled,
                                        boolean allowFootIk) {
        if (entry.groundEnabled != groundEnabled
                || entry.blockEnabled != blockEnabled
                || entry.allowFootIk != allowFootIk
                || Math.abs(entry.entityScale - entityScale) >= 1e-4f) {
            return false;
        }
        if (entry.gameTime == gameTime) {
            return true;
        }
        float dx = posX - entry.posX;
        float dy = posY - entry.posY;
        float dz = posZ - entry.posZ;
        float dPosSq = dx * dx + dy * dy + dz * dz;
        float dYaw = Math.abs(wrapAngleRadians(bodyYaw - entry.bodyYaw));
        return dPosSq < POS_EPSILON_SQ && dYaw < YAW_EPSILON_RAD;
    }

    private static float probeGroundY(Level level,
                                      LivingEntity entity,
                                      float posX,
                                      float posY,
                                      float posZ,
                                      Scratch scratch) {
        if (entity.onGround()) {
            return posY;
        }
        return probeHighestSurface(
                level,
                posX,
                posZ,
                GROUND_PROBE_HALF_EXTENT,
                posY - GROUND_PROBE_DEPTH,
                posY + 0.05f,
                scratch);
    }

    private static float probeFootSurfaceY(Level level,
                                           float footX,
                                           float posY,
                                           float footZ,
                                           Scratch scratch) {
        return probeHighestSurface(
                level,
                footX,
                footZ,
                FOOT_PROBE_HALF_EXTENT,
                posY - FOOT_PROBE_Y_RANGE,
                posY + FOOT_PROBE_Y_RANGE,
                scratch);
    }

    /** 在指定柱状区域 [minY, maxY] 内从上往下扫描最高碰撞顶面，无碰撞箱则返回 Float.NaN。 */
    private static float probeHighestSurface(Level level,
                                             float centerX,
                                             float centerZ,
                                             float halfExtent,
                                             float minY,
                                             float maxY,
                                             Scratch scratch) {
        if (!validProbeCoordinate(centerX) || !validProbeCoordinate(centerZ)) return Float.NaN;
        int minBx = Mth.floor(centerX - halfExtent);
        int maxBx = Mth.floor(centerX + halfExtent);
        int minBz = Mth.floor(centerZ - halfExtent);
        int maxBz = Mth.floor(centerZ + halfExtent);
        int topBy = Math.min(Mth.floor(maxY), level.getMaxBuildHeight() - 1);
        int bottomBy = Math.max(Mth.floor(minY), level.getMinBuildHeight());

        SurfaceProbeConsumer consumer = scratch.surfaceConsumer;
        consumer.reset(centerX - halfExtent, centerX + halfExtent,
                centerZ - halfExtent, centerZ + halfExtent, minY, maxY);

        BlockPos.MutableBlockPos mutablePos = scratch.mutablePos;
        for (int by = topBy; by >= bottomBy; by--) {
            boolean foundInLayer = false;
            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int bz = minBz; bz <= maxBz; bz++) {
                    mutablePos.set(bx, by, bz);
                    BlockState state = level.getBlockState(mutablePos);
                    if (state.isAir()) {
                        continue;
                    }
                    VoxelShape shape = state.getCollisionShape(level, mutablePos);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    consumer.beginBlock(bx, by, bz);
                    shape.forAllBoxes(consumer);
                    if (consumer.foundInBlock) {
                        foundInLayer = true;
                    }
                }
            }
            if (foundInLayer && Float.isFinite(consumer.maxSurfaceY)) {
                break;
            }
        }
        return consumer.maxSurfaceY;
    }

    private static void collectNearbyBlocks(Level level,
                                            float posX,
                                            float posY,
                                            float posZ,
                                            float groundY,
                                            float entityScale,
                                            Scratch scratch) {
        float radius = Mth.clamp(0.85f * entityScale, 0.7f, 1.8f);
        float minQueryY = Float.isFinite(groundY) ? groundY + 0.02f : posY - 0.1f;
        float maxQueryY = posY + 2.0f * entityScale;
        float floorCutoffY = Float.isFinite(groundY) ? groundY + 0.01f : posY - 0.5f;

        if (maxQueryY <= minQueryY) {
            scratch.candidateCount = 0;
            return;
        }

        int minBx = Mth.floor(posX - radius);
        int maxBx = Mth.floor(posX + radius);
        int minBy = Math.max(Mth.floor(minQueryY), level.getMinBuildHeight());
        int maxBy = Math.min(Math.min(Mth.floor(maxQueryY), level.getMaxBuildHeight() - 1),
                minBy + MAX_SCAN_HEIGHT - 1);
        int minBz = Mth.floor(posZ - radius);
        int maxBz = Mth.floor(posZ + radius);

        scratch.candidateCount = 0;
        BlockCollectConsumer consumer = scratch.blockConsumer;
        consumer.reset(posX, posZ, radius, minQueryY, maxQueryY, floorCutoffY);

        BlockPos.MutableBlockPos mutablePos = scratch.mutablePos;
        for (int by = minBy; by <= maxBy; by++) {
            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int bz = minBz; bz <= maxBz; bz++) {
                    mutablePos.set(bx, by, bz);
                    BlockState state = level.getBlockState(mutablePos);
                    if (state.isAir()) {
                        continue;
                    }
                    VoxelShape shape = state.getCollisionShape(level, mutablePos);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    consumer.beginBlock(bx, by, bz);
                    shape.forAllBoxes(consumer);
                }
            }
        }
    }

    private static boolean validProbeCoordinate(float value) {
        return Math.abs(value) <= WORLD_COORDINATE_LIMIT;
    }

    private static void pruneCacheIfNeeded(Map<Long, CachedEnvironment> cache, long currentTick) {
        if (cache.size() < MAX_CACHE_ENTRIES) {
            return;
        }
        Iterator<Map.Entry<Long, CachedEnvironment>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, CachedEnvironment> entry = it.next();
            if (currentTick - entry.getValue().gameTime > 100L) {
                it.remove();
            }
        }
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
    }

    private static float wrapAngleRadians(float angle) {
        float twoPi = (float) (Math.PI * 2.0);
        angle = angle % twoPi;
        if (angle > (float) Math.PI) {
            angle -= twoPi;
        } else if (angle < (float) -Math.PI) {
            angle += twoPi;
        }
        return angle;
    }

    private static final class CachedEnvironment {
        long gameTime;
        float posX;
        float posY;
        float posZ;
        float bodyYaw;
        float entityScale;
        boolean groundEnabled;
        boolean blockEnabled;
        boolean allowFootIk;
        float groundY;
        float leftFootY;
        float rightFootY;
        int blockCount;
        final float[] boxes = new float[MAX_BLOCKS * FLOATS_PER_BLOCK];

        void update(long gameTime,
                    float posX,
                    float posY,
                    float posZ,
                    float bodyYaw,
                    float entityScale,
                    boolean groundEnabled,
                    boolean blockEnabled,
                    boolean allowFootIk,
                    float groundY,
                    float leftFootY,
                    float rightFootY,
                    float[] sourceBoxes,
                    int blockCount) {
            this.gameTime = gameTime;
            this.posX = posX;
            this.posY = posY;
            this.posZ = posZ;
            this.bodyYaw = bodyYaw;
            this.entityScale = entityScale;
            this.groundEnabled = groundEnabled;
            this.blockEnabled = blockEnabled;
            this.allowFootIk = allowFootIk;
            this.groundY = groundY;
            this.leftFootY = leftFootY;
            this.rightFootY = rightFootY;
            this.blockCount = blockCount;
            if (blockCount > 0) {
                System.arraycopy(sourceBoxes, 0, this.boxes, 0, blockCount * FLOATS_PER_BLOCK);
            }
        }
    }

    private static final class Scratch {
        final ByteBuffer directBuffer = ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final FloatBuffer floatView = directBuffer.asFloatBuffer();
        final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        final float[] candidateBoxes = new float[MAX_BLOCKS * FLOATS_PER_BLOCK];
        final float[] candidateDistSq = new float[MAX_BLOCKS];
        int candidateCount;
        final Map<Long, CachedEnvironment> cache = new HashMap<>(32);
        final SurfaceProbeConsumer surfaceConsumer = new SurfaceProbeConsumer();
        final BlockCollectConsumer blockConsumer = new BlockCollectConsumer(this);
    }

    private static final class SurfaceProbeConsumer implements Shapes.DoubleLineConsumer {
        double minX, maxX, minZ, maxZ;
        double minY, maxY;
        int bx, by, bz;
        float maxSurfaceY;
        boolean foundInBlock;

        void reset(double minX, double maxX, double minZ, double maxZ, double minY, double maxY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.minY = minY;
            this.maxY = maxY;
            this.maxSurfaceY = Float.NaN;
            this.foundInBlock = false;
        }

        void beginBlock(int bx, int by, int bz) {
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.foundInBlock = false;
        }

        @Override
        public void consume(double x1, double y1, double z1, double x2, double y2, double z2) {
            double wx1 = bx + x1;
            double wy2 = by + y2;
            double wz1 = bz + z1;
            double wx2 = bx + x2;
            double wz2 = bz + z2;
            if (wx2 <= minX || wx1 >= maxX || wz2 <= minZ || wz1 >= maxZ) {
                return;
            }
            if (wy2 < minY || wy2 > maxY) {
                return;
            }
            float surface = (float) wy2;
            if (!Float.isFinite(maxSurfaceY) || surface > maxSurfaceY) {
                maxSurfaceY = surface;
            }
            foundInBlock = true;
        }
    }

    private static final class BlockCollectConsumer implements Shapes.DoubleLineConsumer {
        private final Scratch scratch;
        float posX, posZ;
        double queryMinX, queryMaxX, queryMinY, queryMaxY, queryMinZ, queryMaxZ;
        double floorCutoffY;
        int bx, by, bz;

        BlockCollectConsumer(Scratch scratch) {
            this.scratch = scratch;
        }

        void reset(float posX, float posZ, float radius, float minY, float maxY, float floorCutoffY) {
            this.posX = posX;
            this.posZ = posZ;
            this.queryMinX = posX - radius;
            this.queryMaxX = posX + radius;
            this.queryMinY = minY;
            this.queryMaxY = maxY;
            this.queryMinZ = posZ - radius;
            this.queryMaxZ = posZ + radius;
            this.floorCutoffY = floorCutoffY;
        }

        void beginBlock(int bx, int by, int bz) {
            this.bx = bx;
            this.by = by;
            this.bz = bz;
        }

        @Override
        public void consume(double x1, double y1, double z1, double x2, double y2, double z2) {
            double wx1 = bx + x1;
            double wy1 = by + y1;
            double wz1 = bz + z1;
            double wx2 = bx + x2;
            double wy2 = by + y2;
            double wz2 = bz + z2;

            if (wy2 <= floorCutoffY) {
                return;
            }
            if (wx2 <= queryMinX || wx1 >= queryMaxX
                    || wy2 <= queryMinY || wy1 >= queryMaxY
                    || wz2 <= queryMinZ || wz1 >= queryMaxZ) {
                return;
            }

            float cx = (float) ((wx1 + wx2) * 0.5);
            float cz = (float) ((wz1 + wz2) * 0.5);
            float dx = cx - posX;
            float dz = cz - posZ;
            float distSq = dx * dx + dz * dz;

            int count = scratch.candidateCount;
            if (count < MAX_BLOCKS) {
                insertAt(count, (float) wx1, (float) wy1, (float) wz1,
                        (float) wx2, (float) wy2, (float) wz2, distSq);
                scratch.candidateCount = count + 1;
                return;
            }

            // 已有 16 个时，仅在比当前最远候选更近时替换，维持按距离升序
            if (distSq >= scratch.candidateDistSq[MAX_BLOCKS - 1]) {
                return;
            }
            insertAt(MAX_BLOCKS - 1, (float) wx1, (float) wy1, (float) wz1,
                    (float) wx2, (float) wy2, (float) wz2, distSq);
        }

        private void insertAt(int limitIndex,
                              float minX, float minY, float minZ,
                              float maxX, float maxY, float maxZ,
                              float distSq) {
            int pos = limitIndex;
            while (pos > 0 && scratch.candidateDistSq[pos - 1] > distSq) {
                scratch.candidateDistSq[pos] = scratch.candidateDistSq[pos - 1];
                System.arraycopy(scratch.candidateBoxes, (pos - 1) * FLOATS_PER_BLOCK,
                        scratch.candidateBoxes, pos * FLOATS_PER_BLOCK, FLOATS_PER_BLOCK);
                pos--;
            }
            scratch.candidateDistSq[pos] = distSq;
            int base = pos * FLOATS_PER_BLOCK;
            scratch.candidateBoxes[base] = minX;
            scratch.candidateBoxes[base + 1] = minY;
            scratch.candidateBoxes[base + 2] = minZ;
            scratch.candidateBoxes[base + 3] = maxX;
            scratch.candidateBoxes[base + 4] = maxY;
            scratch.candidateBoxes[base + 5] = maxZ;
        }
    }
}
