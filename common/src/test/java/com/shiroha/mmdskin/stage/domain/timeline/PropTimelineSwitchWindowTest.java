package com.shiroha.mmdskin.stage.domain.timeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.*;
import static org.junit.jupiter.api.Assertions.*;

class PropTimelineSwitchWindowTest {
    private static BoneBinding bind(String child,String target) {
        return new BoneBinding(child,target,Offset.IDENTITY,identityCompensation(),true,false);
    }

    private static BoneBinding bind(String child,String target,Offset offset,boolean keepWorld,Offset placement) {
        return new BoneBinding(child,target,offset,identityCompensation(),keepWorld,false,placement);
    }

    private static BoneBinding detach(String child) {
        return BoneBinding.detached(child);
    }

    @Test void onlyFrameZeroKeepsTheDefaultWindow() {
        var timeline=new PropTimeline(State.DEFAULT,List.of(),List.of(),
                List.of(new Key<>(0,bind("Center","Right"))));

        assertEquals(BoneWindow.DEFAULT,timeline.outsideParentWindow("Center",0));
        assertEquals(BoneWindow.DEFAULT,timeline.outsideParentWindow("Center",100));
        assertEquals(Map.of("Center",BoneWindow.DEFAULT),timeline.outsideParentWindows(0));
        assertFalse(timeline.outsideParentWindow("Center",0).active());
    }

    @Test void completeTimelineReportsEveryBoneAcrossSameTargetSwitchUnbindAndRebind() {
        var timeline=new PropTimeline(State.DEFAULT,List.of(),List.of(),
                List.of(new Key<>(0,bind("Center","Right")),
                        new Key<>(3098,bind("Root","Right")),
                        new Key<>(3098,bind("Center","Right")),
                        new Key<>(3099,bind("Root","Right")),
                        new Key<>(3099,bind("Center","Left")),
                        new Key<>(3168,bind("FingerPivot","Index3")),
                        new Key<>(3370,detach("Root")),
                        new Key<>(3375,bind("Center","Right"))));

        assertEquals(new BoneWindow(0,3099),timeline.outsideParentWindow("Center",0));
        assertEquals(new BoneWindow(3098,3370),timeline.outsideParentWindow("Root",0));
        assertEquals(new BoneWindow(3168,0),timeline.outsideParentWindow("FingerPivot",0));

        assertEquals(new BoneWindow(0,3099),timeline.outsideParentWindow("Center",3098));
        assertEquals(new BoneWindow(3098,3370),timeline.outsideParentWindow("Root",3098));

        assertEquals(new BoneWindow(3099,3375),timeline.outsideParentWindow("Center",3099));
        assertEquals(new BoneWindow(3098,3370),timeline.outsideParentWindow("Root",3099));

        assertEquals(new BoneWindow(3099,3375),timeline.outsideParentWindow("Center",3168));
        assertEquals(new BoneWindow(3168,0),timeline.outsideParentWindow("FingerPivot",3168));

        assertEquals(new BoneWindow(3370,0),timeline.outsideParentWindow("Root",3370));
        assertEquals(new BoneWindow(3099,3375),timeline.outsideParentWindow("Center",3370));

        assertEquals(new BoneWindow(3375,0),timeline.outsideParentWindow("Center",3375));
        assertEquals(new BoneWindow(3370,0),timeline.outsideParentWindow("Root",3375));

        var windows=timeline.outsideParentWindows(3400);
        assertEquals(new BoneWindow(3375,0),windows.get("Center"));
        assertEquals(new BoneWindow(3370,0),windows.get("Root"));
        assertEquals(new BoneWindow(3168,0),windows.get("FingerPivot"));

        assertEquals("Left",timeline.evaluate(3099).boneBindings().get("Center").bone());
        assertEquals("Right",timeline.evaluate(3099).boneBindings().get("Root").bone());
        assertEquals(3,timeline.evaluate(3168).boneBindings().size());
        assertFalse(timeline.evaluate(3370).boneBindings().containsKey("Root"));
        assertEquals("Right",timeline.evaluate(3375).boneBindings().get("Center").bone());
    }

    @Test void futureUnbindStillProvidesAWindowAndUnboundBonesRemainInTheMap() {
        var timeline=new PropTimeline(State.DEFAULT,List.of(),List.of(),
                List.of(new Key<>(0,bind("Center","Right")),
                        new Key<>(60,detach("Center"))));

        assertEquals(new BoneWindow(0,60),timeline.outsideParentWindow("Center",0));
        assertEquals(new BoneWindow(0,60),timeline.outsideParentWindow("Center",30));
        assertEquals(new BoneWindow(60,0),timeline.outsideParentWindow("Center",60));
        assertEquals(new BoneWindow(60,0),timeline.outsideParentWindow("Center",90));
        assertTrue(timeline.outsideParentWindows(90).containsKey("Center"));
        assertTrue(timeline.evaluate(90).boneBindings().isEmpty());
    }

    @Test void sameTargetRegistrationsDoNotCreateSwitchFrames() {
        var timeline=new PropTimeline(State.DEFAULT,List.of(),List.of(),
                List.of(new Key<>(0,bind("Center","Right")),
                        new Key<>(30,bind("Center","Right",new Offset(1,0,0,0,0,0),false,null)),
                        new Key<>(60,bind("Center","Right",Offset.IDENTITY,true,new Offset(0,1,0,0,0,0)))));

        assertEquals(BoneWindow.DEFAULT,timeline.outsideParentWindow("Center",15));
        assertEquals(BoneWindow.DEFAULT,timeline.outsideParentWindow("Center",45));
        assertEquals(BoneWindow.DEFAULT,timeline.outsideParentWindow("Center",90));
        assertEquals(BoneWindow.DEFAULT,timeline.outsideParentWindows(45).get("Center"));
    }

