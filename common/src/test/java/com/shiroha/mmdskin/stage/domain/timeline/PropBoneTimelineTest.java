package com.shiroha.mmdskin.stage.domain.timeline;

import com.shiroha.mmdskin.ui.selector.timeline.PropTimelineEditor;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.*;
import static org.junit.jupiter.api.Assertions.*;

class PropBoneTimelineTest {
    private static BoneBinding binding(String child,String target) {
        return new BoneBinding(child,target,Offset.IDENTITY,identityCompensation(),true,false);
    }
    @Test void sameFrameBonesPersistIndependentlyAcrossSeekRebindAndUnbind() {
        var timeline=new PropTimeline(new State(false,null),List.of(new Key<>(100,true)),List.of(),
                List.of(new Key<>(0,binding("Center","Right")),new Key<>(3098,binding("Root","Right")),
                        new Key<>(3098,binding("Center","Right")),new Key<>(3099,binding("Root","Right")),
                        new Key<>(3099,binding("Center","Left")),new Key<>(3168,binding("FingerPivot","Index3")),
                        new Key<>(3370,BoneBinding.detached("Root"))));
        assertEquals(2,timeline.evaluate(3098).boneBindings().size());
        assertEquals("Left",timeline.evaluate(3099).boneBindings().get("Center").bone());
        assertEquals(3,timeline.evaluate(3168).boneBindings().size(),"Other control bones must remain bound");
        assertEquals(2,timeline.evaluate(3370).boneBindings().size());
        assertEquals("Right",timeline.evaluate(0).boneBindings().get("Center").bone());
        var direct=timeline.evaluate(3200);
        for(int frame=0;frame<=3200;frame++) timeline.evaluate(frame);
        assertEquals(direct,timeline.evaluate(3200));
        assertThrows(IllegalArgumentException.class,() -> new PropTimeline(State.DEFAULT,List.of(),List.of(),
                List.of(new Key<>(0,binding("Root","R")),new Key<>(0,binding("Root","L")))));
    }
    @Test void editorUpdatesOnlySelectedBoneAndKeepsRegistrationOrderAndBaseline() {
        var editor=new PropTimelineEditor(EMPTY); editor.motionEnd(4000);
        editor.seek(3098);
        editor.boneBinding(binding("Root","R")); editor.boneBinding(binding("Center","R"));
        assertEquals(2,editor.snapshot().boneKeys().size());
        assertEquals(List.of("Root"),editor.beforeRegistration(3098,"Center").boneKeys().stream().map(k -> k.value().childBone()).toList());
        assertTrue(editor.beforeRegistration(3098,"Root").boneKeys().isEmpty());
        editor.boneBinding(binding("Center","L"));
        assertEquals("Root",editor.snapshot().boneKeys().get(0).value().childBone());
        editor.seek(3167); editor.boneBinding(binding("Center","L"));
        assertEquals(3,editor.snapshot().boneKeys().size(),"Same target at a later frame is a distinct registration");
        editor.seek(3098); editor.selectedBone("Root");
        editor.delete(PropTimelineEditor.Track.ATTACHMENT);
        assertEquals("Center",editor.snapshot().boneKeys().get(0).value().childBone());
        assertTrue(editor.compensationStale());
        editor.replaceCalibrated(editor.snapshot().boneKeys());
        assertFalse(editor.compensationStale());
    }
}
