import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.material.*;
import com.shiroha.mmdskin.texture.runtime.TextureAlphaCoverage;
import java.nio.*;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** 隐藏 GL 上下文验证 CPU/GPU 输入、实时排序及缓存失效。 */
public class CacheGlProbe {
    static ByteBuffer buffer(int n) { return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder()); }
    static void check(boolean b, String m) { if (!b) throw new AssertionError(m); }
    static int shader(int type, String code) {
        int s=GL46C.glCreateShader(type);GL46C.glShaderSource(s,code);GL46C.glCompileShader(s);
        check(GL46C.glGetShaderi(s,GL46C.GL_COMPILE_STATUS)!=0,GL46C.glGetShaderInfoLog(s));return s;
    }
    public static void main(String[] args) {
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        long window=GLFW.glfwCreateWindow(64,64,"cache probe",0,0);check(window!=0,"window");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();run();
        } finally { GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate(); }
    }
    public static void run() {
        RenderSystem.initRenderThread();
        int program=GL46C.glCreateProgram();
        GL46C.glAttachShader(program,shader(GL46C.GL_VERTEX_SHADER,"#version 330\nvoid main(){gl_Position=vec4(0,0,0,1);}"));
        GL46C.glAttachShader(program,shader(GL46C.GL_FRAGMENT_SHADER,"#version 330\nuniform int AlphaPass; uniform int SphereMode; uniform vec4 MaterialColor; out vec4 c; void main(){c=MaterialColor;c.rgb*=SphereMode==2?0.9:1.0;if(AlphaPass==2 && c.a>=.999)discard;}"));
        GL46C.glLinkProgram(program);check(GL46C.glGetProgrami(program,GL46C.GL_LINK_STATUS)!=0,"link");GL46C.glUseProgram(program);
        check(GL46C.glGetUniformLocation(program,"SphereMode")>=0,"SphereMode test uniform is active");
        int vao=GL46C.glGenVertexArrays();GL46C.glBindVertexArray(vao);
        int ebo=GL46C.glGenBuffers();GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,ebo);
        ByteBuffer indices=buffer(24);for(int i=0;i<6;i++)indices.putInt(i*4,i);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER,indices,GL46C.GL_STATIC_DRAW);
        ByteBuffer pos=buffer(72),uv=buffer(48),rgba=buffer(64*64*4);
        for(int i=0;i<6;i++){pos.putFloat(i*12+8,i<3?-.4f:.4f);uv.putFloat(i*8,.3f);uv.putFloat(i*8+4,.3f);}
        for(int i=0;i<64*64;i++)rgba.put(i*4+3,(byte)128);
        ModelMaterial material=new ModelMaterial();material.hasAlpha=true;material.hasTranslucentPixels=true;
        material.alphaCoverage=TextureAlphaCoverage.fromRgba(rgba,64,64);
        ByteBuffer draws=buffer(20);draws.putInt(8,6);draws.putFloat(12,1);draws.put(16,(byte)1);
        FloatBuffer view=buffer(64).asFloatBuffer();new Matrix4f().get(view);
        int positions=GL46C.glGenBuffers();GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER,positions);
        GL46C.glBufferData(GL46C.GL_COPY_READ_BUFFER,pos,GL46C.GL_DYNAMIC_DRAW);
        int[][] cpuGpuResults=new int[2][];
        int path=0;
        for(boolean gpu:new boolean[]{false,true}) {
            for(int i=0;i<6;i++)pos.putFloat(i*12+8,i<3?-.4f:.4f);
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER,positions);
            GL46C.glBufferSubData(GL46C.GL_COPY_READ_BUFFER,0,pos);
            try(var order=new TranslucentDrawOrder()){
            var input=new TranslucentDrawOrder.Inputs(gpu?null:pos,positions,6,view,null,1,false,uv,0,0);
            order.prepare(input,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a);
            check(order.candidateBuildCount()==1&&order.candidateTriangleCount()==2,"initial candidates");
            for(int i=0;i<6;i++)pos.putFloat(i*12+8,i<3?.4f:-.4f);
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER,positions);GL46C.glBufferSubData(GL46C.GL_COPY_READ_BUFFER,0,pos);
            input=new TranslucentDrawOrder.Inputs(gpu?null:pos,positions,6,view,null,2,false,uv,0,0);
            order.prepare(input,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a);
            check(order.candidateBuildCount()==1,"geometry-only cache reuse");
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER,order.indexBuffer());ByteBuffer sorted=buffer(24);
            GL46C.glGetBufferSubData(GL46C.GL_COPY_READ_BUFFER,0,sorted);check(sorted.getInt(0)==3,"current geometry ordering");
            cpuGpuResults[path++]=new int[]{sorted.getInt(0),sorted.getInt(4),sorted.getInt(8),sorted.getInt(12),sorted.getInt(16),sorted.getInt(20)};
            draws.put(16,(byte)0);check(!order.prepare(input,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a),"hidden");
            draws.put(16,(byte)1);order.prepare(input,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a);
            check(order.candidateTriangleCount()==2&&order.candidateBuildCount()==2,"visible recovery");
            input=new TranslucentDrawOrder.Inputs(gpu?null:pos,positions,6,view,null,2,true,uv,0,0);
            order.prepare(input,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a);
            order.prepare(input,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a);
            check(order.candidateBuildCount()==4,"dynamic EBO invalidation");

            // 正常准备后恢复两种缓冲绑定。
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER,positions);
            int savedElement=GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            int savedRead=GL46C.glGetInteger(GL46C.GL_COPY_READ_BUFFER_BINDING);
            order.prepare(input,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a);
            check(GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING)==savedElement,"element buffer state restore");
            check(GL46C.glGetInteger(GL46C.GL_COPY_READ_BUFFER_BINDING)==savedRead,"copy-read buffer state restore");

            // 非法索引异常也必须恢复绑定。
            int errorRead=GL46C.glGetInteger(GL46C.GL_COPY_READ_BUFFER_BINDING);
            indices.putInt(0,99);
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,ebo);
            GL46C.glBufferSubData(GL46C.GL_ELEMENT_ARRAY_BUFFER,0,indices);
            boolean invalidRejected=false;
            try {
                var invalidInput=new TranslucentDrawOrder.Inputs(pos,positions,6,view,null,7,true,uv,0,0);
                order.prepare(invalidInput,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a);
            } catch (IllegalArgumentException expected) { invalidRejected=true; }
            check(invalidRejected,"invalid source vertex is rejected");
            check(GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING)==ebo,"exception element buffer state restore");
            check(GL46C.glGetInteger(GL46C.GL_COPY_READ_BUFFER_BINDING)==errorRead,"exception copy-read buffer state restore");
            indices.putInt(0,0);
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,ebo);
            GL46C.glBufferSubData(GL46C.GL_ELEMENT_ARRAY_BUFFER,0,indices);

            // 稳定 EBO 下的 UV 版本变化仍使候选失效。
            ByteBuffer uvChanged=buffer(48);
            for(int i=0;i<6;i++){uvChanged.putFloat(i*8,.3f);uvChanged.putFloat(i*8+4,.3f);}
            int buildsBeforeUv=order.candidateBuildCount();
            var uvInput=new TranslucentDrawOrder.Inputs(pos,positions,6,view,null,5,false,uvChanged,0,1);
            order.prepare(uvInput,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a);
            check(order.candidateBuildCount()==buildsBeforeUv+1,"UV revision invalidation");

            // 两种位置输入均保留同深度的原始顺序。
            for(int i=0;i<6;i++)pos.putFloat(i*12+8,0f);
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER,positions);GL46C.glBufferSubData(GL46C.GL_COPY_READ_BUFFER,0,pos);
            var tieInput=new TranslucentDrawOrder.Inputs(gpu?null:pos,positions,6,view,null,6,false,uv,0,0);
            order.prepare(tieInput,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a);
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER,order.indexBuffer());
            GL46C.glGetBufferSubData(GL46C.GL_COPY_READ_BUFFER,0,sorted);
            check(sorted.getInt(0)==0&&sorted.getInt(4)==1&&sorted.getInt(8)==2,"stable equal-depth tie order");

            // 空候选不能留下旧绘制数量。
            draws.put(16,(byte)0);
            check(!order.prepare(tieInput,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a),"zero candidate result");
            check(order.drawCount()==0&&order.candidateTriangleCount()==0,"zero candidate clears previous output");

            // 其它状态相同时也不能合并不同球面材质。
            ModelMaterial second=new ModelMaterial();second.hasAlpha=true;second.hasTranslucentPixels=true;
            material.sphereTex=101;material.sphereMode=1;second.sphereTex=102;second.sphereMode=2;
            ByteBuffer twoDraws=buffer(40);
            for(int i=0;i<2;i++){
                int b=i*20;twoDraws.putInt(b,i);twoDraws.putInt(b+4,i*3);twoDraws.putInt(b+8,3);
                twoDraws.putFloat(b+12,1f);twoDraws.put(b+16,(byte)1);twoDraws.put(b+17,(byte)1);
            }
            draws.put(16,(byte)1);
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,ebo);
            order.prepare(tieInput,twoDraws,2,4,new ModelMaterial[]{material,second},null,(i,a)->a);
            check(order.drawCount()==2,"sphere material modes retain separate ordered runs");
            check(GL46C.glGetError()==0,"GL error");System.out.println("PASS cache GL gpu="+gpu);
        }
        }
        check(Arrays.equals(cpuGpuResults[0],cpuGpuResults[1]),"CPU/GPU input ordering equivalence");
        explicitIndexAndViewCaches(ebo, pos, uv, view);
        GpuTimerGlProbe.run();
    }

    static int firstIndex(TranslucentDrawOrder order) {
        ByteBuffer out=buffer(4);
        GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER,order.indexBuffer());
        GL46C.glGetBufferSubData(GL46C.GL_COPY_READ_BUFFER,0,out);
        return out.getInt(0);
    }

    static void explicitIndexAndViewCaches(int ebo, ByteBuffer positions, ByteBuffer uvs, FloatBuffer view) {
        ModelMaterial material=new ModelMaterial(); material.hasAlpha=true; material.hasTranslucentPixels=true;
        ModelMaterial[] materials={material};
        for(int i=0;i<6;i++) positions.putFloat(i*12+8,0);
        ByteBuffer draws=buffer(20); draws.putInt(8,6).putFloat(12,1).put(16,(byte)1);
        // The valid region has prefix/suffix padding; CPU bytes deliberately differ from the EBO.
        ByteBuffer indices=buffer(40); indices.position(8).limit(32);
        for(int i=0;i<6;i++) indices.putInt(8+i*4,(i+3)%6);
        var input=new TranslucentDrawOrder.Inputs(positions,0,6,view,null,11,true,uvs,0,0).withIndices(indices,11);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,ebo);
        try(var order=new TranslucentDrawOrder()) {
            order.prepare(input,draws,1,4,materials,null,(i,a)->a);
            check(firstIndex(order)==3,"CPU index region used instead of EBO readback");
            check(indices.position()==8&&indices.limit()==32,"source region preserved");
            order.prepare(input,draws,1,4,materials,null,(i,a)->a);
            check(order.candidateBuildCount()==1,"explicit dynamic index revision reused");
            for(int i=0;i<6;i++)indices.putInt(8+i*4,i);
            input=input.withIndices(indices,12);
            order.prepare(input,draws,1,4,materials,null,(i,a)->a);
            check(firstIndex(order)==0&&order.candidateBuildCount()==2,"new index revision invalidates topology");
            indices.limit(20); draws.putInt(8,3);
            order.prepare(input,draws,1,4,materials,null,(i,a)->a);
            check(order.candidateTriangleCount()==1&&order.candidateBuildCount()==3,"index valid length invalidates topology");
            indices.limit(19); boolean rejected=false;
            try { order.prepare(input,draws,1,4,materials,null,(i,a)->a); }
            catch(IllegalArgumentException expected) { rejected=true; }
            check(rejected,"unaligned CPU index region rejected");
        }
        draws.putInt(8,6); indices.limit(32);
        try(var views=new ViewTranslucentOrders()) {
            var world=views.get(false,false); var first=views.get(false,true); var gui=views.get(true,false);
            check(world!=first&&world!=gui&&first!=gui,"distinct view ownership");
            for(int pass=0;pass<4;pass++)for(var order:new TranslucentDrawOrder[]{world,first,gui})
                order.prepare(input,draws,1,4,materials,null,(i,a)->a);
            for(var order:new TranslucentDrawOrder[]{world,first,gui})check(order.candidateBuildCount()==1,"alternating view cache retained");
            check(views.ramBytes()>0&&views.vramBytes()>0,"view memory accounting");
            views.close(); check(views.ramBytes()==0&&views.vramBytes()==0,"all view resources released");
        }
        check(GL46C.glGetError()==0,"explicit index/view GL error");
        System.out.println("PASS explicit CPU index regions/revisions and alternating view caches");
    }
}