    @Test void differentTargetAndRebindAreSwitches() {
        var timeline=new PropTimeline(State.DEFAULT,List.of(),List.of(),
                List.of(new Key<>(0,bind("Center","Right")),
                        new Key<>(60,bind("Center","Left")),
                        new Key<>(120,bind("Center","Right"))));

        assertEquals(new BoneWindow(0,60),timeline.outsideParentWindow("Center",30));
        assertEquals(new BoneWindow(60,120),timeline.outsideParentWindow("Center",60));
        assertEquals(new BoneWindow(60,120),timeline.outsideParentWindow("Center",90));
        assertEquals(new BoneWindow(120,0),timeline.outsideParentWindow("Center",120));
        assertEquals(new BoneWindow(120,0),timeline.outsideParentWindow("Center",200));
    }

    @Test void fractionalFramesUseTheSegmentAtOrBeforeTheFrame() {
        var timeline=new PropTimeline(State.DEFAULT,List.of(),List.of(),
                List.of(new Key<>(0,bind("Center","Right")),
                        new Key<>(60,bind("Center","Left"))));

        assertEquals(new BoneWindow(0,60),timeline.outsideParentWindow("Center",0.5f));
        assertEquals(new BoneWindow(0,60),timeline.outsideParentWindow("Center",59.999f));
        assertEquals(new BoneWindow(60,0),timeline.outsideParentWindow("Center",60f));
        assertEquals(new BoneWindow(60,0),timeline.outsideParentWindow("Center",60.5f));

        var future=new PropTimeline(State.DEFAULT,List.of(),List.of(),
                List.of(new Key<>(10,bind("Center","Right")),
                        new Key<>(20,bind("Center","Left"))));
        assertEquals(new BoneWindow(10,20),future.outsideParentWindow("Center",9.5f));
        assertEquals(new BoneWindow(10,20),future.outsideParentWindow("Center",10f));
        assertEquals(new BoneWindow(20,0),future.outsideParentWindow("Center",20f));
    }

    @Test void bonesWithoutBindingHistoryHaveNoWindow() {
        var timeline=new PropTimeline(State.DEFAULT,List.of(),List.of(),
                List.of(new Key<>(0,detach("OnlyDetached")),
                        new Key<>(30,bind("Center","Right"))));

        assertEquals(BoneWindow.DEFAULT,timeline.outsideParentWindow("OnlyDetached",0));
        assertEquals(BoneWindow.DEFAULT,timeline.outsideParentWindow("OnlyDetached",25));
        assertEquals(BoneWindow.DEFAULT,timeline.outsideParentWindow("OnlyDetached",100));
        assertEquals(BoneWindow.DEFAULT,timeline.outsideParentWindow("Missing",0));
        assertFalse(timeline.outsideParentWindows(0).containsKey("OnlyDetached"));
        assertFalse(timeline.outsideParentWindows(0).containsKey("Missing"));
        assertTrue(timeline.outsideParentWindows(0).containsKey("Center"));

        var detachedOnly=new PropTimeline(State.DEFAULT,List.of(),List.of(),
                List.of(new Key<>(0,detach("Center"))));
        assertTrue(detachedOnly.outsideParentWindows(0).isEmpty());
        assertEquals(BoneWindow.DEFAULT,detachedOnly.outsideParentWindow("Center",0));
    }

    @Test void initialStateBindingsRemainCompatibleAndStartAtFrameZero() {
        var initial=new State(false,null,Map.of("Center",bind("Center","Right")));
        var timeline=new PropTimeline(initial,List.of(),List.of(),
                List.of(new Key<>(60,detach("Center"))));

        assertEquals(new BoneWindow(0,60),timeline.outsideParentWindow("Center",0));
        assertEquals(new BoneWindow(0,60),timeline.outsideParentWindow("Center",30));
        assertEquals(new BoneWindow(60,0),timeline.outsideParentWindow("Center",60));
        assertEquals(1,timeline.evaluate(0).boneBindings().size());
        assertTrue(timeline.evaluate(60).boneBindings().isEmpty());
        assertEquals(new State(true,null),State.DEFAULT);
    }

    @Test void stateOverloadKeepsDraftBindingsAtTheDefaultWindow() {
        var timeline=new PropTimeline(State.DEFAULT,List.of(),List.of(),
                List.of(new Key<>(60,bind("Center","Right"))));
        var draft=new State(true,null,Map.of("Draft",bind("Draft","Right")));

        var windows=timeline.outsideParentWindows(30,draft);
        assertEquals(new BoneWindow(60,0),windows.get("Center"));
        assertEquals(BoneWindow.DEFAULT,windows.get("Draft"));
    }

    @Test void invalidWindowsFramesAndDuplicateChildKeysAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> new BoneWindow(-1,0));
        assertThrows(IllegalArgumentException.class,() -> new BoneWindow(0,-1));
        assertThrows(IllegalArgumentException.class,() -> new BoneWindow(10,5));
        assertThrows(IllegalArgumentException.class,() -> new BoneWindow(10,10));
        assertThrows(IllegalArgumentException.class,() -> EMPTY.outsideParentWindow("Center",-1));
        assertThrows(IllegalArgumentException.class,() -> EMPTY.outsideParentWindow("Center",Float.NaN));
        assertThrows(IllegalArgumentException.class,() -> EMPTY.outsideParentWindows(-1));
        assertThrows(IllegalArgumentException.class,() -> new PropTimeline(State.DEFAULT,List.of(),List.of(),
                List.of(new Key<>(0,bind("Center","Right")),new Key<>(0,bind("Center","Left")))));
    }
}
