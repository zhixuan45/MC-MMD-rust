package com.shiroha.mmdskin.ui.selector.timeline;

import com.shiroha.mmdskin.bridge.runtime.NativeAnimationPort;
import com.shiroha.mmdskin.model.runtime.ModelInstance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreviewMotionBindingTest {
    @Test void actorAndPropUseSeparateHandlesAndHoldTheirRespectiveLastFrames() {
        var port = new Port();
        var actor = new Model(11); var prop = new Model(22);
        try (var a = new PreviewMotionBinding(port,actor.instance,Path.of("dance.vmd"));
             var p = new PreviewMotionBinding(port,prop.instance,Path.of("fan.vmd"))) {
            a.seek(600); p.seek(600);
            assertEquals(List.of("11:600.0","22:120.0"),port.seeks);
            a.seek(600); p.seek(900);
            assertEquals(2,port.seeks.size());
            a.seek(0); p.seek(0);
            assertEquals(0,actor.speed); assertFalse(actor.loop);
            assertEquals(0,prop.speed); assertFalse(prop.loop);
        }
        assertEquals(List.of(2L,1L),port.deleted);
        assertEquals(0,actor.animation); assertEquals(0,prop.animation);
        assertEquals(List.of(11L,22L),port.loadedModels);
    }

    @Test void staticActorNeedsNoMotionHandleAndDisposalIsIdempotent() {
        var port = new Port(); var actor = new Model(11);
        var binding = new PreviewMotionBinding(port,actor.instance,null);
        binding.seek(900); binding.close(); binding.close();
        assertEquals(0,binding.endFrame());
        assertTrue(port.loadedModels.isEmpty()); assertTrue(port.deleted.isEmpty());
        assertEquals(0,actor.animation);
    }

    @Test void failedMotionAndFreedModelsDoNotLeakOrReceiveNativeMutations() {
        var port = new Port(); var actor = new Model(11);
        port.tracks = false;
        assertThrows(IllegalArgumentException.class,
                () -> new PreviewMotionBinding(port,actor.instance,Path.of("bad.vmd")));
        assertEquals(List.of(1L),port.deleted);
        port.tracks = true;
        var binding = new PreviewMotionBinding(port,actor.instance,Path.of("dance.vmd"));
        actor.handle = 0;
        assertThrows(IllegalStateException.class,() -> binding.seek(20));
        binding.close(); binding.close();
        assertEquals(List.of(1L,2L),port.deleted);
    }

    private static final class Model {
        long handle,animation;
        float speed = 1;
        boolean loop = true;
        final ModelInstance instance;
        Model(long handle) {
            this.handle = handle;
            instance = (ModelInstance)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ModelInstance.class},
                    (p,m,a) -> {
                        if (m.getName().equals("getModelHandle")) return this.handle;
                        assertNotEquals(0,this.handle);
                        switch (m.getName()) {
                            case "changeAnim" -> { if ((long)a[1]==0) animation=(long)a[0]; }
                            case "setLayerLoop" -> loop=(boolean)a[1];
                            case "setLayerSpeed" -> speed=(float)a[1];
                            case "resetPhysics" -> { }
                            default -> throw new AssertionError(m.getName());
                        }
                        return null;
                    });
        }
    }
    private static final class Port implements NativeAnimationPort {
        long next;
        boolean tracks = true;
        final List<Long> loadedModels = new ArrayList<>(),deleted = new ArrayList<>();
        final List<String> seeks = new ArrayList<>();
        @Override public long loadAnimation(long model,String path) { loadedModels.add(model); return ++next; }
        @Override public void deleteAnimation(long handle) { assertFalse(deleted.contains(handle)); deleted.add(handle); }
        @Override public boolean hasBoneData(long h) { return tracks; }
        @Override public boolean hasMorphData(long h) { return false; }
        @Override public boolean hasCameraData(long h) { return false; }
        @Override public float getAnimationMaxFrame(long h) { return h==1?900:120; }
        @Override public void seekLayer(long model,long layer,float frame) { seeks.add(model+":"+frame); }
        @Override public void mergeAnimation(long a,long b) { fail(); }
        @Override public void getCameraTransform(long a,float f,ByteBuffer b) { fail(); }
    }
}
