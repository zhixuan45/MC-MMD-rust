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
    private final ByteBuffer pose=ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN);
    private boolean applied;
    private long appliedModel;
    private int[] appliedIndices=new int[0];
    private float[] appliedMatrices=new float[0];

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
        return new Matrix4f[]{new Matrix4f(pose.asFloatBuffer()),new Matrix4f(pose.position(64).slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer())};
    }

    public static Matrix4f targetInProp(Matrix4f propToActor,Matrix4f actorBone) {
        if(!propToActor.isFinite() || propToActor.determinant()<=1e-8f) throw new IllegalArgumentException("Invalid prop scale");
        // Bone translations change units, but a target bone must not resize the child's VMD.
        return new Matrix4f(propToActor).invert().mul(actorBone).normalize3x3();
    }
    public static List<Float> compensation(Matrix4f target,Matrix4f global,Matrix4f local,boolean keepWorld) {
        Matrix4f value=keepWorld?new Matrix4f(target).invert().mul(global).mul(new Matrix4f(local).invert())
                :new Matrix4f(local).invert();
        float[] data=new float[16]; value.get(data);
        return java.util.stream.IntStream.range(0,16).mapToObj(i -> data[i]).toList();
    }
    public static Matrix4f parentFrame(Matrix4f target,BoneBinding binding) {
        float[] data=new float[16];
        for(int i=0;i<16;i++) data[i]=binding.compensation().get(i);
        var offset=binding.offset();
        float radians=(float)(Math.PI/180);
        return new Matrix4f(target).mul(new Matrix4f().set(data))
                .translate(offset.x(),offset.y(),offset.z())
                .rotateXYZ(offset.rotationX()*radians,offset.rotationY()*radians,offset.rotationZ()*radians);
    }
    public boolean apply(long actor,long prop,Matrix4f propToActor,PropTimeline.State state) {
        if(state.boneBindings().isEmpty() && !applied) return true;
        int[] indices=new int[state.boneBindings().size()];
        float[] values=new float[indices.length*16];
        int i=0;
        for(BoneBinding binding:state.boneBindings().values()) {
            indices[i]=index(prop,binding.childBone());
            Matrix4f target=targetInProp(propToActor,pose(actor,binding.bone())[0]);
            parentFrame(target,binding).get(values,i*16);
            i++;
        }
        // Shadow/main draws share the pose cache. Reassigning identical input would
        // bump the native input epoch and advance prop physics twice in one frame.
        if(appliedModel==prop && Arrays.equals(indices,appliedIndices) && Arrays.equals(values,appliedMatrices)) return true;
        if(!matrices.setPropBoneParents(prop,indices,values)) {
            throw new IllegalArgumentException("External parent rejected: check prop bones and dynamic rigid-body ownership");
        }
        appliedModel=prop;
        appliedIndices=indices;
        appliedMatrices=values;
        applied=!state.boneBindings().isEmpty();
        return true;
    }
    public void clear(long prop) {
        if(applied) matrices.setPropBoneParents(prop,new int[0],new float[0]);
        applied=false;
        appliedModel=0;
        appliedIndices=new int[0];
        appliedMatrices=new float[0];
        catalogs.clear();
    }
}
