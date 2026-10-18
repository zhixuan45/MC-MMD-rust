package com.shiroha.mmdskin.stage.client.sync;

import com.shiroha.mmdskin.bridge.runtime.NativeAnimationPort;
import com.shiroha.mmdskin.stage.client.camera.port.StageFrameQueryPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证舞台外部帧驱动退出时恢复默认播放速度。 */
class StageAnimSyncHelperLifecycleTest {
    @Test
    void endRestoresStageLayerSpeedBeforeRemovingModel() throws Exception {
        RecordingAnimationPort port = new RecordingAnimationPort();
        StageAnimSyncHelper helper = new StageAnimSyncHelper(new NoopFrameQueryPort(), port);
        UUID playerId = UUID.randomUUID();
        putModel(helper, playerId, 41L);

        helper.end(playerId);

        assertEquals("41:0:1.0", port.lastSpeed);
    }

    @Test
    void disconnectRestoresAllStageLayerSpeeds() throws Exception {
        RecordingAnimationPort port = new RecordingAnimationPort();
        StageAnimSyncHelper helper = new StageAnimSyncHelper(new NoopFrameQueryPort(), port);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        putModel(helper, first, 41L);
        putModel(helper, second, 42L);

        helper.disconnect();

        assertEquals(Map.of(41L, 1.0f, 42L, 1.0f), port.speeds);
    }

    @SuppressWarnings("unchecked")
    private static void putModel(StageAnimSyncHelper helper, UUID playerId, long modelHandle) throws Exception {
        Field field = StageAnimSyncHelper.class.getDeclaredField("remoteStageModels");
        field.setAccessible(true);
        ((Map<UUID, Long>) field.get(helper)).put(playerId, modelHandle);
    }

    private static final class NoopFrameQueryPort implements StageFrameQueryPort {
        @Override
        public boolean isStagePresentationActive() {
            return false;
        }

        @Override
        public float getCurrentFrame() {
            return 0;
        }
    }

    private static final class RecordingAnimationPort implements NativeAnimationPort {
        private final Map<Long, Float> speeds = new ConcurrentHashMap<>();
        private String lastSpeed;

        @Override
        public void setLayerSpeed(long modelHandle, long layer, float speed) {
            speeds.put(modelHandle, speed);
            lastSpeed = modelHandle + ":" + layer + ":" + speed;
        }

        @Override public long loadAnimation(long modelHandle, String animationPath) { return 0; }
        @Override public void deleteAnimation(long animationHandle) { }
        @Override public void mergeAnimation(long mergedAnimationHandle, long sourceAnimationHandle) { }
        @Override public boolean hasCameraData(long animationHandle) { return false; }
        @Override public boolean hasBoneData(long animationHandle) { return false; }
        @Override public boolean hasMorphData(long animationHandle) { return false; }
        @Override public float getAnimationMaxFrame(long animationHandle) { return 0; }
        @Override public void seekLayer(long modelHandle, long layer, float frame) { }
        @Override public void getCameraTransform(long animationHandle, float frame, ByteBuffer targetBuffer) { }
    }
}
