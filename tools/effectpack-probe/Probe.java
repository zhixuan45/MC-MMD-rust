package com.shiroha.mmdskin.render.effectpack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.bridge.runtime.NativeTexturePort;
import com.shiroha.mmdskin.render.scene.RenderScene;
import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.shader.ToonShaderBase;
import com.shiroha.mmdskin.texture.runtime.TextureRepository;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

/** 隐藏 GLFW 上使用生产导入器、材质绑定器、主片段程序和世界 pass。 */
public final class Probe {
    private static final int SIZE = 32;
    private static final String VERTEX = """
            #version 330 core
            out vec2 texCoord0; out vec3 viewNormal; out vec3 viewPos; out vec3 viewLightDir; out float eyeMask;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                texCoord0 = p; viewPos = vec3(p * 2.0 - 1.0, -2.0);
                viewNormal = vec3(0.0, 0.0, 1.0); viewLightDir = vec3(0.0, 0.0, 1.0); eyeMask = 0.0;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static EffectPackRuntime runtime;
    private static FxEffect ray, gray;
    private static int fbo, output, vao, diffuse;
    private static int checks;

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "传入 Tex_1.zip 路径");
        check(GLFW.glfwInit(), "GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "MMD FX probe", 0, 0);
        check(window != 0, "隐藏窗口");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities(); RenderSystem.initRenderThread();
            System.out.println("GL=" + GL33C.glGetString(GL33C.GL_VERSION));
            Path run = Files.createTempDirectory(Path.of("build"), "effectpack-probe-");
            Path library = run.resolve("library");
            FxPackLibrary.importPack(Path.of(args[0]), library);
            Path colors = library.resolve("colors"); Files.createDirectories(colors);
            Files.writeString(colors.resolve("gray.fx"), colorFx());
            runtime = new EffectPackRuntime(library, run.resolve("settings.json"));
            check(runtime.scan().failures().isEmpty(), "导入诊断: " + runtime.scan().failures());
            var singleton = EffectPackRuntime.class.getDeclaredField("instance");
            singleton.setAccessible(true); singleton.set(null, runtime);
            ray = runtime.scan().effects().stream().filter(e -> e.kind() == FxEffect.Kind.RAY_MATERIAL).findFirst().orElseThrow();
            gray = runtime.scan().effects().stream().filter(e -> e.kind() == FxEffect.Kind.COLOR_FILTER).findFirst().orElseThrow();
            TextureRepository.Init(); TextureRepository.configureRuntimeCollaborators(new TgaPort());
            setup();
            Path upstream = Path.of("build/ray-upstream-validation");
            if (Files.isRegularFile(upstream.resolve("material_2.0.fx"))) {
                FxEffect original = RayMaterialImporter.parse(upstream, upstream.resolve("material_2.0.fx"), Files.readString(upstream.resolve("material_2.0.fx")), "upstream");
                check(original.channels().get("SMOOTHNESS").source() == 9, "原版默认材质导入并保留来源9");
            }
            testScopes();
            testProductionPrograms();
            testWorldPass();
            testNewMaterialChannels();
            testGraphPixels();
            testShadowPatch();
            Path original = runtime.library().resolve(ray.id());
            Path alternate = runtime.library().resolve("second-ray"); Files.createDirectories(alternate);
            try (var resources = Files.list(original.getParent())) {
                for (Path resource : resources.filter(p -> p.toString().endsWith(".tga") || p.toString().endsWith(".fxsub")).toList())
                    Files.copy(resource, alternate.resolve(resource.getFileName()));
            }
            Files.writeString(alternate.resolve("alternate.fx"), Files.readString(original)
                    .replace("NORMAL_MAP_FROM 1", "NORMAL_MAP_FROM 0")
                    .replace("SMOOTHNESS_MAP_FROM 1", "SMOOTHNESS_MAP_FROM 0")
                    .replace("const float smoothness = 0.0;", "const float smoothness = 0.8;"));
            Files.writeString(colors.resolve("warm.fx"), Files.readString(Path.of("examples/mmd-effects/warm.fx")));
            runtime.reload();
            checks += com.shiroha.mmdskin.render.material.MixedFxGlProbe.run(runtime, ray.id(), "second-ray/alternate.fx", "colors/gray.fx", "colors/warm.fx");
            check(GL33C.glGetError() == GL33C.GL_NO_ERROR, "无 GL 错误");
            runtime.reload();
            check(TextureRepository.getTextureCount() == 0, "重载释放额外贴图");
            System.out.println("PASS " + checks + " checks; imported=" + ray.id() + "; output=" + run);
        } finally {
            WorldColorEffect.release();
            GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate();
        }
    }

    private static void testNewMaterialChannels() throws Exception {
        String material = Files.readString(Path.of("common/src/main/resources/assets/mmdskin/shader/fx_material.glsl"));
        String fragment = "#version 330 core\nuniform sampler2D Sampler0; uniform vec4 MaterialColor;\n"
                + material + "\nout vec4 result; void main(){ result=vec4(mmdFxScalar(2,vec2(.25)),mmdFxAlpha(.8,.5,vec2(.25)),mmdFxAlbedo(vec3(0),vec2(.25)).r,1); }";
        int p = ShaderCompiler.compileRenderProgram(VERTEX, fragment, "new FX channels"); check(p != 0, "新通道编译");
        GL33C.glUseProgram(p); uniforms(p);
        GL33C.glUniform1i(GL33C.glGetUniformLocation(p, "FxMaterialEnabled"), 1);
        GL33C.glUniform1i(GL33C.glGetUniformLocation(p, "FxSources[2]"), 9);
        GL33C.glUniform1f(GL33C.glGetUniformLocation(p, "FxMaterialPower"), 30);
        GL33C.glUniform1i(GL33C.glGetUniformLocation(p, "FxSources[9]"), 1);
        GL33C.glUniform1i(GL33C.glGetUniformLocation(p, "FxMap9"), 0);
        GL33C.glUniform2f(GL33C.glGetUniformLocation(p, "FxRepeat[9]"), 1, 1);
        GL33C.glUniform4i(GL33C.glGetUniformLocation(p, "FxOptions[9]"), 0,0,0,0);
        GL33C.glUniform4f(GL33C.glGetUniformLocation(p, "FxValues[0]"), .4f,.4f,.4f,1);
        GL33C.glUniform4f(GL33C.glGetUniformLocation(p, "FxValues[7]"), .5f,.5f,.5f,1);
        GL33C.glUniform4i(GL33C.glGetUniformLocation(p, "FxOptions[7]"), 0,1,0,0);
        float[] pixel = drawBound();
        near(pixel[0], .29289322f, .0001f, "Ray 来源9公式");
        near(pixel[1], 180f/255f*.5f, .0001f, "外置alpha替换贴图alpha并保留材质alpha");
        near(pixel[2], .2f, .0001f, "副基础色乘法");
        GL33C.glUniform1f(GL33C.glGetUniformLocation(p, "FxMaterialPower"), 0);
        near(drawBound()[0], 0, .0001f, "来源9保留低于1的镜面指数");
        int alphaMap = GL33C.glGenTextures(); GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,alphaMap);
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D,0,GL33C.GL_RGBA32F,2,1,0,GL33C.GL_RGBA,GL33C.GL_FLOAT,new float[]{.25f,0,0,1,.75f,0,0,1});
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D,GL33C.GL_TEXTURE_MIN_FILTER,GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D,GL33C.GL_TEXTURE_MAG_FILTER,GL33C.GL_NEAREST);
        near(drawBound()[1],.125f,.0001f,"外置alpha左像素");
        GL33C.glUniform4i(GL33C.glGetUniformLocation(p,"FxOptions[9]"),1,0,0,0);
        near(drawBound()[1],.375f,.0001f,"外置alpha翻转UV");
        GL33C.glUniform4i(GL33C.glGetUniformLocation(p,"FxOptions[9]"),0,0,0,0);
        GL33C.glUniform2f(GL33C.glGetUniformLocation(p,"FxRepeat[9]"),2,1);
        near(drawBound()[1],.375f,.0001f,"外置alpha重复UV");
        GL33C.glDeleteTextures(alphaMap); GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,diffuse);
        GL33C.glUseProgram(0); GL33C.glDeleteProgram(p);
        String normalFragment = "#version 330 core\nuniform sampler2D Sampler0; uniform vec4 MaterialColor; in vec2 texCoord0; in vec3 viewPos;\n" + material
                + "\nout vec4 result; void main(){result=vec4(mmdFxNormal(vec3(0,0,1),viewPos,texCoord0),1);}";
        p=ShaderCompiler.compileRenderProgram(VERTEX,normalFragment,"subnormal"); check(p!=0,"副法线编译"); GL33C.glUseProgram(p);
        GL33C.glUniform1i(GL33C.glGetUniformLocation(p,"FxMaterialEnabled"),1);
        GL33C.glUniform1i(GL33C.glGetUniformLocation(p,"FxSources[8]"),1);
        GL33C.glUniform1i(GL33C.glGetUniformLocation(p,"FxMap8"),0);
        GL33C.glUniform4f(GL33C.glGetUniformLocation(p,"FxValues[8]"),1,1,1,1);
        GL33C.glUniform4i(GL33C.glGetUniformLocation(p,"FxOptions[8]"),0,1,0,0);
        int detail=GL33C.glGenTextures(); GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,detail);
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D,0,GL33C.GL_RGBA32F,1,1,0,GL33C.GL_RGBA,GL33C.GL_FLOAT,new float[]{.75f,.5f,1,1});
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D,GL33C.GL_TEXTURE_MIN_FILTER,GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D,GL33C.GL_TEXTURE_MAG_FILTER,GL33C.GL_NEAREST);
        pixel=drawBound(); near(pixel[0],.5f,.0001f,"副法线X"); near(pixel[2],.8660254f,.0001f,"副法线重建Z");
        GL33C.glUseProgram(0); GL33C.glDeleteProgram(p); GL33C.glDeleteTextures(detail); GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,diffuse);
    }

    private static void testGraphPixels() throws Exception {
        FxEffect half = MmeColorImporter.parse(Path.of("."), Path.of("a.fx"), FxPackLibraryColor("c.rgb = c.rgb * 0.5;"), "a");
        FxEffect add = MmeColorImporter.parse(Path.of("."), Path.of("b.fx"), FxPackLibraryColor("c.rgb = c.rgb + float3(0.1,0.1,0.1);"), "b");
        var graph = new FxWorldGraph(java.util.List.of(new FxWorldGraph.Stage("half", "scene", "half", half), new FxWorldGraph.Stage("add", "half", "result", add)), "result");
        FxEffect effect = new FxEffect("graph", "graph", Path.of("."), FxEffect.Kind.WORLD_GRAPH, Map.of(),1,FxEffect.IDENTITY,java.util.List.of(),graph);
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo); GL33C.glViewport(0,0,SIZE,SIZE);
        GL33C.glDisable(GL33C.GL_SCISSOR_TEST); GL33C.glClearColor(.8f,.4f,.2f,.3f); GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT);
        GL33C.glEnable(GL33C.GL_SCISSOR_TEST); GL33C.glScissor(1,1,2,2);
        FxGraphGlBackend.render(effect, fbo, SIZE, SIZE);
        check(GL33C.glIsEnabled(GL33C.GL_SCISSOR_TEST), "图恢复scissor");
        GL33C.glDisable(GL33C.GL_SCISSOR_TEST);
        float[] c = new float[4]; GL33C.glReadPixels(0,0,1,1,GL33C.GL_RGBA,GL33C.GL_FLOAT,c);
        near(c[0], .5f, .001f, "多pass依赖 R"); near(c[1], .3f,.001f,"多pass依赖 G"); near(c[2], .2f,.001f,"多pass依赖 B"); near(c[3], .3f,.001f,"图保留alpha");
        FxGraphGlBackend.release();
    }

    private static String FxPackLibraryColor(String operation) {
        return "float Script : STANDARDSGLOBAL < string ScriptClass = \"scene\"; > = 0.8; texture Screen : RENDERCOLORTARGET; sampler S = sampler_state { Texture = <Screen>; }; float4 PS(float2 uv : TEXCOORD0) : COLOR0 { float4 c=tex2D(S,uv); " + operation + " return c; } technique T { pass P { PixelShader = compile ps_3_0 PS(); } }";
    }

    private static void testShadowPatch() throws Exception {
        String fragment = "#version 330 core\nuniform sampler2D Diffuse; in vec2 texCoord0; out vec4 color; void main(){color=texture(Diffuse,texCoord0);color.a*=0.5;}";
        int original = ShaderCompiler.compileRenderProgram(VERTEX, fragment, "shadow patch source"); check(original != 0,"shadow source");
        GL33C.glUseProgram(original); GL33C.glUniform1i(GL33C.glGetUniformLocation(original,"Diffuse"),0);
        var method = Class.forName("com.shiroha.mmdskin.render.material.ShadowFxAlphaBinding").getDeclaredMethod("patch", String.class, int.class); method.setAccessible(true);
        String patched = (String)method.invoke(null,fragment,original);
        int p = ShaderCompiler.compileRenderProgram(VERTEX,patched,"shadow external alpha"); check(p != 0,"shadow patch 编译");
        GL33C.glUseProgram(p); GL33C.glActiveTexture(GL33C.GL_TEXTURE0); GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,diffuse);
        GL33C.glUniform1i(GL33C.glGetUniformLocation(p,"Diffuse"),0); GL33C.glUniform1i(GL33C.glGetUniformLocation(p,"MmdShadowFxMap"),0);
        GL33C.glUniform2f(GL33C.glGetUniformLocation(p,"MmdShadowFxRepeat"),1,1);
        GL33C.glUniform2i(GL33C.glGetUniformLocation(p,"MmdShadowFxOptions"),0,0);
        GL33C.glUniform1i(GL33C.glGetUniformLocation(p,"MmdShadowFxEnabled"),1);
        float[] c=drawBound(); near(c[0],180f/255f,.0001f,"阴影保留RGB"); near(c[3],180f/255f*.5f,.0001f,"阴影替换alpha并保留原后续运算");
        GL33C.glUniform1i(GL33C.glGetUniformLocation(p,"MmdShadowFxEnabled"),0);
        near(drawBound()[3],128f/255f*.5f,.0001f,"无FX材质恢复原阴影alpha");
        GL33C.glUseProgram(0); GL33C.glDeleteProgram(p); GL33C.glDeleteProgram(original);
    }

    private static void setup() {
        vao = GL33C.glGenVertexArrays(); GL33C.glBindVertexArray(vao);
        output = GL33C.glGenTextures(); GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, output);
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA32F, SIZE, SIZE, 0, GL33C.GL_RGBA, GL33C.GL_FLOAT, 0L);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
        fbo = GL33C.glGenFramebuffers(); GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
        GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, output, 0);
        check(GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER) == GL33C.GL_FRAMEBUFFER_COMPLETE, "FBO");
        GL33C.glViewport(0, 0, SIZE, SIZE);
        diffuse = GL33C.glGenTextures(); GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, diffuse);
        ByteBuffer pixel = MemoryUtil.memAlloc(4).put(new byte[]{(byte)180, (byte)120, (byte)80, (byte)128}).flip();
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, 1, 1, 0, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixel);
        MemoryUtil.memFree(pixel);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
    }

    private static void testScopes() {
        runtime.settings().characters = ray.id(); runtime.settings().scenes = gray.id();
        runtime.settings().characterModel = "hero";
        try (var scope = runtime.enter(RenderScene.WORLD, "hero")) {
            check(runtime.current() == ray, "人物选择");
            try (var nested = runtime.enter(RenderScene.PLACED_SCENE, "stage")) { check(runtime.current() == gray, "放置场景独立选择"); }
            check(runtime.current() == ray, "嵌套作用域恢复");
        }
        check(runtime.current() == null, "人物作用域退出");
        try (var scope = runtime.enter(RenderScene.WORLD, "other")) { check(runtime.current() == null, "模型范围隔离"); }
        runtime.settings().characters = gray.id(); runtime.settings().world = gray.id();
        try (var scope = runtime.enter(RenderScene.WORLD, "hero")) { check(runtime.current() == null, "世界颜色去重"); }
        try (var scope = runtime.enter(RenderScene.INVENTORY, "hero")) { check(runtime.current() == gray, "GUI 颜色预览"); }
        runtime.settings().world = "";
    }

    private static void testProductionPrograms() throws Exception {
        int plain = compile(null, false), rayProgram = compile(ray, false), batch = compile(ray, true), color = compile(gray, false);
        float[] original = draw(plain);
        runtime.settings().characters = ray.id();
        try (var scope = runtime.enter(RenderScene.WORLD, "hero")) {
            GL33C.glUseProgram(rayProgram); uniforms(rayProgram);
            int active = GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE);
            int max = GL33C.glGetInteger(GL33C.GL_MAX_TEXTURE_IMAGE_UNITS);
            int[] bindings = new int[max], samplers = new int[max];
            for (int unit = 0; unit < max; unit++) {
                GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
                bindings[unit] = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
                samplers[unit] = GL33C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, unit);
            }
            GL33C.glActiveTexture(active);
            float[] reference;
            try (var binding = new FxMaterialBinding(false)) {
                check(GL33C.glGetUniformi(rayProgram, GL33C.glGetUniformLocation(rayProgram, "FxMaterialEnabled")) == 1, "生产材质绑定启用");
                float[] material = drawBound();
                reference = material;
                near(material[3], original[3], 0.0001f, "原 PMX alpha 保留");
                check(Math.abs(material[0] - original[0]) + Math.abs(material[1] - original[1]) > 0.001, "实际 Tex_1 改变像素");
                GL33C.glUniform1i(GL33C.glGetUniformLocation(rayProgram, "FxSources[5]"), 0);
                GL33C.glUniform4f(GL33C.glGetUniformLocation(rayProgram, "FxValues[5]"), 1, 1, 1, 1);
                float[] bright = drawBound();
                GL33C.glUniform4f(GL33C.glGetUniformLocation(rayProgram, "FxValues[5]"), 0.1f, 0.1f, 0.1f, 1);
                float[] occluded = drawBound();
                check(bright[0] > occluded[0] + 0.01, "AO 影响照明");
                GL33C.glUniform1i(GL33C.glGetUniformLocation(rayProgram, "FxSources[1]"), 0);
                GL33C.glUniform1i(GL33C.glGetUniformLocation(rayProgram, "FxSources[2]"), 0);
                GL33C.glUniform4f(GL33C.glGetUniformLocation(rayProgram, "FxValues[5]"), 1, 1, 1, 1);
                GL33C.glUniform4f(GL33C.glGetUniformLocation(rayProgram, "FxValues[2]"), 0.1f, 0.1f, 0.1f, 1);
                float[] rough = drawBound();
                GL33C.glUniform4f(GL33C.glGetUniformLocation(rayProgram, "FxValues[2]"), 0.8f, 0.8f, 0.8f, 1);
                float[] smooth = drawBound();
                check(Math.abs(smooth[0] - rough[0]) > 0.005, "光滑度影响高光");
            }
            check(GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE) == active, "active texture 恢复");
            for (int unit = 0; unit < max; unit++) {
                GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
                check(bindings[unit] == GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D), "texture 恢复 " + unit);
                check(samplers[unit] == GL33C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, unit), "sampler 恢复 " + unit);
            }
            GL33C.glActiveTexture(active);
            try (var binding = new FxMaterialBinding(true)) {
                check(GL33C.glGetUniformi(rayProgram, GL33C.glGetUniformLocation(rayProgram, "FxMaterialEnabled")) == 0, "阴影不替换宿主材质");
            }
            GL33C.glUseProgram(batch); uniforms(batch);
            ByteBuffer rows = MemoryUtil.memAlloc(96);
            float[][] vectors = {{1,1,1,1},{0,0,0,32},{1,1,1,1},{0,0,0,0},{0,0,0,0},{1,0,0,0}};
            for (float[] vector : vectors) for (float value : vector) rows.putFloat(value);
            rows.flip();
            int materialBuffer = GL33C.glGenBuffers(), triangleBuffer = GL33C.glGenBuffers();
            int storage = org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
            GL33C.glBindBuffer(storage, materialBuffer); GL33C.glBufferData(storage, rows, GL33C.GL_STATIC_DRAW);
            GL33C.glBindBufferBase(storage, 2, materialBuffer);
            GL33C.glBindBuffer(storage, triangleBuffer); GL33C.glBufferData(storage, new int[]{0}, GL33C.GL_STATIC_DRAW);
            GL33C.glBindBufferBase(storage, 3, triangleBuffer);
            GL33C.glUniform1i(GL33C.glGetUniformLocation(batch, "MmdBatchEnabled"), 1);
            try (var binding = new FxMaterialBinding(false)) {
                float[] batched = drawBound();
                for (int i = 0; i < 4; i++) near(batched[i], reference[i], 0.0001f, "实际材质表批量像素等价 " + i);
            }
            GL33C.glDeleteBuffers(materialBuffer); GL33C.glDeleteBuffers(triangleBuffer); MemoryUtil.memFree(rows);
        }
        float[] grayPixel = draw(color);
        near(grayPixel[0], grayPixel[1], 0.0001f, "MME 灰度 R=G"); near(grayPixel[1], grayPixel[2], 0.0001f, "MME 灰度 G=B");
        near(grayPixel[3], original[3], 0.0001f, "颜色效果保留覆盖率");
        GL33C.glUseProgram(0);
        for (int p : new int[]{plain, rayProgram, batch, color}) GL33C.glDeleteProgram(p);
    }

    private static int compile(FxEffect effect, boolean batch) throws Exception {
        Path shaders = Path.of("common/src/main/resources/assets/mmdskin/shader");
        String body = Files.readString(shaders.resolve("toon_main_body.frag.glsl"))
                .replace("/* MMD_FX_MATERIAL */", FxShaderSource.material(effect))
                .replace("/* MMD_FX_COLOR */", effect == null ? FxEffect.IDENTITY : effect.colorFunction());
        var method = ToonShaderBase.class.getDeclaredMethod("outputSource", String.class, ToonOutputProfile.class);
        method.setAccessible(true);
        String source = (String)method.invoke(null, body, ToonOutputProfile.vanilla());
        if (batch) source = source.replace("#version 330 core", "#version 430 core\n#define MMD_BATCH_MATERIALS")
                .replace("/* TOON_BATCH_MATERIALS */", Files.readString(shaders.resolve("toon_batch_materials.glsl")));
        int result = ShaderCompiler.compileRenderProgram(VERTEX, source, "FX probe " + (effect == null ? "off" : effect.kind()) + "/batch=" + batch);
        check(result != 0, "生产 shader 编译 " + (effect == null ? "off" : effect.kind()) + "/batch=" + batch);
        return result;
    }

    private static float[] draw(int program) {
        GL33C.glUseProgram(program); uniforms(program); return drawBound();
    }
    private static void uniforms(int program) {
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0); GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, diffuse);
        GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "Sampler0"), 0);
        GL33C.glUniform4f(GL33C.glGetUniformLocation(program, "MaterialColor"), 1, 1, 1, 1);
        GL33C.glUniform1f(GL33C.glGetUniformLocation(program, "LightIntensity"), 1);
        GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "ToonLevels"), 3);
        GL33C.glUniform3f(GL33C.glGetUniformLocation(program, "ShadowColor"), 0.5f, 0.5f, 0.5f);
    }
    private static float[] drawBound() {
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo); GL33C.glViewport(0, 0, SIZE, SIZE);
        GL33C.glDisable(GL33C.GL_BLEND); GL33C.glDisable(GL33C.GL_DEPTH_TEST); GL33C.glDisable(GL33C.GL_SCISSOR_TEST);
        GL33C.glBindVertexArray(vao); GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
        float[] pixel = new float[4];
        GL33C.glReadPixels(SIZE / 2, SIZE / 2, 1, 1, GL33C.GL_RGBA, GL33C.GL_FLOAT, pixel);
        for (float value : pixel) check(Float.isFinite(value), "有限像素值");
        return pixel;
    }

    private static void testWorldPass() {
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
        GL33C.glColorMask(true, true, true, true); GL33C.glClearColor(0.8f, 0.3f, 0.1f, 0.6f); GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT);
        GL33C.glViewport(1, 2, 13, 14); GL33C.glEnablei(GL33C.GL_BLEND, 0); GL33C.glEnablei(GL33C.GL_BLEND, 1);
        GL33C.glColorMaski(0, true, false, true, false); GL33C.glColorMaski(1, false, true, false, true);
        int before = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
        int unpack = GL33C.glGenBuffers(); GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, unpack);
        GL33C.glBufferData(GL33C.GL_PIXEL_UNPACK_BUFFER, 16L, GL33C.GL_STATIC_DRAW);
        WorldColorEffect.apply(gray, fbo, SIZE, SIZE);
        check(GL33C.glGetInteger(GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING) == unpack, "世界 PBO 恢复");
        GL33C.glDeleteBuffers(unpack);
        float[] pixel = new float[4]; GL33C.glReadPixels(0, 0, 1, 1, GL33C.GL_RGBA, GL33C.GL_FLOAT, pixel);
        near(pixel[0], pixel[1], 0.005f, "世界灰度 R=G"); near(pixel[1], pixel[2], 0.005f, "世界灰度 G=B");
        near(pixel[3], 0.6f, 0.005f, "世界 alpha 保留");
        int[] viewport = new int[4]; GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, viewport);
        check(java.util.Arrays.equals(viewport, new int[]{1, 2, 13, 14}), "世界 viewport 恢复");
        check(GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM) == before, "世界 program 恢复");
        check(GL33C.glIsEnabledi(GL33C.GL_BLEND, 0) && GL33C.glIsEnabledi(GL33C.GL_BLEND, 1), "indexed blend 恢复");
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var mask = stack.malloc(4); GL33C.glGetBooleani_v(GL33C.GL_COLOR_WRITEMASK, 1, mask);
            check(mask.get(0) == 0 && mask.get(1) != 0 && mask.get(2) == 0 && mask.get(3) != 0, "其他颜色槽的 mask 保留");
        }
        GL33C.glColorMaski(0, true, true, true, true);
        GL33C.glClearColor(2.0f, 1.5f, 0.8f, 1); GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT);
        WorldColorEffect.apply(gray, fbo, SIZE, SIZE);
        GL33C.glReadPixels(0, 0, 1, 1, GL33C.GL_RGBA, GL33C.GL_FLOAT, pixel);
        check(pixel[0] > 1.4f, "世界中间颜色保留 HDR");
    }

    private static String colorFx() {
        return """
                float Script : STANDARDSGLOBAL < string ScriptClass = "scene"; > = 0.8;
                texture Screen : RENDERCOLORTARGET;
                sampler ScreenSampler = sampler_state { Texture = <Screen>; };
                float4 PS(float2 uv : TEXCOORD0) : COLOR0 {
                    float4 c = tex2D(ScreenSampler, uv);
                    float gray = dot(c.rgb, float3(0.299, 0.587, 0.114));
                    c.rgb = float3(gray, gray, gray); return c;
                }
                technique Color { pass P { PixelShader = compile ps_3_0 PS(); } }
                """;
    }

    private static void near(float actual, float expected, float tolerance, String message) { check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + "/" + expected); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }

    /** 只用于本探针的未压缩 TGA 解码器，生产仍使用原 Rust 图片解码器。 */
    private static final class TgaPort implements NativeTexturePort {
        private record Pixels(int width, int height, ByteBuffer data) { }
        private final Map<Long, Pixels> images = new HashMap<>();
        private long next;
        @Override public long loadTexture(String filename) {
            try {
                byte[] bytes = Files.readAllBytes(Path.of(filename));
                int w = (bytes[12] & 255) | (bytes[13] & 255) << 8, h = (bytes[14] & 255) | (bytes[15] & 255) << 8;
                int stride = (bytes[16] & 255) / 8, offset = 18 + (bytes[0] & 255);
                ByteBuffer data = MemoryUtil.memAlloc(w * h * 4);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int row = (bytes[17] & 32) != 0 ? y : h - y - 1, p = offset + (row * w + x) * stride;
                    if (stride == 1) data.put(bytes[p]).put(bytes[p]).put(bytes[p]).put((byte)255);
                    else data.put(bytes[p + 2]).put(bytes[p + 1]).put(bytes[p]).put(stride == 4 ? bytes[p + 3] : (byte)255);
                }
                data.flip(); images.put(++next, new Pixels(w, h, data)); return next;
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        @Override public int textureWidth(long h) { return images.get(h).width(); }
        @Override public int textureHeight(long h) { return images.get(h).height(); }
        @Override public long textureData(long h) { return MemoryUtil.memAddress(images.get(h).data()); }
        @Override public boolean textureHasAlpha(long h) { return true; }
        @Override public void copyTextureData(ByteBuffer destination, long source, int size) { destination.put(MemoryUtil.memByteBuffer(source, size)); }
        @Override public void deleteTexture(long h) { MemoryUtil.memFree(images.remove(h).data()); }
    }
}
