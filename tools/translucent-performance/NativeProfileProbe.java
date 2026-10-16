import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridge;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Real JNI ABI and independent-model oracle, using a generated one-bone PMX. */
public class NativeProfileProbe {
    static final NativeFunc api=new NativeFunc();
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static ByteBuffer bytes(int n){return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());}
    static void text(ByteBuffer b,String s){byte[] v=s.getBytes(StandardCharsets.UTF_8);b.putInt(v.length).put(v);}
    static void xyz(ByteBuffer b,float x,float y,float z){b.putFloat(x).putFloat(y).putFloat(z);}
    static Path fixture(Path directory)throws Exception {
        Files.createDirectories(directory); Path path=directory.resolve("evaluation-probe.pmx");
        ByteBuffer b=ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'P','M','X',' '}).putFloat(2).put((byte)8).put(new byte[]{1,0,1,1,1,1,1,1});
        text(b,"EvaluationProbe");text(b,"");text(b,"");text(b,"");
        b.putInt(1);xyz(b,0,1,0);xyz(b,0,1,0);b.putFloat(0).putFloat(0);
        b.put((byte)0).put((byte)0).putFloat(1); // BDEF1, bone zero
        b.putInt(0).putInt(0).putInt(0); // faces, textures, materials
        b.putInt(1);text(b,"ProbeBone");text(b,"");xyz(b,0,1,0);
        b.put((byte)-1).putInt(0).putShort((short)0);xyz(b,0,1,0);
        b.putInt(0).putInt(0); // morphs, display frames
        b.putInt(1);text(b,"ProbeBody");text(b,"");
        b.put((byte)0).put((byte)0).putShort((short)0xffff).put((byte)0);
        xyz(b,.1f,.1f,.1f);xyz(b,0,1,0);xyz(b,0,0,0);
        b.putFloat(1).putFloat(0).putFloat(0).putFloat(0).putFloat(.5f).put((byte)1);
        b.putInt(0); // joints
        Files.write(path,Arrays.copyOf(b.array(),b.position()));return path;
    }
    static long load(Path path){
        long h=api.LoadModelPMX(path.toAbsolutePath().toString(),path.getParent().toString(),3);
        check(h!=0&&api.GetBoneCount(h)==1&&api.GetVertexCount(h)==1,"fixture parser/load");
        return h;
    }
    static byte[] pose(long handle){
        ByteBuffer b=bytes(64);check(api.CopySkinningMatricesToBuffer(handle,b)==1,"copy bone matrix");
        byte[] out=new byte[64];b.get(out);return out;
    }
    static void fields(ByteBuffer b,boolean cpu){
        check(b.getLong(0)==1,"schema version");
        // Tiny stages can take less than one platform timer tick (notably a one-bone IK pass).
        for(int offset=8;offset<=32;offset+=8)check(b.getLong(offset)>=0,"nonnegative stage samples");
        check(b.getLong(8)+b.getLong(16)+b.getLong(24)>0,"stage samples recorded");
        check(cpu||b.getLong(32)==0,"GPU evaluation skips CPU skinning timing");
        check(b.getLong(40)==1&&b.getLong(48)>0&&b.getLong(56)>=b.getLong(48),"actual Bullet counters");
    }
    static void warm(long h){for(int i=0;i<3;i++)api.UpdateAnimationOnly(h,1f/60);}
    public static void main(String[] args)throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        // Bind the already-loaded probe API to the production bridge without extracting another DLL.
        var instance=NativeFunc.class.getDeclaredField("inst");instance.setAccessible(true);instance.set(null,api);
        Path path=fixture(Path.of(args[1]));long handle=load(path);
        try {
            warm(handle);
            var bridge=new NativeRuntimeBridge();
            if(args.length>2&&args[2].equals("legacy")) {
                byte[] before=pose(handle);
                check(!bridge.updateModelProfiled(handle,1f/60,false,bytes(64)),"old DLL capability fallback");
                check(!bridge.updateModelProfiled(handle,1f/60,false,bytes(64)),"old DLL repeated fallback");
                check(Arrays.equals(before,pose(handle)),"failed capability probe must not evaluate");
                bridge.updateAnimationOnly(handle,1f/60);
                check(!Arrays.equals(before,pose(handle)),"old DLL regular evaluation still works");
                System.out.println("PASS old DLL profiling capability fallback");return;
            }
            byte[] before=pose(handle);
            check(!api.UpdateModelProfiled(handle,1f/60,false,ByteBuffer.allocate(64)),"heap buffer rejected");
            check(!api.UpdateModelProfiled(handle,1f/60,false,bytes(63)),"short buffer rejected");
            check(Arrays.equals(before,pose(handle)),"invalid buffers must not evaluate");
            ByteBuffer sample=bytes(64);
            check(!api.UpdateModelProfiled(Long.MAX_VALUE,1f/60,false,sample),"invalid handle rejected");
            for(int i=0;i<64;i++)check(sample.get(i)==0,"failure leaves output untouched");
            ByteBuffer backing=bytes(66);backing.put(0,(byte)91).put(65,(byte)92);backing.position(1).limit(65);
            ByteBuffer unaligned=backing.slice().order(ByteOrder.nativeOrder());
            check(api.UpdateModelProfiled(handle,1f/60,false,unaligned),"unaligned direct buffer");fields(unaligned,false);
            check(backing.get(0)==91&&backing.clear().get(65)==92,"native write bounds");
            check(!Arrays.equals(before,pose(handle)),"valid call evaluates");
            check(api.UpdateModelProfiled(handle,1f/60,true,sample),"CPU evaluation");fields(sample,true);
        } finally {api.DeleteModel(handle);}
        // All JNI evaluations use distinct models; compare against regular synchronous execution.
        byte[] reference;
        long baseline=load(path);
        try {warm(baseline);for(int i=0;i<48;i++)api.UpdateAnimationOnly(baseline,1f/60);reference=pose(baseline);}
        finally{api.DeleteModel(baseline);}
        try(var workers=Executors.newFixedThreadPool(4)) {
            var barrier=new CyclicBarrier(4);var futures=new ArrayList<Future<byte[]>>();
            for(int job=0;job<4;job++)futures.add(workers.submit(()->{
                long h=load(path);try {
                    warm(h);barrier.await(10,TimeUnit.SECONDS);ByteBuffer sample=bytes(64);
                    for(int i=0;i<48;i++){check(api.UpdateModelProfiled(h,1f/60,false,sample),"parallel JNI");fields(sample,false);}
                    return pose(h);
                }finally{api.DeleteModel(h);}
            }));
            for(var future:futures)check(Arrays.equals(reference,future.get(30,TimeUnit.SECONDS)),"parallel model pose oracle");
        }
        System.out.println("PASS JNI schema, invalid-buffer no-evaluation, unaligned bounds, CPU/GPU stages and four independent models");
    }
}
