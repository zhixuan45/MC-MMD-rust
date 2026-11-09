package com.shiroha.mmdskin.player.render;

import com.google.gson.Gson;
import com.shiroha.mmdskin.bridge.runtime.NativeMatrixPort;
import com.shiroha.mmdskin.bridge.runtime.NativeModelQueryPort;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.BoneBinding;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Converts actor bone frames into the independently rendered prop's model space. */
public final class PropBoneParenting {
    private final NativeMatrixPort matrices;
    private final NativeModelQueryPort query;
    private final Map<Long,Map<String,Integer>> catalogs=new HashMap<>();
    private final ByteBuffer pose=ByteBuffer.allocateDirect(192).order(ByteOrder.LITTLE_ENDIAN);
    private boolean applied;
    private long appliedModel;
    private int[] appliedIndices=new int[0];
    private float[] appliedMatrices=new float[0];
    private int[] appliedWindowIndices=new int[0];
    private float[] appliedWindows=new float[0];

    public PropBoneParenting(NativeMatrixPort matrices,NativeModelQueryPort query) {
        this.matrices=matrices; this.query=query;
    }

    public List<String> bones(long model) { return List.copyOf(catalog(model).keySet()); }

    private Map<String,Integer> catalog(long model) {
        return catalogs.computeIfAbsent(model,handle -> {
            String[] names=new Gson().fromJson(query.getBoneNames(handle),String[].class);
            var result=new LinkedHashMap<String,Integer>();
            if(names!=null) for(int i=0;i<names.length;i++) if(names[i]!=null) result.put(names[i],i);
            return result;
        });
    }
    private int index(long model,String name) {
        Integer index=catalog(model).get(name);
        if(index==null) throw new IllegalArgumentException("Missing bone: "+name);
        return index;
    }
    public Matrix4f[] pose(long model,String bone) {
        pose.clear();
        if(!matrices.getPropBonePose(model,index(model,bone),pose)) throw new IllegalArgumentException("Cannot read bone pose: "+bone);
        return new Matrix4f[]{matrixAt(0),matrixAt(64),matrixAt(128)};
    }
    private Matrix4f matrixAt(int offset) {
        return new Matrix4f(pose.slice(offset,64).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer());
    }
    public static Matrix4f animationLocal(Matrix4f local,Matrix4f origin) {
        // Only remove the PMX rest offset. Registration-frame VMD values remain authored motion.
        return inverseOrigin(origin).mul(local);
    }
    private static Matrix4f inverseOrigin(Matrix4f origin) {
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
    public boolean apply(long actor,long prop,Matrix4f propToActor,PropTimeline.State state) {
        return apply(actor,prop,propToActor,state,Map.of());
    }
    public boolean apply(long actor,long prop,Matrix4f propToActor,PropTimeline timeline,float frame,
                         PropTimeline.State state) {
        Objects.requireNonNull(timeline,"timeline");
        return apply(actor,prop,propToActor,state,timeline.outsideParentWindows(frame,state));
    }
    public boolean apply(long actor,long prop,Matrix4f propToActor,PropTimeline.State state,
                         Map<String,PropTimeline.BoneWindow> windows) {
        Objects.requireNonNull(state,"state");
        Objects.requireNonNull(windows,"windows");
        int[] indices=new int[state.boneBindings().size()];
        float[] values=new float[indices.length*16];
        var windowByIndex=new TreeMap<Integer,PropTimeline.BoneWindow>();
        int i=0;
        for(BoneBinding binding:state.boneBindings().values()) {
            int child=index(prop,binding.childBone());
            indices[i]=child;
            Matrix4f target=targetInProp(propToActor,pose(actor,binding.bone())[0]);
            Matrix4f origin=pose(prop,binding.childBone())[2];
            parentFrame(target,binding).mul(inverseOrigin(origin)).get(values,i*16);
            PropTimeline.BoneWindow window=windows.get(binding.childBone());
            if(window!=null && window.active()) windowByIndex.put(child,window);
            i++;
        }
        // Windows also cover bones whose parent is currently unbound, so resolve
        // them against the prop catalog instead of state.boneBindings().
        for(var entry:windows.entrySet()) {
            PropTimeline.BoneWindow window=entry.getValue();
            if(window==null || !window.active()) continue;
            Integer child=catalog(prop).get(entry.getKey());
            if(child!=null) windowByIndex.put(child,window);
        }
        int[] windowIndices=new int[windowByIndex.size()];
        float[] windowValues=new float[windowIndices.length*2];
        i=0;
        for(var entry:windowByIndex.entrySet()) {
            windowIndices[i]=entry.getKey();
            windowValues[i*2]=entry.getValue().previousFrame();
            windowValues[i*2+1]=entry.getValue().nextFrame();
            i++;
        }
        // Shadow/main draws share the pose cache. Reassigning identical input would
        // bump the native input epoch and advance prop physics twice in one frame.
        boolean hasAppliedState=applied || appliedWindowIndices.length>0;
        boolean sameModel=hasAppliedState && appliedModel==prop;
        boolean parentsChanged=(indices.length>0 || applied)
                && (!sameModel || !Arrays.equals(indices,appliedIndices) || !Arrays.equals(values,appliedMatrices));
        boolean windowsChanged=(windowIndices.length>0 || appliedWindowIndices.length>0)
                && (!sameModel || !Arrays.equals(windowIndices,appliedWindowIndices)
                || !Arrays.equals(windowValues,appliedWindows));
        if(!parentsChanged && !windowsChanged) return true;
        if(!hasAppliedState && indices.length==0 && windowIndices.length==0) {
            appliedModel=prop;
            return true;
        }
        if(windowsChanged && !matrices.setPropBoneWindows(prop,windowIndices,windowValues)) {
            throw new IllegalArgumentException("External window rejected: check prop bones and timeline switch frames");
        }
        if(parentsChanged) {
            if(!matrices.setPropBoneParents(prop,indices,values)) {
                throw new IllegalArgumentException("External parent rejected: check prop bones and dynamic rigid-body ownership");
            }
            appliedModel=prop;
            appliedIndices=indices;
            appliedMatrices=values;
            applied=indices.length>0;
        }
        if(windowsChanged) {
            // Re-sample the current frame because the animation was already
            // evaluated before these windows reached the native side.
            if(!matrices.evaluatePropPose(prop)) {
                throw new IllegalStateException("Cannot resample prop pose after switch-window update");
            }
            appliedModel=prop;
            appliedWindowIndices=windowIndices;
            appliedWindows=windowValues;
        }
        return true;
    }
    public void clear(long prop) {
        if(applied) matrices.setPropBoneParents(prop,new int[0],new float[0]);
        if(appliedWindowIndices.length>0) matrices.setPropBoneWindows(prop,new int[0],new float[0]);
        applied=false;
        appliedModel=0;
        appliedIndices=new int[0];
        appliedMatrices=new float[0];
        appliedWindowIndices=new int[0];
        appliedWindows=new float[0];
        catalogs.clear();
    }
}
