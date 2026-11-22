package com.shiroha.mmdskin.render.pipeline;

import com.shiroha.mmdskin.bridge.runtime.NativeScenePort;
import com.shiroha.mmdskin.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Collects immutable-for-this-tick collision geometry on the render thread. */
final class EntityLocomotionInput {
    static final int DISABLED = 0;
    static final int IDLE = 1;
    static final int WALK = 2;
    static final int SPRINT = 3;
    private static final int MAX_SURFACES = 256;
    private static final int SURFACE_BYTES = 6 * Float.BYTES;
    private static final ThreadLocal<SurfaceCache> SURFACES =
            ThreadLocal.withInitial(SurfaceCache::new);

    private EntityLocomotionInput() {}

    static void sync(NativeScenePort port, long handle, LivingEntity entity, Vec3 origin,
                     float yawDegrees, float scale, int mode) {
        if (mode == DISABLED || !Float.isFinite(scale) || scale <= 0.0001f
                || !Double.isFinite(origin.x) || !Double.isFinite(origin.y) || !Double.isFinite(origin.z)) {
            port.setModelLocomotion(handle, DISABLED, false, false, 0, RenderFrameClock.current(),
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, null, 0);
            return;
        }

        Level level = entity.level();
        BlockPos block = entity.blockPosition();
        double dx = entity.getX() - entity.xo;
        double dy = entity.getY() - entity.yo;
        double dz = entity.getZ() - entity.zo;
        boolean terrain = ConfigManager.isPhysicsFootIkEnabled() && nearCamera(origin)
                && isContinuousMotion(dx, dy, dz);
        SurfaceBuffer surfaces = terrain ? SURFACES.get().collect(level, block) : null;
        port.setModelLocomotion(handle, mode, entity.onGround(), terrain, level.getGameTime(),
                RenderFrameClock.current(), System.identityHashCode(level), block.getX(), block.getY(),
                block.getZ(), (float) Math.toRadians(yawDegrees),
                (float) (origin.x - block.getX()), (float) (origin.y - block.getY()),
                (float) (origin.z - block.getZ()), (float) (entity.getX() - block.getX()),
                (float) (entity.getY() - block.getY()), (float) (entity.getZ() - block.getZ()),
                (float) dx, (float) dy, (float) dz, scale,
                surfaces == null ? null : surfaces.buffer, surfaces == null ? 0 : surfaces.count);
    }

    static boolean isContinuousMotion(double dx, double dy, double dz) {
        return Math.hypot(dx, dz) <= 0.85 && Math.abs(dy) <= 0.85;
    }

    private static boolean nearCamera(Vec3 origin) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        return !camera.isInitialized() || origin.distanceToSqr(camera.getPosition()) <= 16 * 16;
    }

    private static final class SurfaceCache {
        private final SurfaceBuffer[] entries = new SurfaceBuffer[8];
        private WeakReference<Level> level = new WeakReference<>(null);
        private long tick;
        private int size;
        private int next;

        private SurfaceBuffer collect(Level level, BlockPos origin) {
            long tick = level.getGameTime();
            if (this.level.get() != level) {
                this.level = new WeakReference<>(level);
                size = 0;
                next = 0;
            }
            if (this.tick != tick) {
                this.tick = tick;
                size = 0;
                next = 0;
            }
            for (int i = 0; i < size; i++) {
                SurfaceBuffer entry = entries[i];
                if (entry.matches(origin)) return entry;
            }
            SurfaceBuffer entry = entries[next];
            if (entry == null) entries[next] = entry = new SurfaceBuffer();
            next = (next + 1) % entries.length;
            size = Math.min(size + 1, entries.length);
            entry.collect(level, origin);
            return entry;
        }
    }

    private static final class SurfaceBuffer {
        private final ByteBuffer buffer = ByteBuffer.allocateDirect(MAX_SURFACES * SURFACE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private int x, y, z, offsetX, offsetY, offsetZ, count;
        private boolean overflow;

        private boolean matches(BlockPos origin) {
            return x == origin.getX() && y == origin.getY() && z == origin.getZ();
        }

        private void collect(Level level, BlockPos origin) {
            x = origin.getX();
            y = origin.getY();
            z = origin.getZ();
            buffer.clear();
            count = 0;
            overflow = false;
            for (offsetY = -2; offsetY <= 1 && !overflow; offsetY++) {
                int blockY = y + offsetY;
                if (blockY < level.getMinBuildHeight() || blockY >= level.getMaxBuildHeight()) continue;
                for (offsetX = -2; offsetX <= 2 && !overflow; offsetX++) {
                    for (offsetZ = -2; offsetZ <= 2 && !overflow; offsetZ++) {
                        cursor.set(x + offsetX, blockY, z + offsetZ);
                        var state = level.getBlockState(cursor);
                        if (!state.isAir()) state.getCollisionShape(level, cursor).forAllBoxes(this::add);
                    }
                }
            }
            if (overflow) count = 0;
        }

        private void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            if (count == MAX_SURFACES) {
                overflow = true;
                return;
            }
            buffer.putFloat((float) (offsetX + minX)).putFloat((float) (offsetY + minY))
                    .putFloat((float) (offsetZ + minZ)).putFloat((float) (offsetX + maxX))
                    .putFloat((float) (offsetY + maxY)).putFloat((float) (offsetZ + maxZ));
            count++;
        }
    }
}
