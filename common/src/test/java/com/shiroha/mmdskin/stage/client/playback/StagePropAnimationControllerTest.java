package com.shiroha.mmdskin.stage.client.playback;

import com.shiroha.mmdskin.bridge.runtime.NativeAnimationPort;
import com.shiroha.mmdskin.model.runtime.ModelInstance;
import com.shiroha.mmdskin.stage.domain.model.StagePropAssignment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StagePropAnimationControllerTest {
    @TempDir Path dir;

    @Test void lateModelJoinsCurrentFrameAndShortMotionHoldsItsLastFrame() throws Exception {
        fixture("prop.vmd");
        Port port=new Port(); var controller=new StagePropAnimationController(port);
        UUID owner=UUID.randomUUID(); var model=new Model(201);
        controller.begin(owner,new StagePropAssignment("fan.pmx","prop.vmd"));
        controller.seek(owner,90);
        assertTrue(controller.bind(owner,model.proxy,dir,"fan.pmx"));
        assertEquals(List.of(201L),port.loadModels);
        assertEquals(90,port.lastFrame(201));
        assertEquals(0,model.speed); assertFalse(model.loop);
        for (int i=0;i<5;i++) assertTrue(controller.bind(owner,model.proxy,dir,"fan.pmx"));
        assertEquals(1,port.loadModels.size());
        controller.seek(owner,150); controller.seek(owner,180);
        assertEquals(120,port.lastFrame(201));
        assertEquals(2,port.seeks.size());
        controller.stop(owner); controller.stop(owner);
        assertEquals(0,model.animation); assertEquals(1,model.speed); assertTrue(model.loop);
        assertEquals(Set.of(101L),port.deleted);
        assertNull(controller.assignment(owner));
    }

    @Test void replayAndRewindResetPhysicsAndDoNotReuseThePreviousStageHandle() throws Exception {
        fixture("prop.vmd");
        Port port=new Port(); var controller=new StagePropAnimationController(port);
        UUID owner=UUID.randomUUID(); var model=new Model(11);
        StagePropAssignment assignment=new StagePropAssignment("fan.pmx","prop.vmd");
        controller.begin(owner,assignment); controller.bind(owner,model.proxy,dir,"fan.pmx");
        controller.seek(owner,60); int resets=model.resets;
        controller.seek(owner,10); assertEquals(resets+1,model.resets);
        controller.begin(owner,assignment);
        assertEquals(Set.of(101L),port.deleted);
        controller.bind(owner,model.proxy,dir,"fan.pmx");
        assertEquals(102,model.animation); assertEquals(0,port.lastFrame(11));
        controller.clear(); assertEquals(Set.of(101L,102L),port.deleted);
    }

    @Test void differentOwnersHaveIndependentModelsClocksAndAnimationHandles() throws Exception {
        fixture("a.vmd"); fixture("b.vmd");
        Port port=new Port(); var controller=new StagePropAnimationController(port);
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(); var ma=new Model(11); var mb=new Model(22);
        controller.begin(a,new StagePropAssignment("a.pmx","a.vmd"));
        controller.begin(b,new StagePropAssignment("b.pmx","b.vmd"));
        assertFalse(controller.bind(a,ma.proxy,dir,"wrong.pmx"));
        assertTrue(controller.bind(a,ma.proxy,dir,"a.pmx"));
        assertTrue(controller.bind(b,mb.proxy,dir,"b.pmx"));
        controller.seek(a,70); controller.seek(b,25);
        assertEquals(70,port.lastFrame(11)); assertEquals(25,port.lastFrame(22));
        controller.stop(a); assertEquals(102,mb.animation); assertNotNull(controller.assignment(b));
        controller.clear(); assertEquals(Set.of(101L,102L),port.deleted);
    }

    @Test void failedAssetsAreNotReloadedOnEveryDrawAndRetryOnTheNextStage() throws Exception {
        Port port=new Port(); var controller=new StagePropAnimationController(port);
        UUID owner=UUID.randomUUID(); var model=new Model(11);
        StagePropAssignment assignment=new StagePropAssignment("fan.pmx","missing.vmd");
        controller.begin(owner,assignment);
        for (int i=0;i<5;i++) assertFalse(controller.bind(owner,model.proxy,dir,"fan.pmx"));
        assertTrue(port.loadModels.isEmpty());
        fixture("missing.vmd"); controller.begin(owner,assignment); port.zero=true;
        for (int i=0;i<5;i++) assertFalse(controller.bind(owner,model.proxy,dir,"fan.pmx"));
        assertEquals(1,port.loadModels.size());
        controller.begin(owner,assignment); port.zero=false;
        assertTrue(controller.bind(owner,model.proxy,dir,"fan.pmx"));
        controller.clear();
    }

    @Test void disposedOrReplacedModelsReleaseTheOldMotionWithoutTouchingFreedModelHandles() throws Exception {
        fixture("prop.vmd");
        Port port=new Port(); var controller=new StagePropAnimationController(port);
        UUID owner=UUID.randomUUID(); var old=new Model(11);
        controller.begin(owner,new StagePropAssignment("fan.pmx","prop.vmd"));
        controller.bind(owner,old.proxy,dir,"fan.pmx"); controller.seek(owner,45);
        old.handle=0;
        controller.seek(owner,46);
        assertEquals(Set.of(101L),port.deleted);
        var next=new Model(22);
        assertTrue(controller.bind(owner,next.proxy,dir,"fan.pmx"));
        assertEquals(46,port.lastFrame(22));
        controller.clear(); assertEquals(Set.of(101L,102L),port.deleted);
    }

    @Test void unusableMotionAndBindingFailureReleaseTheirHandlesExactlyOnce() throws Exception {
        fixture("prop.vmd");
        Port port=new Port(); var controller=new StagePropAnimationController(port);
        UUID owner=UUID.randomUUID(); var model=new Model(11);
        var assignment=new StagePropAssignment("fan.pmx","prop.vmd");
        controller.begin(owner,assignment); port.tracks=false;
        assertFalse(controller.bind(owner,model.proxy,dir,"fan.pmx"));
        assertEquals(0,model.animation); assertEquals(Set.of(101L),port.deleted);
        controller.begin(owner,assignment); port.tracks=true; model.fail=true;
        assertFalse(controller.bind(owner,model.proxy,dir,"fan.pmx"));
        controller.stop(owner); assertEquals(Set.of(101L,102L),port.deleted);
    }

    private void fixture(String name) throws Exception { Files.write(dir.resolve(name),new byte[]{1}); }

    private static final class Model {
        long handle,animation; float speed=1; boolean loop=true,fail; int resets;
        final ModelInstance proxy;
        Model(long handle) {
            this.handle=handle;
            proxy=(ModelInstance)Proxy.newProxyInstance(ModelInstance.class.getClassLoader(),new Class<?>[]{ModelInstance.class},(p,m,args)->{
                if (m.getName().equals("getModelHandle")) return this.handle;
                assertNotEquals(0,this.handle,"A disposed model must never receive native mutations");
                switch (m.getName()) {
                    case "changeAnim" -> { if (fail) throw new IllegalStateException("bind failed"); animation=(long)args[0]; }
                    case "setLayerSpeed" -> speed=(float)args[1];
                    case "setLayerLoop" -> loop=(boolean)args[1];
                    case "resetPhysics" -> resets++;
                    default -> throw new AssertionError(m.getName());
                }
                return null;
            });
        }
    }
    private record Seek(long model,float frame) { }
    private static final class Port implements NativeAnimationPort {
        long next=100; boolean tracks=true,zero;
        final List<Long> loadModels=new ArrayList<>(); final Set<Long> deleted=new HashSet<>(); final List<Seek> seeks=new ArrayList<>();
        float lastFrame(long model) { return seeks.stream().filter(s->s.model==model).reduce((a,b)->b).orElseThrow().frame; }
        @Override public long loadAnimation(long model,String path) { loadModels.add(model); return zero?0:++next; }
        @Override public void deleteAnimation(long animation) { assertTrue(deleted.add(animation),"Animation deleted twice"); }
        @Override public void mergeAnimation(long a,long b) { fail("Prop motion must not merge into the character motion"); }
        @Override public boolean hasCameraData(long animation) { return false; }
        @Override public boolean hasBoneData(long animation) { return tracks; }
        @Override public boolean hasMorphData(long animation) { return false; }
        @Override public float getAnimationMaxFrame(long animation) { return 120; }
        @Override public void seekLayer(long model,long layer,float frame) { assertEquals(0,layer); seeks.add(new Seek(model,frame)); }
        @Override public void getCameraTransform(long a,float frame,ByteBuffer output) { fail("Prop animation must not drive the camera"); }
    }
}
