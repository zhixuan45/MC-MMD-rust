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
            testScopes();
            testProductionPrograms();
            testWorldPass();
            check(GL33C.glGetError() == GL33C.GL_NO_ERROR, "无 GL 错误");
            runtime.reload();
            check(TextureRepository.getTextureCount() == 0, "重载释放额外贴图");
            System.out.println("PASS " + checks + " checks; imported=" + ray.id() + "; output=" + run);
        } finally {
            WorldColorEffect.release();
            GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate();
        }
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
