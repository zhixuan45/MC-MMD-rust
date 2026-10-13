package probe;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.shiroha.mmdskin.compat.iris.IrisCompat;
import com.shiroha.mmdskin.bridge.runtime.NativeModelLoadPort;
import com.shiroha.mmdskin.render.material.MaterialTextureLoader;
import com.shiroha.mmdskin.render.material.ModelMaterial;
import com.shiroha.mmdskin.render.material.SubMeshDrawHelper;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.server.packs.PackResources;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Optional;
import java.lang.reflect.Proxy;

import static org.lwjgl.egl.EGL14.*;

/** EGL/Mesa 真 GL 回归：无需游戏窗口，使用生产子网格绘制与 Toon 片段程序。 */
public final class HeadlessProbe {
    private static final String VERTEX = """
            #version 330 core
            uniform float Z;
            out vec2 texCoord0; out vec3 viewNormal; out vec3 viewPos; out vec3 viewLightDir; out float eyeMask;
            void main() {
                const vec2 p[3]=vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3));
                gl_Position=vec4(p[gl_VertexID%3],Z+float(gl_VertexID/3)*.5,1);
                texCoord0=vec2(.5); eyeMask=0; viewNormal=vec3(0,0,1);
                viewPos=vec3(0,0,-1); viewLightDir=vec3(0,0,1);
            }
            """;
    private static final String STANDARD = """
            #version 330 core
            uniform sampler2D Sampler0; uniform vec4 ColorModulator;
            in vec2 texCoord0; out vec4 fragColor;
            void main() {
                vec4 c=texture(Sampler0,texCoord0);
                if(c.a<.1) discard;
                fragColor=c*ColorModulator;
            }
            """;

