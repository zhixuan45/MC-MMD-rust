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

    @Test void failedMotionCannotInstallBoneParentsOrLeakThemIntoTheLegacyAttachment() throws Exception {
        fixture("prop.vmd");
        boneTimeline();
        var previous=com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridgeHolder.get();
        var nativeParents=new ArrayList<Integer>();
        com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridgeHolder.set(parentRuntime(nativeParents));
        var port=new Port(); port.tracks=false;
        var controller=new StagePropAnimationController(port);
        UUID owner=UUID.randomUUID();
        try {
            controller.begin(owner,new StagePropAssignment("fan.pmx","prop.vmd"));
            assertFalse(controller.bind(owner,new Model(22).proxy,dir,"fan.pmx"));
            controller.applyBoneParents(owner,11,22,new org.joml.Matrix4f());
            controller.stop(owner);
            assertTrue(nativeParents.isEmpty(),"Rejected VMD must not install unowned native parents; no owner exists to clear them");
            assertEquals(com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.State.DEFAULT,controller.state(owner));
        } finally {
            controller.clear();
            com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridgeHolder.set(previous);
        }
    }

    @Test void validBoneTimelineClearsBeforeTheSamePropReturnsToLegacyAttachment() throws Exception {
        fixture("prop.vmd"); fixture("plain.vmd");
        boneTimeline();
        var previous=com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridgeHolder.get();
        var nativeParents=new ArrayList<Integer>();
        com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridgeHolder.set(parentRuntime(nativeParents));
        var controller=new StagePropAnimationController(new Port());
        UUID owner=UUID.randomUUID(); var model=new Model(22);
        try {
            controller.begin(owner,new StagePropAssignment("fan.pmx","prop.vmd"));
            assertTrue(controller.bind(owner,model.proxy,dir,"fan.pmx"));
            controller.applyBoneParents(owner,11,33,new org.joml.Matrix4f());
            assertTrue(nativeParents.isEmpty(),"A session must not mutate a different cached prop instance");
            controller.applyBoneParents(owner,11,22,new org.joml.Matrix4f());
            assertEquals(List.of(1),nativeParents);
            controller.begin(owner,new StagePropAssignment("fan.pmx","plain.vmd"));
            assertEquals(List.of(1,0),nativeParents,"Previous bone parents must clear before the same native prop is reused");
            assertTrue(controller.bind(owner,model.proxy,dir,"fan.pmx"));
            controller.applyBoneParents(owner,11,22,new org.joml.Matrix4f());
            assertEquals(List.of(1,0),nativeParents,"Legacy whole-model attachment must not install bone parents");
        } finally {
            controller.clear();
            com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridgeHolder.set(previous);
        }
    }

    @Test void failedBindingKeepsControlsInactiveAfterSeekAndRepeatedDraws() throws Exception {
        fixture("prop.vmd"); boneTimeline();
        for(String failure:List.of("zero","empty","mutation")) {
            var port=new Port(); var model=new Model(22);
            port.zero=failure.equals("zero");
            port.tracks=!failure.equals("empty");
            model.fail=failure.equals("mutation");
            var controller=new StagePropAnimationController(port);
            UUID owner=UUID.randomUUID();
            try {
                controller.begin(owner,new StagePropAssignment("fan.pmx","prop.vmd"));
                assertFalse(controller.bind(owner,model.proxy,dir,"fan.pmx"));
                controller.seek(owner,30);
                assertFalse(controller.bind(owner,model.proxy,dir,"fan.pmx"));
                assertEquals(com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.State.DEFAULT,controller.state(owner),failure);
                assertEquals(1,port.loadModels.size(),"Failed binding retries on a new session rather than each draw");
            } finally { controller.clear(); }
        }
    }

    private void boneTimeline() throws Exception {
        var binding=new com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.BoneBinding("Center","Hand",
                com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Offset.IDENTITY,
                com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.identityCompensation(),true,false);
        var timeline=new com.shiroha.mmdskin.stage.domain.timeline.PropTimeline(
                com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.State.DEFAULT,List.of(),List.of(),
                List.of(new com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Key<>(0,binding)));
        com.shiroha.mmdskin.asset.catalog.PropTimelineFiles.save(
                com.shiroha.mmdskin.asset.catalog.PropTimelineFiles.open(dir.resolve("prop.vmd"),"fan.pmx"),timeline);
    }

    private static com.shiroha.mmdskin.bridge.runtime.NativeRuntimePort parentRuntime(List<Integer> calls) {
        return (com.shiroha.mmdskin.bridge.runtime.NativeRuntimePort)Proxy.newProxyInstance(
                StagePropAnimationControllerTest.class.getClassLoader(),
                new Class<?>[]{com.shiroha.mmdskin.bridge.runtime.NativeRuntimePort.class},(p,m,args) -> {
                    return switch(m.getName()) {
                        case "getBoneNames" -> "[\"Center\",\"Hand\"]";
                        case "getPropBonePose" -> {
                            var buffer=(ByteBuffer)args[2];
                            new org.joml.Matrix4f().get(0,buffer); new org.joml.Matrix4f().get(64,buffer);
                            yield true;
                        }
                        case "setPropBoneParents" -> { calls.add(((int[])args[1]).length); yield true; }
                        default -> throw new AssertionError(m.getName());
                    };
                });
    }

    @Test void crossFolderReferenceLoadsThePropsLocalMotionAndTimeline() throws Exception {
        fixture("prop.vmd");
        Files.writeString(dir.resolve("prop.timeline.json"), """
                {"version":1,"motion":"prop.vmd","objects":[{"id":"fan","asset":"fan.pmx",
                "tracks":{"visibility":[{"frame":10,"value":false}]}}]}
                """);
        Port port = new Port();
        var controller = new StagePropAnimationController(port);
        var owner = UUID.randomUUID();
        var model = new Model(201);
        var assignment = new StagePropAssignment("扇子B/fan.pmx", "prop.vmd");
        assertTrue(assignment.isValid());
        controller.begin(owner, assignment);
        assertTrue(controller.bind(owner, model.proxy, dir, "扇子B/fan.pmx"));
        controller.seek(owner, 10);
        assertFalse(controller.state(owner).visible());
        controller.stop(owner);
    }

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

    @Test void timelineUsesUnclampedStageFrameForLateJoinRewindAndLoop() throws Exception {
        fixture("prop.vmd"); timeline("prop",true);
        Port port=new Port(); var controller=new StagePropAnimationController(port);
        UUID owner=UUID.randomUUID(); var model=new Model(11);
        controller.begin(owner,new StagePropAssignment("fan.pmx","prop.vmd"));
        controller.seek(owner,600);
        controller.bind(owner,model.proxy,dir,"fan.pmx");
        assertEquals(120,port.lastFrame(11));
        assertTrue(controller.state(owner).visible());
        assertEquals("左ダミー",controller.state(owner).attachment().bone());
        controller.seek(owner,950);
        assertFalse(controller.state(owner).visible());
        assertEquals(1,port.seeks.size(),"Control tracks must keep advancing after the prop VMD ends");
        controller.seek(owner,300);
        assertTrue(controller.state(owner).visible());
        assertEquals("右ダミー",controller.state(owner).attachment().bone());
        controller.seek(owner,0);
        assertFalse(controller.state(owner).visible());
        assertEquals("右ダミー",controller.state(owner).attachment().bone());
        controller.stop(owner);
        assertEquals(com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.State.DEFAULT,controller.state(owner));
    }

    @Test void changingAttachmentResetsPhysicsOnceAndRepeatedDrawsDoNotReloadTheTimeline() throws Exception {
        fixture("prop.vmd"); timeline("prop",true);
        Port port=new Port(); var controller=new StagePropAnimationController(port);
        UUID owner=UUID.randomUUID(); var model=new Model(11);
        controller.begin(owner,new StagePropAssignment("fan.pmx","prop.vmd"));
        controller.bind(owner,model.proxy,dir,"fan.pmx");
        int resets=model.resets;
        controller.seek(owner,120);
        assertEquals(resets,model.resets,"Visibility changes preserve the continuously evaluated physics");
        controller.seek(owner,480);
        assertEquals(resets+1,model.resets);
        controller.seek(owner,481);
        controller.seek(owner,481);
        assertEquals(resets+1,model.resets);
        Files.writeString(dir.resolve("prop.timeline.json"),"{");
        controller.bind(owner,model.proxy,dir,"fan.pmx");
        assertEquals("左ダミー",controller.state(owner).attachment().bone());
        controller.begin(owner,new StagePropAssignment("fan.pmx","prop.vmd"));
        controller.bind(owner,model.proxy,dir,"fan.pmx");
        assertEquals(com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.State.DEFAULT,controller.state(owner));
        controller.clear();
    }

    @Test void differentPerformersAndReplacementModelsRetainIndependentTimelineState() throws Exception {
        fixture("prop.vmd"); timeline("prop",true);
        Port port=new Port(); var controller=new StagePropAnimationController(port);
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(); var ma=new Model(11); var mb=new Model(22);
        var assignment=new StagePropAssignment("fan.pmx","prop.vmd");
        controller.begin(a,assignment); controller.begin(b,assignment);
        controller.bind(a,ma.proxy,dir,"fan.pmx"); controller.bind(b,mb.proxy,dir,"fan.pmx");
        controller.seek(a,600); controller.seek(b,50);
        assertTrue(controller.state(a).visible()); assertFalse(controller.state(b).visible());
        ma.handle=0; controller.seek(a,601);
        var replacement=new Model(33);
        controller.bind(a,replacement.proxy,dir,"fan.pmx");
        assertEquals("左ダミー",controller.state(a).attachment().bone());
        assertFalse(controller.state(b).visible());
        controller.clear();
    }

    @Test void malformedTimelineFallsBackWithoutBreakingMotionAndInvalidSeekKeepsItsState() throws Exception {
        fixture("prop.vmd");
        Files.writeString(dir.resolve("prop.timeline.json"),"{");
        Port port=new Port(); var controller=new StagePropAnimationController(port);
        UUID owner=UUID.randomUUID(); var model=new Model(11);
        controller.begin(owner,new StagePropAssignment("fan.pmx","prop.vmd"));
        assertTrue(controller.bind(owner,model.proxy,dir,"fan.pmx"));
        controller.seek(owner,60);
        for (float frame:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1}) controller.seek(owner,frame);
        assertEquals(60,port.lastFrame(11));
        assertEquals(com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.State.DEFAULT,controller.state(owner));
        controller.clear();
    }

    private void timeline(String name,boolean initiallyHidden) throws Exception {
        Files.writeString(dir.resolve(name+".timeline.json"),"""
                {"version":1,"objects":[{"id":"fan","asset":"fan.pmx",
                  "initial":{"visible":%s},
                  "tracks":{
                    "visibility":[{"frame":120,"value":true},{"frame":900,"value":false}],
                    "attachment":[
                      {"frame":0,"parent":{"object":"actor","bone":"右ダミー"}},
                      {"frame":480,"parent":{"object":"actor","bone":"左ダミー"}}]
                  }}]}
                """.formatted(!initiallyHidden));
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
