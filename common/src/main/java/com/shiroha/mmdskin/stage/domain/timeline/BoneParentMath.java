package com.shiroha.mmdskin.stage.domain.timeline;

import org.joml.Matrix4f;
import java.util.List;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.BoneBinding;

/** Pure matrix calculations for prop bone bindings. */
public final class BoneParentMath {
    private BoneParentMath() {}
    public static Matrix4f animationLocal(Matrix4f local,Matrix4f origin) {
        // Only remove the PMX rest offset. Registration-frame VMD values remain authored motion.
        return inverseOrigin(origin).mul(local);
    }
    public static Matrix4f inverseOrigin(Matrix4f origin) {
        if(!origin.isFinite() || origin.determinant()<=1e-8f) {
            throw new IllegalArgumentException("Native bone origin is unavailable; update the native library");
        }
        return new Matrix4f(origin).invert();
    }

    public static Matrix4f targetInProp(Matrix4f propToActor,Matrix4f actorBone) {
        if(!propToActor.isFinite() || propToActor.determinant()<=1e-8f) throw new IllegalArgumentException("Invalid prop scale");
        // Bone translations change units, but a target bone must not resize the child's VMD.
        return new Matrix4f(propToActor).invert().mul(actorBone).normalize3x3();
    }
    public static List<Float> compensation(Matrix4f target,Matrix4f global,Matrix4f animation,boolean keepWorld) {
        Matrix4f value=keepWorld?new Matrix4f(target).invert().mul(global).mul(new Matrix4f(animation).invert())
                :new Matrix4f();
        float[] data=new float[16]; value.get(data);
        return java.util.stream.IntStream.range(0,16).mapToObj(i -> data[i]).toList();
    }
    public static Matrix4f parentFrame(Matrix4f target,BoneBinding binding) {
        float[] data=new float[16];
        for(int i=0;i<16;i++) data[i]=binding.compensation().get(i);
        return withOffset(new Matrix4f(target).mul(new Matrix4f().set(data)),binding.offset());
    }
    private static Matrix4f withOffset(Matrix4f matrix,PropTimeline.Offset offset) {
        float radians=(float)(Math.PI/180);
        return matrix.translate(offset.x(),offset.y(),offset.z())
                .rotateXYZ(offset.rotationX()*radians,offset.rotationY()*radians,offset.rotationZ()*radians);
    }
    public static Matrix4f placementPose(Matrix4f target,PropTimeline.Offset placement) {
        return withOffset(new Matrix4f(target),placement);
    }
    public static BoneBinding registration(String child,String parent,Matrix4f target,Matrix4f[] pose,
                                           PropTimeline.Offset offset,boolean keepWorld,PropTimeline.Offset placement) {
        Matrix4f desired=placement==null?pose[0]:placementPose(target,placement);
        return new BoneBinding(child,parent,offset,
                compensation(target,desired,animationLocal(pose[1],pose[2]),keepWorld),keepWorld,false,placement);
    }
}