    public static void main(String[] args) throws Exception {
        EGL.getCapabilities();
        long display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer major=stack.mallocInt(1), minor=stack.mallocInt(1);
            check(eglInitialize(display,major,minor), "eglInitialize");
            EGL.createDisplayCapabilities(display,major.get(0),minor.get(0));
            check(eglBindAPI(EGL_OPENGL_API), "eglBindAPI");
            PointerBuffer configs=stack.mallocPointer(1); IntBuffer count=stack.mallocInt(1);
            check(eglChooseConfig(display,stack.ints(EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,
                    EGL_RENDERABLE_TYPE,EGL_OPENGL_BIT,EGL_NONE),configs,count), "eglChooseConfig");
            long surface=eglCreatePbufferSurface(display,configs.get(0),stack.ints(EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE));
            long context=eglCreateContext(display,configs.get(0),EGL_NO_CONTEXT,stack.ints(EGL_NONE));
            check(eglMakeCurrent(display,surface,surface,context), "eglMakeCurrent");
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            System.out.println("GL="+GL46C.glGetString(GL46C.GL_VERSION));
            run();
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            eglDestroyContext(display,context); eglDestroySurface(display,surface);
        } finally { eglTerminate(display); }
    }

    private static void run() throws Exception {
        int vao=GL46C.glGenVertexArrays(); GL46C.glBindVertexArray(vao);
        int indices=GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,indices);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER,new int[]{0,1,2},GL46C.GL_STATIC_DRAW);
        int fbo=GL46C.glGenFramebuffers(); GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER,fbo);
        int output=texture(new byte[]{0,0,0,0});
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER,GL46C.GL_COLOR_ATTACHMENT0,GL46C.GL_TEXTURE_2D,output,0);
        int depth=GL46C.glGenRenderbuffers(); GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER,depth);
        GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER,GL46C.GL_DEPTH_COMPONENT24,1,1);
        GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER,GL46C.GL_DEPTH_ATTACHMENT,GL46C.GL_RENDERBUFFER,depth);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER)==GL46C.GL_FRAMEBUFFER_COMPLETE,"FBO");
        RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL46C.GL_LEQUAL);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(770,771,1,771);
        RenderSystem.setShaderColor(1,1,1,1); GL46C.glViewport(0,0,1,1);
        int cloud=texture(new byte[]{0,0,(byte)255,(byte)255});
        int standard=link(VERTEX,STANDARD);
        String toon=Files.readString(Path.of("common/src/main/resources/assets/mmdskin/shader/toon_main_body.frag.glsl"))
                .replace("/* TOON_OUTPUT_DECLARATIONS */","layout(location=0) out vec4 probeColor;")
                .replace("/* TOON_OUTPUT_WRITER */","#define writeToonOutputs(c,a,n,al) probeColor=vec4(c,al)")
                .replace("/* TOON_SURFACE_LIGHTING */",Files.readString(Path.of("common/src/main/resources/assets/mmdskin/shader/toon_surface_lighting.glsl")))
                .replace("/* TOON_LIGHTING */","vec3 applyPackLighting(vec3 a,vec3 n,vec3 p){return a;}");
        int toonProgram=link(VERTEX,toon);
        int irisInterface=link(VERTEX,STANDARD.replace("ColorModulator","iris_ColorModulator"));
        for (int program : new int[]{standard,toonProgram,standard,irisInterface,toonProgram,standard}) {
            GL46C.glUseProgram(program);
            GL46C.glUniform1i(GL46C.glGetUniformLocation(program,"Sampler0"),0);
            GL46C.glUniform1i(GL46C.glGetUniformLocation(program,"ToonLevels"),2);
            uniform(program,"LightIntensity",1); uniform(program,"AlphaCutoff",.001f);
            uniform(program,"SpecularIntensity",0); uniform(program,"GlobalAlpha",1);
            GL46C.glUniform3f(GL46C.glGetUniformLocation(program,"ShadowColor"),1,1,1);
            GL46C.glUniform4f(colorUniform(program),1,1,1,1);
            for (int texAlpha : new int[]{0,128,255}) {
                int tex=texture(new byte[]{(byte)255,0,0,(byte)texAlpha});
                for (float materialAlpha : new float[]{0,.5f,1}) {
                    clear();
                    // 继承关闭的深度写入，生产绘制必须恢复它。
                    RenderSystem.depthMask(false);
                    draw(tex,texAlpha != 255,materialAlpha);
                    float expected=texAlpha<26?0:(texAlpha/255f)*materialAlpha;
                    expect(read()[3],expected,"texture*material alpha, program="+program);
                    // 在模型之后画更远的蓝色云；只有透明洞允许它通过。
                    float[] before=read(); uniform(program,"Z",.5f);
                    RenderSystem.bindTexture(0); RenderSystem.bindTexture(cloud);
                    uniform(program,"GlobalAlpha",1);
                    GL46C.glUniform4f(GL46C.glGetUniformLocation(program,"MaterialColor"),1,1,1,1);
                    GL46C.glUniform4f(colorUniform(program),1,1,1,1);
                    RenderSystem.disableCull();
                    GL46C.glDrawElements(GL46C.GL_TRIANGLES,3,GL46C.GL_UNSIGNED_INT,0);
                    float[] after=read();
                    if (expected>=.001f) {
                        for (int i=0;i<4;i++) expect(after[i],before[i],"cloud behind material");
                    } else {
                        expect(after[2],1,"cloud through transparent hole");
                    }
                    uniform(program,"Z",0);
                }
                GL46C.glDeleteTextures(tex);
            }
            fallback(program);
            sortedLayers(program, indices, cloud);
        }
        programCacheRegression();
        check(GL46C.glGetError()==GL46C.GL_NO_ERROR,"GL error");
        System.out.println("PASS standard/Toon/standard switches; alpha=0/.5/1; textured/fallback; depth/cloud occlusion");
        GL46C.glDeleteProgram(irisInterface); GL46C.glDeleteProgram(standard); GL46C.glDeleteProgram(toonProgram);
        GL46C.glDeleteTextures(cloud); GL46C.glDeleteTextures(output); GL46C.glDeleteRenderbuffers(depth);
        GL46C.glDeleteFramebuffers(fbo); GL46C.glDeleteBuffers(indices); GL46C.glDeleteVertexArrays(vao);
    }

    private static void programCacheRegression() throws Exception {
        String json="""
                {"vertex":"mmd_alpha_probe","fragment":"mmd_alpha_probe",
                 "samplers":[{"name":"Sampler0"}],"attributes":[],"uniforms":[]}
                """;
        PackResources pack=(PackResources) Proxy.newProxyInstance(PackResources.class.getClassLoader(),
                new Class<?>[]{PackResources.class},(object,method,values)->
                        method.getName().equals("packId") ? "mmd-alpha-probe" : null);
        ResourceProvider provider=location -> {
            String path=location.getPath();
            String text=path.endsWith(".json")?json:path.endsWith(".vsh")?VERTEX:STANDARD;
            return Optional.of(new Resource(pack,()->new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
        };
        try (ShaderInstance shader=new ShaderInstance(provider,"mmd_alpha_probe",DefaultVertexFormat.POSITION)) {
            shader.apply();
            check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM)==shader.getId(),"first ShaderInstance apply");
            GL46C.glUseProgram(0); // 与三渲二清理相同：直接改变实际 GL 程序。
            shader.apply();
            check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM)==0,"reproduce stale vanilla program cache");
            // 生产修复：显式绑定；结束时清理原版程序缓存。
            GL46C.glUseProgram(shader.getId());
            IrisCompat.clearProgram(shader,-1,-1);
            shader.apply();
            check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM)==shader.getId(),"vanilla cache reset after clearProgram");
            IrisCompat.clearProgram(shader,-1,-1);
        }
        System.out.println("PASS actual ShaderInstance stale cache reproduced and reset by production clearProgram");
    }

    private static int colorUniform(int program) {
        return GL46C.glGetUniformLocation(program,
                GL46C.glGetUniformLocation(program,"ColorModulator")>=0 ? "ColorModulator" : "iris_ColorModulator");
    }
    private static void sortedLayers(int program, int indices, int blue) {
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,indices);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER,new int[]{0,1,2,3,4,5},GL46C.GL_STATIC_DRAW);
        int red=texture(new byte[]{(byte)255,0,0,(byte)255});
        ByteBuffer data=ByteBuffer.allocateDirect(40).order(ByteOrder.nativeOrder());
        data.putInt(0).putInt(0).putInt(3).putFloat(.5f).put((byte)1).put((byte)1).putShort((short)0);
        data.putInt(1).putInt(3).putInt(3).putFloat(1).put((byte)1).put((byte)1).putShort((short)0).flip();
        ModelMaterial front=new ModelMaterial(), back=new ModelMaterial();
        front.tex=red; back.tex=blue;
        ModelMaterial[] materials={front,back};
        clear();
        GL46C.glColorMaski(3, false, true, false, true);
        SubMeshDrawHelper.draw(data,2,4,GL46C.GL_UNSIGNED_INT,i->materials[i].tex,(i,a)->a,materials,null,false,i->{});
        float[] pixel=read();
        expect(pixel[0],.5f,"translucent red in front of opaque blue");
        expect(pixel[2],.5f,"opaque before translucent regardless of PMX order");
        expect(pixel[3],1,"layer alpha retains opaque background coverage");
        ByteBuffer mask=ByteBuffer.allocateDirect(4);
        GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK,3,mask);
        check(mask.get(0)==0 && mask.get(1)!=0 && mask.get(2)==0 && mask.get(3)!=0,"restore MRT color masks after depth seal");
        GL46C.glColorMaski(3,true,true,true,true);
        // 两层都半透明，颜色应先按 PMX 顺序混合，再补最近表面的深度。
        data.putFloat(20+12,.5f);
        clear();
        SubMeshDrawHelper.draw(data,2,4,GL46C.GL_UNSIGNED_INT,i->materials[i].tex,(i,a)->a,materials,null,false,i->{});
        pixel=read();
        expect(pixel[0],.25f,"depth seal does not suppress second translucent layer");
        expect(pixel[2],.5f,"both translucent layers blend before depth seal");
        expect(pixel[3],.75f,"coverage of two translucent layers");
        GL46C.glDeleteTextures(red);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER,new int[]{0,1,2},GL46C.GL_STATIC_DRAW);
    }

    private static void fallback(int program) {
        NativeModelLoadPort port=new NativeModelLoadPort() {
            public long loadPmxModel(String a,String b,long c){return 1;}
            public long loadPmdModel(String a,String b,long c){return 1;}
            public long loadVrmModel(String a,String b,long c){return 1;}
            public String getMaterialTexturePath(long h,int i){return "";}
            public float[] getMaterialDiffuseColor(long h,int i){return new float[]{1,0,0,.5f};}
        };
        int previous=GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        ModelMaterial material=MaterialTextureLoader.loadMaterial(port,1,0,new ArrayList<>());
        check(GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D)==previous,"texture upload binding restore");
        clear(); draw(material.tex,material.hasTranslucentPixels,.5f); expect(read()[3],.5f,"fallback alpha once");
        // 材质 morph 从 .5 变为 1，回退纹理不应锁死原 alpha。
        clear(); draw(material.tex,material.hasTranslucentPixels,1); expect(read()[3],1,"fallback alpha morph");
        MaterialTextureLoader.releaseOwnedTextures(new ModelMaterial[]{material});
    }

    private static void draw(int tex,boolean translucent,float alpha) {
        ByteBuffer data=ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());
        data.putInt(0).putInt(0).putInt(3).putFloat(alpha).put((byte)1).put((byte)1).putShort((short)0).flip();
        ModelMaterial material=new ModelMaterial(); material.tex=tex; material.hasAlpha=true; material.hasTranslucentPixels=translucent;
        SubMeshDrawHelper.draw(data,1,4,GL46C.GL_UNSIGNED_INT,i->tex,(i,a)->a,new ModelMaterial[]{material},null,false,i->{});
    }
    private static void clear() {
        RenderSystem.depthMask(true); GL46C.glClearColor(0,0,0,0);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT|GL46C.GL_DEPTH_BUFFER_BIT);
    }
    private static float[] read() {
        float[] pixel=new float[4]; GL46C.glReadPixels(0,0,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,pixel); return pixel;
    }
    private static int texture(byte[] rgba) {
        int t=GL46C.glGenTextures(); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,t);
        ByteBuffer b=ByteBuffer.allocateDirect(4).put(rgba); b.flip();
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D,0,GL46C.GL_RGBA8,1,1,0,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,b);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MIN_FILTER,GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MAG_FILTER,GL46C.GL_NEAREST); return t;
    }
    private static void uniform(int p,String n,float v) { GL46C.glUniform1f(GL46C.glGetUniformLocation(p,n),v); }
    private static int link(String v,String f) {
        int vs=compile(GL46C.GL_VERTEX_SHADER,v), fs=compile(GL46C.GL_FRAGMENT_SHADER,f);
        int p=GL46C.glCreateProgram();GL46C.glAttachShader(p,vs);GL46C.glAttachShader(p,fs);GL46C.glLinkProgram(p);
        check(GL46C.glGetProgrami(p,GL46C.GL_LINK_STATUS)!=0,GL46C.glGetProgramInfoLog(p));
        GL46C.glDeleteShader(vs);GL46C.glDeleteShader(fs);return p;
    }
    private static int compile(int type,String s) {
        int id=GL46C.glCreateShader(type);GL46C.glShaderSource(id,s);GL46C.glCompileShader(id);
        check(GL46C.glGetShaderi(id,GL46C.GL_COMPILE_STATUS)!=0,GL46C.glGetShaderInfoLog(id));return id;
    }
    private static void expect(float actual,float expected,String name) {
        check(Math.abs(actual-expected)<.012f,name+": "+actual+" != "+expected);
    }
    private static void check(boolean ok,String name) { if(!ok)throw new AssertionError(name); }
}
