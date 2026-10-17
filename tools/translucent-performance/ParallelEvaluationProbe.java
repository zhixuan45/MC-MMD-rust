import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridge;
import com.shiroha.mmdskin.render.pipeline.NativeEvaluationScope;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** JNI 边界和固定线程池切换；合成独立岛不能替代真实头发/裙摆验收。 */
public class ParallelEvaluationProbe {
    static final NativeFunc api = new NativeFunc();
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static ByteBuffer bytes(int n) { return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder()); }
    static Path fixture(Path directory) throws Exception {
        Files.createDirectories(directory);
        ByteBuffer b = ByteBuffer.allocate(100000).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'P','M','X',' '}).putFloat(2).put((byte)8).put(new byte[]{1,0,1,1,1,1,1,1});
        NativeProfileProbe.text(b,"ParallelIslands");
        for (int i=0;i<3;i++) NativeProfileProbe.text(b,"");
        b.putInt(1); NativeProfileProbe.xyz(b,0,10,0); NativeProfileProbe.xyz(b,0,1,0);
        b.putFloat(0).putFloat(0).put((byte)0).put((byte)0).putFloat(1);
        b.putInt(0).putInt(0).putInt(0);
        b.putInt(96);
        for (int i=0;i<96;i++) {
            NativeProfileProbe.text(b,"Bone"+i); NativeProfileProbe.text(b,"");
            NativeProfileProbe.xyz(b,i*2,10,0);
            b.put((byte)-1).putInt(0).putShort((short)0); NativeProfileProbe.xyz(b,0,1,0);
        }
        b.putInt(0).putInt(0).putInt(96);
        for (int i=0;i<96;i++) {
            NativeProfileProbe.text(b,"Body"+i); NativeProfileProbe.text(b,"");
            b.put((byte)i).put((byte)0).putShort((short)0xffff).put((byte)0);
            NativeProfileProbe.xyz(b,.1f,.1f,.1f); NativeProfileProbe.xyz(b,i*2,10,0); NativeProfileProbe.xyz(b,0,0,0);
            b.putFloat(1).putFloat(0).putFloat(0).putFloat(0).putFloat(.5f).put((byte)1);
        }
        b.putInt(0);
        Path path=directory.resolve("parallel-islands.pmx");
        Files.write(path,Arrays.copyOf(b.array(),b.position())); return path;
    }
    static long load(Path path) {
        long h=api.LoadModelPMX(path.toString(),path.getParent().toString(),3);
        check(h!=0,"加载合成物理模型"); return h;
    }
    static float[] pose(long handle) {
        int count=Math.toIntExact(api.GetBoneCount(handle));
        ByteBuffer output=bytes(count*64);
        check(api.CopySkinningMatricesToBuffer(handle,output)==count,"复制矩阵");
        float[] values=new float[count*16];output.asFloatBuffer().get(values);return values;
    }
    static void equal(float[] a,float[] b) {
        check(a.length==b.length,"矩阵数量");
        for (int i=0;i<a.length;i++) check(Float.isFinite(b[i])&&Math.abs(a[i]-b[i])<1e-5,"独立岛顺序/并行矩阵不一致 index="+i);
    }
    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        var instance=NativeFunc.class.getDeclaredField("inst"); instance.setAccessible(true);instance.set(null,api);
        long capabilities=api.GetEvaluationCapabilities();
        check((capabilities&3)==3,"预算和详细计时 ABI");
        Path path=fixture(Path.of(args[1]));
        long serial=load(path), parallel=load(path);
        try {
            float[] before=pose(parallel);
            check(!api.UpdateModelParallel(parallel,.02f,false,4,true,bytes(63)),"短缓冲拒绝");
            check(!api.UpdateModelParallel(parallel,.02f,false,4,true,ByteBuffer.allocate(512)),"堆缓冲拒绝");
            check(!api.UpdateModelParallel(Long.MAX_VALUE,.02f,false,4,true,null),"无效句柄拒绝");
            check(!api.UpdateModelParallel(parallel,Float.NaN,false,4,true,null),"无效时间拒绝");
            equal(before,pose(parallel));
            for (int i=0;i<3;i++) {
                check(api.UpdateModelParallel(serial,1f/60,false,1,false,null),"预热顺序");
                check(api.UpdateModelParallel(parallel,1f/60,false,1,false,null),"预热并行参照");
            }
            ByteBuffer backing=bytes(514);
            backing.put(0,(byte)91).put(513,(byte)92).position(1).limit(513);
            ByteBuffer sample=backing.slice().order(ByteOrder.nativeOrder());
            long tasks=0,peak=0;
            for (int frame=0;frame<80;frame++) {
                check(api.UpdateModelParallel(serial,1f/60,false,1,false,null),"顺序更新");
                int width=frame%4==0?1:4;
                check(api.UpdateModelParallel(parallel,1f/60,false,width,true,sample),"并行更新");
                check(sample.getLong(0)==1&&sample.getLong(64)==1&&sample.getLong(72)==288,"采样schema");
                check(sample.getLong(48)==1,"子步数量保持不变");
                tasks+=sample.getLong(264);peak=Math.max(peak,sample.getLong(256));
                equal(pose(serial),pose(parallel));
            }
            check(backing.get(0)==91&&backing.clear().get(513)==92,"非对齐直接缓冲边界");
            if ((capabilities&8)!=0&&Runtime.getRuntime().availableProcessors()>2) {
                check(tasks>0&&peak>0&&peak<=4,"MT 实际任务/峰值边界");
            }
            // 生产桥接必须原样承接一次更新；采样/非采样均不重复推进。
            var bridge=new NativeRuntimeBridge();
            check(api.UpdateModelParallel(serial,1f/60,false,1,false,null),"桥接参照");
            NativeEvaluationScope.run(1,()->bridge.updateAnimationOnly(parallel,1f/60));
            equal(pose(serial),pose(parallel));
            System.out.println("PASS parallel JNI invalid-input no-evaluation, unaligned bounds, 96 independent islands, budget switches, pose oracle and bridge; caps="+capabilities+" physicalTasks="+tasks+" physicalPeak="+peak);
        } finally { api.DeleteModel(serial);api.DeleteModel(parallel); }
        // Java 外层并发时显式传1，不能触发嵌套 Bullet worker。
        try(var workers=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++) futures.add(workers.submit(()->{
                long h=load(path);
                try { for(int frame=0;frame<20;frame++) check(api.UpdateModelParallel(h,1f/60,false,1,false,null),"多实例顺序内部核"); }
                finally {api.DeleteModel(h);}
            }));
            for(var future:futures) future.get(60,TimeUnit.SECONDS);
        }
        System.out.println("PASS four JNI callers with no nested Bullet parallelism");
    }
}
