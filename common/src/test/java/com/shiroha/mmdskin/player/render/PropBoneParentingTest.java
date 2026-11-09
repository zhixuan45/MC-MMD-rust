package com.shiroha.mmdskin.player.render;

import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PropBoneParentingTest {
    @Test void placedBoneAlignsItsAnimatedPivotAndRegistersWithoutLosingLaterVmdMotion() {
        Matrix4f origin=new Matrix4f().translation(3,4,-2);
        Matrix4f animation=new Matrix4f().translation(2,-1,3).rotateXYZ(.2f,.6f,-.4f);
        Matrix4f local=new Matrix4f(origin).mul(animation);
        Matrix4f target=new Matrix4f().translation(-5,11,7).rotateY(.8f);
        Matrix4f[] pose={new Matrix4f().translation(100,-30,8).mul(local),local,origin};
        var aligned=PropBoneParenting.registration("Center","Hand",target,pose,
                PropTimeline.Offset.IDENTITY,true,PropTimeline.Offset.IDENTITY);
        assertTrue(PropBoneParenting.parentFrame(target,aligned).mul(animation)
                .equals(target,1e-5f),"Align the selected animated bone, rather than the model origin");
        var placement=new PropTimeline.Offset(.5f,-.2f,1.3f,25,-40,70);
        var draft=PropBoneParenting.registration("Center","Hand",target,pose,PropTimeline.Offset.IDENTITY,true,placement);
        Matrix4f desired=new Matrix4f(target).translate(.5f,-.2f,1.3f)
                .rotateXYZ((float)Math.toRadians(25),(float)Math.toRadians(-40),(float)Math.toRadians(70));
        Matrix4f preview=PropBoneParenting.parentFrame(target,draft).mul(animation);
        assertTrue(preview.equals(desired,1e-5f),"Fine adjustment must be applied once around the selected bone");
        assertEquals(placement,draft.placement());
        Matrix4f nextAnimation=new Matrix4f().translation(-2,5,1).rotateXYZ(-.7f,.1f,.9f);
        Matrix4f expected=new Matrix4f(desired).mul(new Matrix4f(animation).invert()).mul(nextAnimation);
        Matrix4f actual=PropBoneParenting.parentFrame(target,draft).mul(nextAnimation);
        assertTrue(actual.equals(expected,1e-5f),"Placement must retain subsequent VMD translation and rotation");
        pose[0]=new Matrix4f().translation(-90,12,6);
        assertEquals(draft,PropBoneParenting.registration("Center","Hand",target,pose,
                PropTimeline.Offset.IDENTITY,true,placement),"Recalculation must preserve the saved grip placement");
    }

    @Test void snapMustRetainAuthoredVmdTranslationAndRotationAtRegistration() {
        Matrix4f target=new Matrix4f().translation(5,6,7).rotateY(.4f);
        Matrix4f animation=new Matrix4f().translation(2,-1,3).rotateZ(.5f);
        var snap=new PropTimeline.BoneBinding("Center","Hand",PropTimeline.Offset.IDENTITY,
                PropTimeline.identityCompensation(),false,false);
        assertTrue(PropBoneParenting.parentFrame(target,snap).mul(animation)
                .equals(new Matrix4f(target).mul(animation),1e-5f),
                "Outside parenting must retain authored motion, including its registration-frame values");
    }
    @Test void repeatedDrawsDoNotInvalidateTheSameNativePoseInput() {
        var calls=new java.util.ArrayList<int[]>();
        var matrices=(com.shiroha.mmdskin.bridge.runtime.NativeMatrixPort)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{com.shiroha.mmdskin.bridge.runtime.NativeMatrixPort.class},
                (proxy,method,args) -> {
                    if(method.getName().equals("setPropBoneParents")) { calls.add(((int[])args[1]).clone()); return true; }
                    if(method.getName().equals("getPropBonePose")) {
                        java.nio.ByteBuffer buffer=(java.nio.ByteBuffer)args[2];
                        new Matrix4f().get(0,buffer); new Matrix4f().get(64,buffer);
                        new Matrix4f().get(128,buffer); return true;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var query=(com.shiroha.mmdskin.bridge.runtime.NativeModelQueryPort)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{com.shiroha.mmdskin.bridge.runtime.NativeModelQueryPort.class},
                (proxy,method,args) -> "[\"Root\",\"Hand\"]");
        var parenting=new PropBoneParenting(matrices,query);
        var binding=new PropTimeline.BoneBinding("Root","Hand",PropTimeline.Offset.IDENTITY,
                PropTimeline.identityCompensation(),true,false);
        var state=new PropTimeline.State(true,null,java.util.Map.of("Root",binding));
        parenting.apply(1,2,new Matrix4f(),state);
        parenting.apply(1,2,new Matrix4f(),state);
        assertEquals(1,calls.size(),"Shadow and main draws must reuse identical native input");
        parenting.apply(1,2,new Matrix4f().translation(1,0,0),state);
        assertEquals(2,calls.size(),"Changed attachment must invalidate input");
        parenting.apply(1,2,new Matrix4f(),PropTimeline.State.DEFAULT);
        parenting.apply(1,2,new Matrix4f(),PropTimeline.State.DEFAULT);
        assertEquals(3,calls.size(),"Clear once on unbind");
        parenting.clear(2);
        parenting.apply(1,2,new Matrix4f(),state);
        assertEquals(4,calls.size(),"New session must submit its bindings");
    }

    @Test void bakedCompensationKeepsRegistrationPoseAndRetainsLaterVmdRotation() {
        Matrix4f target=new Matrix4f().translation(8,4,-2).rotateY(.7f);
        Matrix4f global=new Matrix4f().translation(-1,2,3).rotateZ(.3f);
        Matrix4f local=new Matrix4f().translation(0,1,0).rotateX(.2f);
        var compensation=PropBoneParenting.compensation(target,global,local,true);
        var binding=new PropTimeline.BoneBinding("Center","LeftHand",PropTimeline.Offset.IDENTITY,compensation,true,false);
        assertTrue(PropBoneParenting.parentFrame(target,binding).mul(local).equals(global,1e-5f));
        Matrix4f nextLocal=new Matrix4f(local).rotateY(.5f);
        Matrix4f nextTarget=new Matrix4f(target).translate(2,0,0);
        Matrix4f expected=new Matrix4f(nextTarget).mul(new Matrix4f(target).invert()).mul(global)
                .mul(new Matrix4f(local).invert()).mul(nextLocal);
        assertTrue(PropBoneParenting.parentFrame(nextTarget,binding).mul(nextLocal).equals(expected,1e-5f));
        assertEquals(compensation,PropBoneParenting.compensation(target,global,local,true),"Re-registering an unchanged baseline must not drift");
    }

    @Test void snapRemovesOnlyStaticOriginAndZeroMotionCoincidesWithTheTarget() {
        Matrix4f origin=new Matrix4f().translation(0,3,0);
        Matrix4f animation=new Matrix4f().translation(2,-1,4).rotateZ(.5f);
        Matrix4f local=new Matrix4f(origin).mul(animation);
        Matrix4f target=new Matrix4f().translation(5,6,7).rotateY(.4f);
        var snap=new PropTimeline.BoneBinding("Center","Hand",PropTimeline.Offset.IDENTITY,
                PropTimeline.identityCompensation(),false,false);
        assertTrue(PropBoneParenting.parentFrame(target,snap).mul(PropBoneParenting.animationLocal(local,origin))
                .equals(new Matrix4f(target).mul(animation),1e-5f));
        assertTrue(PropBoneParenting.parentFrame(target,snap).mul(PropBoneParenting.animationLocal(origin,origin))
                .equals(target,1e-5f),"Zero VMD translation must coincide even when the PMX origin is nonzero");
        Matrix4f base=new Matrix4f().translation(10,20,30).rotateY(.9f).scale(.9f);
        Matrix4f inProp=PropBoneParenting.targetInProp(base,target);
        assertEquals(1,inProp.determinant(),1e-5f);
        var position=inProp.getTranslation(new org.joml.Vector3f());
        base.transformPosition(position);
        assertTrue(position.equals(target.getTranslation(new org.joml.Vector3f()),1e-4f));
        assertThrows(IllegalArgumentException.class,() -> PropBoneParenting.targetInProp(new Matrix4f().zero(),target));
        assertThrows(IllegalArgumentException.class,() -> PropBoneParenting.animationLocal(local,new Matrix4f().zero()));
    }

    @Test void automaticRegistrationUsesAnimatedLocalWithoutRestOffsetAndKeepsLaterMotion() {
        Matrix4f origin=new Matrix4f().translation(-2,3,4);
        Matrix4f animation=new Matrix4f().translation(2,1,-3).rotateXYZ(.3f,-.2f,.6f);
        Matrix4f local=new Matrix4f(origin).mul(animation);
        Matrix4f global=new Matrix4f().translation(7,2,1).rotateY(.4f).mul(local);
        Matrix4f target=new Matrix4f().translation(-6,10,3).rotateX(.7f);
        var binding=new PropTimeline.BoneBinding("Center","Hand",PropTimeline.Offset.IDENTITY,
                PropBoneParenting.compensation(target,global,PropBoneParenting.animationLocal(local,origin),true),true,false);
        assertTrue(PropBoneParenting.parentFrame(target,binding).mul(animation).equals(global,1e-5f));
        Matrix4f nextAnimation=new Matrix4f().translation(-1,5,2).rotateXYZ(-.5f,.8f,-.1f);
        Matrix4f nextTarget=new Matrix4f().translation(4,11,1).rotateY(.9f);
        Matrix4f expected=new Matrix4f(nextTarget).mul(new Matrix4f(target).invert()).mul(global)
                .mul(new Matrix4f(animation).invert()).mul(nextAnimation);
        Matrix4f actual=PropBoneParenting.parentFrame(nextTarget,binding).mul(nextAnimation);
        assertTrue(actual.equals(expected,1e-5f),"Both authored translation and rotation continue after registration");
    }

    @Test void windowChangesUpdateNativeAndResampleEvenWhenParentsAreUnchanged() {
        var calls=new Calls();
        var parenting=new PropBoneParenting(recordingMatrixPort(calls),recordingQuery());
        var state=state("Root","Hand");
        var first=java.util.Map.of("Root",new PropTimeline.BoneWindow(10,20));
        parenting.apply(1,2,new Matrix4f(),state,first);
        assertEquals(1,calls.parents.size());
        assertEquals(1,calls.windows.size());
        assertArrayEquals(new int[]{0},calls.windows.get(0));
        assertArrayEquals(new float[]{10f,20f},calls.windowValues.get(0));
        assertEquals(1,calls.evaluations);
        parenting.apply(1,2,new Matrix4f(),state,first);
        assertEquals(1,calls.parents.size(),"Identical parent input must be reused");
        assertEquals(1,calls.windows.size(),"Identical window input must be reused");
        assertEquals(1,calls.evaluations,"Unchanged windows must not force another evaluation");
        parenting.apply(1,2,new Matrix4f(),state,
                java.util.Map.of("Root",new PropTimeline.BoneWindow(10,30)));
        assertEquals(1,calls.parents.size(),"Window-only changes must not resubmit parents");
        assertEquals(2,calls.windows.size());
        assertEquals(2,calls.evaluations,"Window changes must resample the current frame");
        parenting.apply(1,2,new Matrix4f().translation(1,0,0),state,
                java.util.Map.of("Root",new PropTimeline.BoneWindow(10,30)));
        assertEquals(2,calls.parents.size());
        assertEquals(2,calls.windows.size(),"Parent-only changes must not resend independent windows");
        assertEquals(2,calls.evaluations,"Unchanged windows do not need another resample");
    }

    @Test void windowsCoverUnboundBonesAndOnlyKnownPropBonesReachJni() {
        var calls=new Calls();
        var parenting=new PropBoneParenting(recordingMatrixPort(calls),recordingQuery());
        var windows=new java.util.LinkedHashMap<String,PropTimeline.BoneWindow>();
        windows.put("Missing",new PropTimeline.BoneWindow(0,5));
        windows.put("Tail",new PropTimeline.BoneWindow(5,15));
        windows.put("Root",new PropTimeline.BoneWindow(10,20));
        parenting.apply(1,2,new Matrix4f(),state("Root","Hand"),windows);
        assertArrayEquals(new int[]{0,2},calls.windows.get(0),
                "Active and unbound windows must be merged and sorted");
        assertArrayEquals(new float[]{10f,20f,5f,15f},calls.windowValues.get(0));
        assertEquals(1,calls.parents.size());
        assertEquals(1,calls.evaluations);
    }

    @Test void unboundWindowSurvivesWithoutParentsAndClearsExplicitly() {
        var calls=new Calls();
        var parenting=new PropBoneParenting(recordingMatrixPort(calls),recordingQuery());
        var windows=java.util.Map.of("Tail",new PropTimeline.BoneWindow(0,25));
        parenting.apply(1,2,new Matrix4f(),PropTimeline.State.DEFAULT,windows);
        assertTrue(calls.parents.isEmpty(),"Unbound bones must not install external parents");
        assertArrayEquals(new int[]{2},calls.windows.get(0));
        assertArrayEquals(new float[]{0f,25f},calls.windowValues.get(0));
        assertEquals(1,calls.evaluations);
        parenting.apply(1,2,new Matrix4f(),PropTimeline.State.DEFAULT,windows);
        assertEquals(1,calls.windows.size());
        parenting.apply(1,2,new Matrix4f(),PropTimeline.State.DEFAULT,java.util.Map.of());
        assertEquals(2,calls.windows.size());
        assertArrayEquals(new int[0],calls.windows.get(1));
        assertArrayEquals(new float[0],calls.windowValues.get(1));
        assertEquals(2,calls.evaluations,"Clearing an active window must resample");
    }

    @Test void detachingAllParentsWithANewWindowStillClearsTheNativeParents() {
        var calls=new Calls();
        var parenting=new PropBoneParenting(recordingMatrixPort(calls),recordingQuery());
        parenting.apply(1,2,new Matrix4f(),state("Root","Hand"),
                java.util.Map.of("Root",new PropTimeline.BoneWindow(0,30)));
        parenting.apply(1,2,new Matrix4f(),PropTimeline.State.DEFAULT,
                java.util.Map.of("Root",new PropTimeline.BoneWindow(30,0)));
        assertEquals(2,calls.parents.size());
        assertArrayEquals(new int[0],calls.parents.get(1));
        assertEquals(2,calls.evaluations);
    }

    @Test void zeroOnlyWindowIsInactiveAndUnknownBonesAreFilteredBeforeJni() {
        var calls=new Calls();
        var parenting=new PropBoneParenting(recordingMatrixPort(calls),recordingQuery());
        var windows=new java.util.LinkedHashMap<String,PropTimeline.BoneWindow>();
        windows.put("Root",new PropTimeline.BoneWindow(0,0));
        windows.put("Missing",new PropTimeline.BoneWindow(0,5));
        parenting.apply(1,2,new Matrix4f(),PropTimeline.State.DEFAULT,windows);
        assertTrue(calls.parents.isEmpty());
        assertTrue(calls.windows.isEmpty(),"Inactive and unknown window bones must not reach JNI");
        assertEquals(0,calls.evaluations);
    }

    @Test void clearDropsWindowsAndCaches() {
        var calls=new Calls();
        var parenting=new PropBoneParenting(recordingMatrixPort(calls),recordingQuery());
        var windows=java.util.Map.of("Tail",new PropTimeline.BoneWindow(5,9));
        parenting.apply(1,2,new Matrix4f(),PropTimeline.State.DEFAULT,windows);
        assertEquals(1,calls.windows.size());
        parenting.clear(2);
        assertEquals(2,calls.windows.size());
        assertArrayEquals(new int[0],calls.windows.get(1));
        assertArrayEquals(new float[0],calls.windowValues.get(1));
        parenting.apply(1,2,new Matrix4f(),PropTimeline.State.DEFAULT,windows);
        assertEquals(3,calls.windows.size(),"clear must reset the window cache");
        assertEquals(2,calls.evaluations);
    }

    @Test void noWindowStateKeepsParentOnlyPath() {
        var calls=new Calls();
        var parenting=new PropBoneParenting(recordingMatrixPort(calls),recordingQuery());
        var state=state("Root","Hand");
        parenting.apply(1,2,new Matrix4f(),state);
        parenting.apply(1,2,new Matrix4f(),state);
        assertEquals(1,calls.parents.size());
        assertTrue(calls.windows.isEmpty());
        assertEquals(0,calls.evaluations,"Ordinary parenting must not add an extra evaluation");
    }

    @Test void failedResampleDoesNotCacheTheWindowInput() {
        var calls=new Calls();
        calls.failEvaluation=true;
        var parenting=new PropBoneParenting(recordingMatrixPort(calls),recordingQuery());
        var state=state("Root","Hand");
        var windows=java.util.Map.of("Root",new PropTimeline.BoneWindow(10,20));
        assertThrows(IllegalStateException.class,
                () -> parenting.apply(1,2,new Matrix4f(),state,windows));
        assertEquals(1,calls.parents.size());
        assertEquals(1,calls.windows.size());
        assertEquals(1,calls.evaluations);
        calls.failEvaluation=false;
        parenting.apply(1,2,new Matrix4f(),state,windows);
        assertEquals(1,calls.parents.size(),"A successful parent update is cached independently");
        assertEquals(2,calls.windows.size(),"A failed evaluation must leave the window input uncached");
        assertEquals(2,calls.evaluations);
    }

    @Test void timelineWindowsReachUnboundBonesBeforeTheirFirstBinding() {
        var calls=new Calls();
        var parenting=new PropBoneParenting(recordingMatrixPort(calls),recordingQuery());
        var binding=new PropTimeline.BoneBinding("Root","Hand",PropTimeline.Offset.IDENTITY,
                PropTimeline.identityCompensation(),true,false);
        var timeline=new PropTimeline(PropTimeline.State.DEFAULT,java.util.List.of(),java.util.List.of(),
                java.util.List.of(new PropTimeline.Key<>(10,binding),
                        new PropTimeline.Key<>(20,PropTimeline.BoneBinding.detached("Root"))));
        var state=timeline.evaluate(0);
        parenting.apply(1,2,new Matrix4f(),timeline,0,state);
        assertTrue(calls.parents.isEmpty(),"The future binding is not active at frame zero");
        assertArrayEquals(new int[]{0},calls.windows.get(0));
        assertArrayEquals(new float[]{10f,20f},calls.windowValues.get(0));
        assertEquals(1,calls.evaluations);
    }

    private static PropTimeline.State state(String child,String bone) {
        var binding=new PropTimeline.BoneBinding(child,bone,PropTimeline.Offset.IDENTITY,
                PropTimeline.identityCompensation(),true,false);
        return new PropTimeline.State(true,null,java.util.Map.of(child,binding));
    }

    private static final class Calls {
        final java.util.List<int[]> parents=new java.util.ArrayList<>();
        final java.util.List<int[]> windows=new java.util.ArrayList<>();
        final java.util.List<float[]> windowValues=new java.util.ArrayList<>();
        int evaluations;
        boolean failEvaluation;
    }

    private static com.shiroha.mmdskin.bridge.runtime.NativeMatrixPort recordingMatrixPort(Calls calls) {
        return (com.shiroha.mmdskin.bridge.runtime.NativeMatrixPort)java.lang.reflect.Proxy.newProxyInstance(
                PropBoneParentingTest.class.getClassLoader(),
                new Class<?>[]{com.shiroha.mmdskin.bridge.runtime.NativeMatrixPort.class},
                (proxy,method,args) -> {
                    switch(method.getName()) {
                        case "setPropBoneParents" -> {
                            calls.parents.add(((int[])args[1]).clone());
                            return true;
                        }
                        case "setPropBoneWindows" -> {
                            calls.windows.add(((int[])args[1]).clone());
                            calls.windowValues.add(((float[])args[2]).clone());
                            return true;
                        }
                        case "getPropBonePose" -> {
                            java.nio.ByteBuffer buffer=(java.nio.ByteBuffer)args[2];
                            new Matrix4f().get(0,buffer); new Matrix4f().get(64,buffer);
                            new Matrix4f().get(128,buffer);
                            return true;
                        }
                        case "evaluatePropPose" -> {
                            calls.evaluations++;
                            return !calls.failEvaluation;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static com.shiroha.mmdskin.bridge.runtime.NativeModelQueryPort recordingQuery() {
        return (com.shiroha.mmdskin.bridge.runtime.NativeModelQueryPort)java.lang.reflect.Proxy.newProxyInstance(
                PropBoneParentingTest.class.getClassLoader(),
                new Class<?>[]{com.shiroha.mmdskin.bridge.runtime.NativeModelQueryPort.class},
                (proxy,method,args) -> "[\"Root\",\"Hand\",\"Tail\"]");
    }

}
