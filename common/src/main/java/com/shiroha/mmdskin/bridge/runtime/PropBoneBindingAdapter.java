package com.shiroha.mmdskin.bridge.runtime;

import com.google.gson.Gson;
import com.shiroha.mmdskin.stage.domain.timeline.BoneParentMath;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.BoneBinding;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Converts actor bone frames into the independently rendered prop's model space. */
public final class PropBoneBindingAdapter {
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

    public PropBoneBindingAdapter(NativeMatrixPort matrices,NativeModelQueryPort query) {
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
    public enum Status { READY, PENDING, FAILED }
    public record ApplyResult(Status status,int code,String message) {
        public boolean ready() { return status==Status.READY; }
    }
    public ApplyResult applyResult(long actor,long prop,Matrix4f propToActor,PropTimeline.State state) {
        return applyResult(actor,prop,propToActor,state,Map.of());
    }
    public ApplyResult applyResult(long actor,long prop,Matrix4f propToActor,PropTimeline timeline,float frame,
                                   PropTimeline.State state) {
        Objects.requireNonNull(timeline,"timeline");
        return applyResult(actor,prop,propToActor,state,timeline.outsideParentWindows(frame,state));
    }
    public ApplyResult applyResult(long actor,long prop,Matrix4f propToActor,PropTimeline.State state,
                                   Map<String,PropTimeline.BoneWindow> windows) {
        try { return applyInternal(actor,prop,propToActor,state,windows); }
        catch(IllegalArgumentException invalid) {
            String message=invalid.getMessage()==null?"Invalid bone binding":invalid.getMessage();
            if(message.startsWith("Missing bone:")) return new ApplyResult(Status.FAILED,-2,message);
            if(message.startsWith("Cannot read bone pose:")) return new ApplyResult(Status.PENDING,-1,message);
            return new ApplyResult(Status.FAILED,-4,message);
        } catch(RuntimeException failure) {
            return new ApplyResult(Status.FAILED,-7,failure.getMessage()==null?"Prop binding failed":failure.getMessage());
        }
    }
    public boolean apply(long actor,long prop,Matrix4f propToActor,PropTimeline.State state) {
        return apply(actor,prop,propToActor,state,Map.of());
    }
    public boolean apply(long actor,long prop,Matrix4f propToActor,PropTimeline timeline,float frame,PropTimeline.State state) {
        ApplyResult result=applyResult(actor,prop,propToActor,timeline,frame,state);
        if(result.status()==Status.FAILED) throw new IllegalArgumentException(result.message());
        return result.ready();
    }
    public boolean apply(long actor,long prop,Matrix4f propToActor,PropTimeline.State state,Map<String,PropTimeline.BoneWindow> windows) {
        ApplyResult result=applyResult(actor,prop,propToActor,state,windows);
        if(result.status()==Status.FAILED) throw new IllegalArgumentException(result.message());
        return result.ready();
    }
    private ApplyResult applyInternal(long actor,long prop,Matrix4f propToActor,PropTimeline.State state,
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
            Matrix4f target=BoneParentMath.targetInProp(propToActor,pose(actor,binding.bone())[0]);
            Matrix4f origin=pose(prop,binding.childBone())[2];
            BoneParentMath.parentFrame(target,binding).mul(BoneParentMath.inverseOrigin(origin)).get(values,i*16);
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
        if(!parentsChanged && !windowsChanged) return new ApplyResult(Status.READY,0,null);
        ApplyResult committed=commitBindings(prop,indices,values,windowIndices,windowValues,windowsChanged);
        if(committed.status()!=Status.READY) return committed;
        appliedModel=prop;
        appliedIndices=indices;
        appliedMatrices=values;
        applied=indices.length>0;
        appliedWindowIndices=windowIndices;
        appliedWindows=windowValues;
        return committed;
    }
    /** Submit both complete binding collections in one native transaction. */
    private ApplyResult commitBindings(long prop,int[] indices,float[] values,int[] windowIndices,
                                      float[] windowValues,boolean windowsChanged) {
        int code=matrices.setPropBindings(prop,indices,values,windowIndices,windowValues);
        if(code!=0) return resultForCode(code);
        if(windowsChanged && !matrices.evaluatePropPose(prop)) {
            int rollback=matrices.setPropBindings(prop,appliedIndices,appliedMatrices,appliedWindowIndices,appliedWindows);
            if(rollback!=0) return new ApplyResult(Status.FAILED,-7,"Window resample failed and previous prop bindings could not be restored");
            return new ApplyResult(Status.FAILED,-7,"Cannot resample prop pose after switch-window update; previous bindings restored");
        }
        return new ApplyResult(Status.READY,0,null);
    }
    private static ApplyResult resultForCode(int code) {
        return switch(code) {
            case -1 -> new ApplyResult(Status.PENDING,-1,"Prop model is not ready");
            case -2 -> new ApplyResult(Status.FAILED,-2,"Prop binding references a missing bone");
            case -3 -> new ApplyResult(Status.FAILED,-3,"Dynamic rigid-body bones cannot be externally parented");
            case -4 -> new ApplyResult(Status.FAILED,-4,"Prop binding contains an invalid matrix");
            case -5 -> new ApplyResult(Status.FAILED,-5,"Prop binding contains invalid switch windows");
            case -6 -> new ApplyResult(Status.FAILED,-6,"Prop binding arrays have an invalid shape");
            default -> new ApplyResult(Status.FAILED,-7,"Native prop binding transaction failed ("+code+")");
        };
    }

    public void clear(long prop) { clearResult(prop); }
    public ApplyResult clearResult(long prop) {
        if(applied || appliedWindowIndices.length>0) {
            int code=matrices.setPropBindings(prop,new int[0],new float[0],new int[0],new float[0]);
            if(code!=0) return resultForCode(code);
        }
        applied=false;
        appliedModel=0;
        appliedIndices=new int[0];
        appliedMatrices=new float[0];
        appliedWindowIndices=new int[0];
        appliedWindows=new float[0];
        catalogs.clear();
        return new ApplyResult(Status.READY,0,null);
    }
}
