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
        channels.put("ALBEDO", new FxEffect.Channel(0,"",new float[]{1,1,1},0,new float[]{1,1},0,0,0));
        near(compare(copy(black,channels),1)[0],1,"white cloth ignores PMX diffuse");
        channels.put("ALBEDO",new FxEffect.Channel(0,"",new float[]{1,1,1},0,new float[]{1,1},1,0,0));
        near(compare(copy(black,channels),1)[0],.2,"diffuse-enabled cloth uses PMX color");
        channels.put("ALBEDO_SUB",new FxEffect.Channel(0,"",new float[]{.6f,.6f,.6f},0,new float[]{1,1},5,0,0));
        near(compare(copy(black,channels),1)[0],Math.pow(.6,1/2.2),"constant sub-albedo retains full alpha");

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
        testModels(black,materials);
        testGlass(black,runtime);
        testMultisampleCapture();
        scanOriginalMaterials(materials);
        EffectPackRuntime.release();
        require(glGetError()==GL_NO_ERROR,"GL errors");
        glDeleteProgram(regular); glDeleteProgram(batch); glDeleteTextures(texture);
        glDeleteTextures(target); glDeleteFramebuffers(fbo); glDeleteVertexArrays(vao);
        System.out.println("PASS Ray models 0-9, original presets, parameter response, refraction, uniform/batch parity: " + checks + " checks");
    }

    private static FxEffect copy(FxEffect base, java.util.Map<String,FxEffect.Channel> channels) {
        return new FxEffect(base.id(),base.name(),base.root(),base.kind(),java.util.Map.copyOf(channels),base.alpha(),base.colorFunction(),base.notes());
    }

    private static FxEffect model(FxEffect base,int id,float a,float b) {
        var channels=new LinkedHashMap<>(base.channels());
        channels.put("CUSTOM_A",new FxEffect.Channel(0,"",new float[]{a},0,new float[]{1,1},id,0,0));
        channels.put("CUSTOM_B",new FxEffect.Channel(0,"",new float[]{b,b,b},0,new float[]{1,1},0,0,0));
        return copy(base,channels);
    }

    private static float regularPixel(FxEffect effect,int mode,float x,float y,float z) {
        glUseProgram(regular);setup(regular,mode);
        glUniform3f(glGetUniformLocation(regular,"ProbeLight"),x,y,z);
        try(var binding=new FxMaterialBinding(false)) {
            binding.apply(new FxMaterialData(effect,1,new int[12]));return pixel()[0];
        }
    }

    private static void testModels(FxEffect base,Path materials) throws Exception {
        for(String name:new String[]{"Skin/material_skin.fx","Skin/material_body_beige.fx",
                "Hair/material_hair_with_anisotropy.fx","Transparent/material_glass.fx",
                "ClearCoat/material_metal_clearcoat.fx","Subsurface/material_jade_white.fx","Subsurface/material_lampshade.fx"}) {
            Path file=materials.resolve(name);
            FxEffect original=RayMaterialImporter.parse(materials,file,Files.readString(file),name);
            var channels=new LinkedHashMap<>(original.channels());channels.remove("NORMAL");channels.remove("NORMAL_SUB");
            compare(copy(original,channels),4); compileProductionPrograms(original);
        }
        for(int id=0;id<=9;id++) { var effect=model(base,id,.8f,.4f);compare(effect,4);compileProductionPrograms(effect); }
        for(int id:new int[]{1,7}) {
            float zero=compare(model(base,id,0,.6f),4)[0],full=compare(model(base,id,1,.6f),4)[0];
            require(full>zero+.01,"curvature changes scattering for model "+id);
        }
        for(int id:new int[]{8,9}) {
            float a=compare(model(base,id,.1f,.2f),4)[0],b=compare(model(base,id,.9f,.2f),4)[0];
            require(a>b+.01,"toon threshold changes shading for model "+id);
        }
        FxEffect anisotropic=model(base,3,.9f,0);
        float x=regularPixel(anisotropic,0,.8660254038f,0,.5f),y=regularPixel(anisotropic,0,0,.8660254038f,.5f);
        require(Math.abs(x-y)>.02,"anisotropic highlights depend on tangent direction");
        double aspect=1/Math.sqrt(1-.9*.9), ax=1/aspect, ay=aspect;
        double d=.25*ax*ax+.75;
        double vh=Math.sqrt(.75),f=.04+.96*Math.pow(1-vh,5);
        near(x,Math.pow(ax*ay/(d*d)*.5/1.5*f*.5,1/2.2),"Ray anisotropic reference");
        float low=compare(model(base,6,.1f,0),0)[0],high=compare(model(base,6,.9f,0),0)[0];
        require(Math.abs(low-high)>.01,"clear coat smoothness controls second lobe");

        var channels=new LinkedHashMap<>(model(base,6,.25f,0).channels());
        channels.put("CUSTOM_A",channels.get("CUSTOM_A").withFlags(1));
        near(compare(copy(base,channels),2)[0],.5,"clear coat roughness flip uses square root");
        channels.put("CUSTOM_A",new FxEffect.Channel(7,"",new float[]{1},0,new float[]{1,1},3,1,0));
        channels.put("CUSTOM_B",new FxEffect.Channel(8,"",new float[]{1,1,1},0,new float[]{1,1},0,0,0));
        float[] pmx=compare(copy(base,channels),2);
        near(pmx[0],.4,"PMX ambient source uses evaluated channel");
        near(pmx[1],Math.pow((.6+.055)/1.055,2.4),"PMX specular source converts sRGB");

        channels=new LinkedHashMap<>(model(base,2,0,0).channels());
        channels.put("EMISSIVE",new FxEffect.Channel(0,"",new float[]{.5f,.5f,.5f,4},0,new float[]{1,1},0,0,0));
        FxEffect emissive=copy(base,channels);compare(emissive,0);
        near(regularPixel(emissive,0,1,0,0),Math.pow(Math.pow((.5+.055)/1.055,2.4)*4,1/2.2),"linear emission intensity reference");
    }

    private static void testGlass(FxEffect base,EffectPackRuntime runtime) throws Exception {
        FxEffect glass=model(base,4,1,0);
        var program=EffectPackRuntime.class.getDeclaredField("program");program.setAccessible(true);
        program.set(runtime,FxProgram.of(java.util.List.of(glass)));
        // A known background gradient makes actual texture-coordinate displacement observable.
        float[] results=new float[2];
        int target=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glViewport(1,1,6,6);
        for(int variant=0;variant<2;variant++) {
            glEnable(GL_SCISSOR_TEST);
            for(int column=0;column<8;column++) {
                glScissor(column,0,1,8);glClearColor(column/7f,.25f,.5f,1);glClear(GL_COLOR_BUFFER_BIT);
            }
            glScissor(0,0,1,1);
            glUseProgram(regular);setup(regular,3);
            glUniform4f(glGetUniformLocation(regular,"MaterialColor"),1,1,1,.5f);
            FxEffect effect=model(base,4,variant,0);
            try(var binding=new FxMaterialBinding(false)) {
                require(glIsEnabled(GL_SCISSOR_TEST),"capture restores scissor");
                require(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)==target,"capture restores target");
                require(glGetUniformi(regular,glGetUniformLocation(regular,"FxSceneEnabled"))==1,"glass snapshot enabled");
                binding.apply(new FxMaterialData(effect,1,new int[12]));
                glDisable(GL_SCISSOR_TEST);results[variant]=pixel()[0];
            }
            require(glGetUniformi(regular,glGetUniformLocation(regular,"FxSceneEnabled"))==0,"glass disabled after scope");
        }
        require(results[1]>results[0]+.005,"glass curvature displaces background sampling");
        program.set(runtime,FxProgram.EMPTY);glViewport(0,0,8,8);
    }

    private static void scanOriginalMaterials(Path materials) throws Exception {
        int passed=0,failed=0;
        var reasons=new java.util.TreeMap<String,Integer>();
        try(var paths=Files.walk(materials)) {
            for(Path file:paths.filter(p->p.toString().endsWith(".fx")).toList()) {
                String source=Files.readString(file);
                if(!source.contains("material_common_2.0.fxsub")) continue;
                try { RayMaterialImporter.parse(materials,file,source,file.getFileName().toString());passed++; }
                catch(java.io.IOException e) {failed++;reasons.merge(e.getMessage(),1,Integer::sum);}
            }
        }
        System.out.println("Original material scan: passed="+passed+", failed="+failed+"; "+reasons);
        require(passed>63,"static material compatibility improves on the recorded stage-one baseline");
    }

    private static void testMultisampleCapture() {
        int original=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int source=glGenFramebuffers(),verify=glGenFramebuffers(),render=glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER,render);
        for(int format:new int[]{GL_RGBA8,GL_RGBA16F}) {
            glBindFramebuffer(GL_FRAMEBUFFER,source);
            glRenderbufferStorageMultisample(GL_RENDERBUFFER,4,format,8,8);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_RENDERBUFFER,render);
            require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"MSAA source");
            float red=format==GL_RGBA8?.25f:2f;
            glClearColor(red,.4f,.6f,1);glClear(GL_COLOR_BUFFER_BIT);
            require(FxSceneColor.capture(2,new int[]{0,0,8,8}),"MSAA snapshot");
            int snapshot=glGetInteger(GL_TEXTURE_BINDING_2D);
            glBindFramebuffer(GL_FRAMEBUFFER,verify);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,snapshot,0);
            float[] pixel=new float[4];glReadPixels(1,1,1,1,GL_RGBA,GL_FLOAT,pixel);
            require(Math.abs(pixel[0]-red)<.005,"MSAA snapshot preserves color/HDR");
            require(glGetError()==GL_NO_ERROR,"MSAA capture GL state");
        }
        glBindFramebuffer(GL_FRAMEBUFFER,original);glBindRenderbuffer(GL_RENDERBUFFER,0);
        glActiveTexture(GL_TEXTURE0);glDeleteRenderbuffers(render);glDeleteFramebuffers(source);glDeleteFramebuffers(verify);
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
        data.writeChannels(bytes,slots,1,new float[]{.3f,.4f,.5f},new float[]{.6f,.7f,.8f}); bytes.flip();
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
        glUniform3f(glGetUniformLocation(program,"FxMaterialAmbient"),.3f,.4f,.5f);
        glUniform3f(glGetUniformLocation(program,"FxMaterialSpecular"),.6f,.7f,.8f);
        glUniform1i(glGetUniformLocation(program,"ProbeMode"),mode);
        glUniform3f(glGetUniformLocation(program,"ProbeLight"),.8660254038f,0,.5f);
    }
    private static float[] pixel() {
        glDrawArrays(GL_TRIANGLES,0,3);
        float[] p=new float[4]; glReadPixels(4,4,1,1,GL_RGBA,GL_FLOAT,p);
        for(float x:p) require(Float.isFinite(x),"finite pixel");
        return p;
    }
    private static int program(boolean batched) throws Exception {
        Path root=Path.of("common/src/main/resources/assets/mmdskin/shader");
        String fragment="#version 430 core\n#define MMD_FX_GLASS\n" + (batched ? "#define MMD_BATCH_MATERIALS\n#define MMD_FX_COMBINED\n" : "")
                + "uniform vec4 MaterialColor, SphereMul, SphereAdd; uniform vec3 MaterialSpecular; uniform float MaterialSpecularPower, EyeMaterialMask; uniform int SphereMode, FxSelection; uniform bool HairMaterial; uniform sampler2D Sampler0, SphereTexture;\n"
                + (batched ? Files.readString(root.resolve("toon_batch_materials.glsl")) : "")
                + Files.readString(root.resolve("fx_material.glsl"))
                + "\nuniform int ProbeMode; uniform vec3 ProbeLight; out vec4 result; void main(){\n"
                + (batched ? "mmdLoadMaterial();\n" : "")
                + "vec2 uv=vec2(.25); if(ProbeMode==1) result=vec4(mmdFxAlbedo(vec3(0),uv),1);"
                + "else if(ProbeMode==2) result=vec4(mmdFxClothWeight(uv),mmdFxClothColor(uv).rg,1);"
                + "else if(ProbeMode==3) result=vec4(mmdFxGlass(vec3(.1),vec3(1),vec3(.6,0,.8),vec3(0,0,-2),uv,.5),1);"
                + "else result=vec4(mmdFxMaterialLight(ProbeMode==4?vec3(.4):vec3(0),ProbeMode==4?vec3(.5):vec3(0),vec3(0,0,1),vec3(0,0,-2),ProbeLight,uv,vec3(1)),1);}";
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
