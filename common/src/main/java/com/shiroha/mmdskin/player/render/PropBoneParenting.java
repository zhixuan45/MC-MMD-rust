package com.shiroha.mmdskin.player.render;

import com.shiroha.mmdskin.bridge.runtime.NativeMatrixPort;
import com.shiroha.mmdskin.bridge.runtime.NativeModelQueryPort;
import com.shiroha.mmdskin.bridge.runtime.PropBoneBindingAdapter;
import com.shiroha.mmdskin.stage.domain.timeline.BoneParentMath;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import org.joml.Matrix4f;
import java.util.List;
import java.util.Map;

/** Compatibility facade; new call sites should depend on PropBoneBindingAdapter/BoneParentMath. */
@Deprecated(forRemoval=false)
public final class PropBoneParenting {
    private final PropBoneBindingAdapter adapter;
    public PropBoneParenting(NativeMatrixPort matrices, NativeModelQueryPort query) { adapter=new PropBoneBindingAdapter(matrices,query); }
    public List<String> bones(long model) { return adapter.bones(model); }
    public Matrix4f[] pose(long model,String bone) { return adapter.pose(model,bone); }
    public boolean apply(long actor,long prop,Matrix4f transform,PropTimeline.State state) { return adapter.apply(actor,prop,transform,state); }
    public boolean apply(long actor,long prop,Matrix4f transform,PropTimeline timeline,float frame,PropTimeline.State state) { return adapter.apply(actor,prop,transform,timeline,frame,state); }
    public boolean apply(long actor,long prop,Matrix4f transform,PropTimeline.State state,Map<String,PropTimeline.BoneWindow> windows) { return adapter.apply(actor,prop,transform,state,windows); }
    public void clear(long prop) { adapter.clear(prop); }
    public static Matrix4f animationLocal(Matrix4f local,Matrix4f origin) { return BoneParentMath.animationLocal(local,origin); }
    public static Matrix4f targetInProp(Matrix4f transform,Matrix4f actorBone) { return BoneParentMath.targetInProp(transform,actorBone); }
    public static List<Float> compensation(Matrix4f target,Matrix4f global,Matrix4f animation,boolean keepWorld) { return BoneParentMath.compensation(target,global,animation,keepWorld); }
    public static Matrix4f parentFrame(Matrix4f target,PropTimeline.BoneBinding binding) { return BoneParentMath.parentFrame(target,binding); }
    public static Matrix4f placementPose(Matrix4f target,PropTimeline.Offset placement) { return BoneParentMath.placementPose(target,placement); }
    public static PropTimeline.BoneBinding registration(String child,String parent,Matrix4f target,Matrix4f[] pose,PropTimeline.Offset offset,boolean keepWorld,PropTimeline.Offset placement) { return BoneParentMath.registration(child,parent,target,pose,offset,keepWorld,placement); }
}
