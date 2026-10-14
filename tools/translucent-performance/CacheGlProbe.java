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
        GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
        int program=GL46C.glCreateProgram();
        GL46C.glAttachShader(program,shader(GL46C.GL_VERTEX_SHADER,"#version 330\nvoid main(){gl_Position=vec4(0,0,0,1);}"));
        GL46C.glAttachShader(program,shader(GL46C.GL_FRAGMENT_SHADER,"#version 330\nuniform int AlphaPass; uniform vec4 MaterialColor; out vec4 c; void main(){c=MaterialColor;if(AlphaPass==2 && c.a>=.999)discard;}"));
        GL46C.glLinkProgram(program);check(GL46C.glGetProgrami(program,GL46C.GL_LINK_STATUS)!=0,"link");GL46C.glUseProgram(program);
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
            draws.put(16,(byte)0);check(!order.prepare(input,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a),"hidden");
            draws.put(16,(byte)1);order.prepare(input,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a);
            check(order.candidateTriangleCount()==2&&order.candidateBuildCount()==2,"visible recovery");
            input=new TranslucentDrawOrder.Inputs(gpu?null:pos,positions,6,view,null,2,true,uv,0,0);
            order.prepare(input,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a);
            order.prepare(input,draws,1,4,new ModelMaterial[]{material},null,(i,a)->a);
            check(order.candidateBuildCount()==4,"dynamic EBO invalidation");
            check(GL46C.glGetError()==0,"GL error");System.out.println("PASS cache GL gpu="+gpu);
        }
        }
        GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
    }
}
