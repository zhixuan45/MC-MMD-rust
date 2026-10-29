package com.shiroha.mmdskin.stage.client.sync;

import com.shiroha.mmdskin.bridge.runtime.NativeAnimationPort;
import com.shiroha.mmdskin.stage.client.camera.port.StageFrameQueryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void remoteFramesAndStopAlsoDriveAndReleaseTheIndependentProp(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("prop.vmd"),new byte[]{1});
        RecordingAnimationPort port=new RecordingAnimationPort(); port.loadProps=true;
        StageAnimSyncHelper helper=new StageAnimSyncHelper(new NoopFrameQueryPort(),port);
        UUID owner=UUID.randomUUID(); putModel(helper,owner,41);
        var model=(com.shiroha.mmdskin.model.runtime.ModelInstance)Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{com.shiroha.mmdskin.model.runtime.ModelInstance.class},
                (p,m,args)->m.getName().equals("getModelHandle")?51L:null);
        helper.propAnimations().begin(owner,new com.shiroha.mmdskin.stage.domain.model.StagePropAssignment("prop.pmx","prop.vmd"));
        assertTrue(helper.propAnimations().bind(owner,model,dir,"prop.pmx"));
        helper.syncRemoteFrame(42);
        assertTrue(port.seeks.contains("41:42.0"));
        assertTrue(port.seeks.contains("51:42.0"));
        helper.end(owner);
        assertNull(helper.propAnimations().assignment(owner));
        assertEquals(List.of(101L),port.deleted);
    }

    @Test
    void disconnectReleasesPropMotionsEvenWhenThePropIsNotRendered(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("prop.vmd"),new byte[]{1});
        RecordingAnimationPort port=new RecordingAnimationPort(); port.loadProps=true;
        StageAnimSyncHelper helper=new StageAnimSyncHelper(new NoopFrameQueryPort(),port);
        UUID owner=UUID.randomUUID();
        var model=(com.shiroha.mmdskin.model.runtime.ModelInstance)Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{com.shiroha.mmdskin.model.runtime.ModelInstance.class},
                (p,m,args)->m.getName().equals("getModelHandle")?51L:null);
        helper.propAnimations().begin(owner,new com.shiroha.mmdskin.stage.domain.model.StagePropAssignment("prop.pmx","prop.vmd"));
        helper.propAnimations().bind(owner,model,dir,"prop.pmx");
        helper.disconnect();
        assertNull(helper.propAnimations().assignment(owner));
        assertEquals(List.of(101L),port.deleted);
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
        private boolean loadProps;
        private long next=100;
        private final List<Long> deleted=new ArrayList<>();
        private final List<String> seeks=new ArrayList<>();

        @Override
        public void setLayerSpeed(long modelHandle, long layer, float speed) {
            speeds.put(modelHandle, speed);
            lastSpeed = modelHandle + ":" + layer + ":" + speed;
        }

        @Override public long loadAnimation(long modelHandle, String animationPath) { return loadProps?++next:0; }
        @Override public void deleteAnimation(long animationHandle) { deleted.add(animationHandle); }
        @Override public void mergeAnimation(long mergedAnimationHandle, long sourceAnimationHandle) { }
        @Override public boolean hasCameraData(long animationHandle) { return false; }
        @Override public boolean hasBoneData(long animationHandle) { return loadProps; }
        @Override public boolean hasMorphData(long animationHandle) { return false; }
        @Override public float getAnimationMaxFrame(long animationHandle) { return 120; }
        @Override public void seekLayer(long modelHandle, long layer, float frame) { seeks.add(modelHandle+":"+frame); }
        @Override public void getCameraTransform(long animationHandle, float frame, ByteBuffer targetBuffer) { }
    }
}
