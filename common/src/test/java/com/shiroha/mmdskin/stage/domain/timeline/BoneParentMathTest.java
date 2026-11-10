package com.shiroha.mmdskin.stage.domain.timeline;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoneParentMathTest {
    @Test void compensationAndParentFramePreserveRegistrationAndLaterMotion() {
        Matrix4f target=new Matrix4f().translation(4,5,6).rotateY(.4f);
        Matrix4f local=new Matrix4f().translation(1,2,3).rotateZ(.3f);
        Matrix4f global=new Matrix4f().translation(-2,8,1).rotateX(.7f);
        var compensation=BoneParentMath.compensation(target,global,local,true);
        var binding=new PropTimeline.BoneBinding("Center","Hand",PropTimeline.Offset.IDENTITY,compensation,true,false);
        assertTrue(BoneParentMath.parentFrame(target,binding).mul(local).equals(global,1e-5f));
        Matrix4f next=new Matrix4f(local).rotateY(.5f);
        assertTrue(BoneParentMath.parentFrame(target,binding).mul(next)
                .equals(new Matrix4f(global).mul(new Matrix4f(local).invert()).mul(next),1e-5f));
    }

    @Test void invalidOriginsAndPropTransformsAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> BoneParentMath.animationLocal(new Matrix4f(),new Matrix4f().zero()));
        assertThrows(IllegalArgumentException.class,() -> BoneParentMath.targetInProp(new Matrix4f().zero(),new Matrix4f()));
    }
}
