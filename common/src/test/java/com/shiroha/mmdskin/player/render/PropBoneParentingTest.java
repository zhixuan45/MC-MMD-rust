package com.shiroha.mmdskin.player.render;

import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PropBoneParentingTest {
    @Test void repeatedDrawsDoNotInvalidateTheSameNativePoseInput() {
        var calls=new java.util.ArrayList<int[]>();
        var matrices=(com.shiroha.mmdskin.bridge.runtime.NativeMatrixPort)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{com.shiroha.mmdskin.bridge.runtime.NativeMatrixPort.class},
                (proxy,method,args) -> {
                    if(method.getName().equals("setPropBoneParents")) { calls.add(((int[])args[1]).clone()); return true; }
                    if(method.getName().equals("getPropBonePose")) {
                        java.nio.ByteBuffer buffer=(java.nio.ByteBuffer)args[2];
                        new Matrix4f().get(0,buffer); new Matrix4f().get(64,buffer); return true;
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

    @Test void snapAlignsBoneOriginAndCoordinateConversionCancelsMovingOuterAttachment() {
        Matrix4f local=new Matrix4f().translation(0,3,0).rotateZ(.5f);
        Matrix4f target=new Matrix4f().translation(5,6,7).rotateY(.4f);
        var snap=new PropTimeline.BoneBinding("Center","Hand",PropTimeline.Offset.IDENTITY,
                PropBoneParenting.compensation(target,new Matrix4f(),local,false),false,false);
        assertTrue(PropBoneParenting.parentFrame(target,snap).mul(local).equals(target,1e-5f));
        Matrix4f base=new Matrix4f().translation(10,20,30).rotateY(.9f).scale(.9f);
        Matrix4f inProp=PropBoneParenting.targetInProp(base,target);
        assertEquals(1,inProp.determinant(),1e-5f);
        var position=inProp.getTranslation(new org.joml.Vector3f());
        base.transformPosition(position);
        assertTrue(position.equals(target.getTranslation(new org.joml.Vector3f()),1e-4f));
        assertThrows(IllegalArgumentException.class,() -> PropBoneParenting.targetInProp(new Matrix4f().zero(),target));
    }
}
