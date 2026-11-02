package com.shiroha.mmdskin.render.effectpack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.lwjgl.egl.EGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.egl.EGL10.*;
import static org.lwjgl.egl.EGL14.*;
import static org.lwjgl.egl.EGL15.*;
import static org.lwjgl.opengl.GL43C.*;

/** Headless Mesa probe: production importer, uniform binding, std430 table and GLSL. */
public final class ClothGlProbe {
    private static final String VERTEX = """
            #version 430 core
            out vec2 texCoord0; out vec3 viewNormal, viewPos, viewLightDir; out float eyeMask;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                texCoord0=p; viewNormal=vec3(0,0,1); viewPos=vec3(0,0,-2);
                viewLightDir=vec3(.8660254038,0,.5); eyeMask=0;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static int regular, batch, texture, checks;

    public static void main(String[] args) throws Exception {
        try (var stack = MemoryStack.stackPush()) {
            long display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
            require(eglInitialize(display, stack.mallocInt(1), stack.mallocInt(1)), "EGL initialize");
            EGL.createDisplayCapabilities(display);
            require(eglBindAPI(EGL_OPENGL_API), "OpenGL API");
            var configs = stack.mallocPointer(1);
            require(eglChooseConfig(display, stack.ints(EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8, EGL_NONE), configs, stack.mallocInt(1)), "EGL config");
            long surface = eglCreatePbufferSurface(display, configs.get(0), stack.ints(EGL_WIDTH, 8, EGL_HEIGHT, 8, EGL_NONE));
            long context = eglCreateContext(display, configs.get(0), 0,
                    stack.ints(EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 3, EGL_NONE));
            require(eglMakeCurrent(display, surface, surface, context), "EGL current");
            try {
                GL.createCapabilities();
                System.out.println("GL=" + glGetString(GL_VERSION));
                run(Path.of(args[0]));
            } finally {
                eglMakeCurrent(display, 0, 0, 0); eglDestroyContext(display, context);
                eglDestroySurface(display, surface); eglTerminate(display);
            }
        }
    }

    private static void run(Path materials) throws Exception {
        Path temp = Files.createTempDirectory(Path.of("build"), "cloth-gl-");
        var runtime = new EffectPackRuntime(temp.resolve("library"), temp.resolve("config.json"));
        var singleton = EffectPackRuntime.class.getDeclaredField("instance");
        singleton.setAccessible(true); singleton.set(null, runtime);
        regular = program(false); batch = program(true);
        int vao = glGenVertexArrays(); glBindVertexArray(vao);
        int fbo = glGenFramebuffers(), target = glGenTextures();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo); glBindTexture(GL_TEXTURE_2D, target);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 8, 8, 0, GL_RGBA, GL_FLOAT, 0L);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
        require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "float FBO");
        glViewport(0,0,8,8);
        texture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,1,1,0,GL_RGBA,GL_FLOAT,new float[]{.2f,.4f,.6f,.8f});
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        FxEffect black = null;
        for (String name : new String[]{"material_black_coat.fx", "material_cloth.fx", "material_white.fx"}) {
            Path file = materials.resolve("Cloth").resolve(name);
            FxEffect original = RayMaterialImporter.parse(materials, file, Files.readString(file), name);
            var channels = new LinkedHashMap<>(original.channels());
            // A flat surface isolates BRDF checks from the normal-map image decoder.
            channels.remove("NORMAL");
            FxEffect effect = copy(original, channels);
            float[] color = compare(effect, 0);
            require(color[0] >= 0, name + " finite light");
            compare(effect, 1);
            if (name.equals("material_black_coat.fx")) black = effect;
        }
        float[] sheen = compare(black,0);
        // Independent Ray reference at N=V=(0,0,1), N.L=.5, roughness=1.
        double f0 = Math.pow((.4 + .055) / 1.055, 2.4);
        double f = f0 + (1-f0) * Math.pow(1-Math.sqrt(.75),5);
        near(sheen[0], Math.pow(f * .09375, 1/2.2), "Ray cloth sheen reference");
        compileProductionPrograms(black);

        var channels = new LinkedHashMap<>(black.channels());
        channels.put("CUSTOM_A", new FxEffect.Channel(0,"",new float[]{0},0,new float[]{1,1},5,0,0));
        float[] zero = compare(copy(black,channels),0);
        channels.remove("CUSTOM_A"); channels.remove("CUSTOM_B");
        near(zero[0],compare(copy(black,channels),0)[0],"zero cloth weight equals default reflection");
        channels = new LinkedHashMap<>(black.channels());
        channels.put("ALBEDO", new FxEffect.Channel(0,"",new float[]{1,1,1},0,new float[]{1,1},0,0,0,1));
        near(compare(copy(black,channels),1)[0],1,"white cloth ignores PMX diffuse");
        channels.put("ALBEDO",channels.get("ALBEDO").withFlags(0));
        near(compare(copy(black,channels),1)[0],.2,"diffuse-enabled cloth uses PMX color");
        channels.put("ALBEDO_SUB",new FxEffect.Channel(0,"",new float[]{.6f,.6f,.6f},0,new float[]{1,1},5,0,0));
        near(compare(copy(black,channels),1)[0],.6,"constant sub-albedo retains full alpha");

        channels.put("CUSTOM_A",new FxEffect.Channel(1,"weight",new float[]{.5f},0,new float[]{1,1},5,2,1,1));
        FxEffect mapped = copy(black,channels);
        near(compare(mapped,2)[0],.7,"weight swizzle, scale, invert");
        channels.put("CUSTOM_A",black.channels().get("CUSTOM_A"));
        channels.put("CUSTOM_B",new FxEffect.Channel(1,"color",new float[]{.5f,.5f,.5f},0,new float[]{1,1},0,0,1,1));
        float[] color = compare(copy(black,channels),2);
        near(color[2],(1-f0)*.5,"B sRGB conversion before invert and scale");
        channels.put("CUSTOM_B",channels.get("CUSTOM_B").withFlags(0));
        color = compare(copy(black,channels),2);
        near(color[2],f0*.5,"B sRGB map without invert");
        require(glGetError()==GL_NO_ERROR,"GL errors");
        glDeleteProgram(regular); glDeleteProgram(batch); glDeleteTextures(texture);
        glDeleteTextures(target); glDeleteFramebuffers(fbo); glDeleteVertexArrays(vao);
        System.out.println("PASS cloth importer, sheen reference, diffuse toggle, custom maps, uniform/batch parity: " + checks + " checks");
    }

    private static FxEffect copy(FxEffect base, java.util.Map<String,FxEffect.Channel> channels) {
        return new FxEffect(base.id(),base.name(),base.root(),base.kind(),java.util.Map.copyOf(channels),base.alpha(),base.colorFunction(),base.notes());
    }

    private static float[] compare(FxEffect effect, int mode) {
        int[] textures = new int[FxMaterialData.CHANNELS.length];
        for(int i=0;i<textures.length;i++) {
            var c=effect.channels().get(FxMaterialData.CHANNELS[i]);
            if(c!=null && c.source()==1) textures[i]=texture;
        }
        FxMaterialData data = new FxMaterialData(effect,1,textures);
        glUseProgram(regular); setup(regular,mode);
        float[] expected;
        try(var binding = new FxMaterialBinding(false)) { binding.apply(data); expected=pixel(); }
        glUseProgram(batch); setup(batch,mode);
        glUniform1i(glGetUniformLocation(batch,"MmdBatchEnabled"),1);
        ByteBuffer bytes=ByteBuffer.allocateDirect(96+FxMaterialData.CHANNEL_BYTES).order(ByteOrder.nativeOrder());
        bytes.putFloat(.2f).putFloat(.3f).putFloat(.4f).putFloat(1);
        for(int i=0;i<16;i++) bytes.putFloat(0);
        bytes.putFloat(1).putFloat(0).putFloat(1).putFloat(1);
        int[] slots=new int[textures.length];
        data.writeChannels(bytes,slots,1); bytes.flip();
        int rows=glGenBuffers(), owners=glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,rows); glBufferData(GL_SHADER_STORAGE_BUFFER,bytes,GL_STATIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,rows);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,owners); glBufferData(GL_SHADER_STORAGE_BUFFER,new int[]{0},GL_STATIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,owners);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,texture);
        float[] actual=pixel();
        for(int i=0;i<4;i++) near(actual[i],expected[i],effect.name()+" batch component "+i);
        glDeleteBuffers(rows); glDeleteBuffers(owners);
        return expected;
    }

    private static void setup(int program,int mode) {
        glUniform4f(glGetUniformLocation(program,"MaterialColor"),.2f,.3f,.4f,1);
        glUniform1i(glGetUniformLocation(program,"ProbeMode"),mode);
    }
    private static float[] pixel() {
        glDrawArrays(GL_TRIANGLES,0,3);
        float[] p=new float[4]; glReadPixels(4,4,1,1,GL_RGBA,GL_FLOAT,p);
        for(float x:p) require(Float.isFinite(x),"finite pixel");
        return p;
    }
    private static int program(boolean batched) throws Exception {
        Path root=Path.of("common/src/main/resources/assets/mmdskin/shader");
        String fragment="#version 430 core\n" + (batched ? "#define MMD_BATCH_MATERIALS\n#define MMD_FX_COMBINED\n" : "")
                + "uniform vec4 MaterialColor, SphereMul, SphereAdd; uniform vec3 MaterialSpecular; uniform float MaterialSpecularPower, EyeMaterialMask; uniform int SphereMode, FxSelection; uniform bool HairMaterial; uniform sampler2D Sampler0, SphereTexture;\n"
                + (batched ? Files.readString(root.resolve("toon_batch_materials.glsl")) : "")
                + Files.readString(root.resolve("fx_material.glsl"))
                + "\nuniform int ProbeMode; out vec4 result; void main(){\n"
                + (batched ? "mmdLoadMaterial();\n" : "")
                + "vec2 uv=vec2(.25); if(ProbeMode==1) result=vec4(mmdFxAlbedo(vec3(0),uv),1);"
                + "else if(ProbeMode==2) result=vec4(mmdFxClothWeight(uv),mmdFxClothColor(uv).rg,1);"
                + "else result=vec4(mmdFxMaterialLight(vec3(0),vec3(0),vec3(0,0,1),vec3(0,0,-2),vec3(.8660254038,0,.5),uv,vec3(1)),1);}";
        return link(fragment);
    }
    private static void compileProductionPrograms(FxEffect effect) throws Exception {
        Path root=Path.of("common/src/main/resources/assets/mmdskin/shader");
        var output=com.shiroha.mmdskin.render.shader.ToonShaderBase.class.getDeclaredMethod("outputSource",
                String.class,com.shiroha.mmdskin.render.shader.ToonOutputProfile.class);
        output.setAccessible(true);
        String body=FxShaderSource.apply(Files.readString(root.resolve("toon_main_body.frag.glsl")),FxProgram.of(java.util.List.of(effect)));
        String fragment=(String)output.invoke(null,body,com.shiroha.mmdskin.render.shader.ToonOutputProfile.vanilla());
        glDeleteProgram(link(fragment));
        String batched=fragment.replace("#version 330 core","#version 430 core\n#define MMD_BATCH_MATERIALS")
                .replace("/* TOON_BATCH_MATERIALS */",Files.readString(root.resolve("toon_batch_materials.glsl")));
        glDeleteProgram(link(batched));
    }
    private static int link(String fragment) {
        int p=glCreateProgram();
        for(int type:new int[]{GL_VERTEX_SHADER,GL_FRAGMENT_SHADER}) {
            int s=glCreateShader(type); glShaderSource(s,type==GL_VERTEX_SHADER ? VERTEX : fragment); glCompileShader(s);
            require(glGetShaderi(s,GL_COMPILE_STATUS)!=0,glGetShaderInfoLog(s)); glAttachShader(p,s); glDeleteShader(s);
        }
        glLinkProgram(p); require(glGetProgrami(p,GL_LINK_STATUS)!=0,glGetProgramInfoLog(p)); return p;
    }
    private static void near(float actual,double expected,String name) {
        require(Math.abs(actual-expected)<.0001,name+": "+actual+" vs "+expected);
    }
    private static void require(boolean ok,String name) { if(!ok) throw new AssertionError(name); checks++; }
}
