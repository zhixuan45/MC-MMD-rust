package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.compat.iris.IrisToonCompat;
import com.shiroha.mmdskin.compat.iris.IrisWaterShaderPatcher;
import com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyPipeline;
import com.shiroha.mmdskin.render.scene.RenderScene;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.outline.OutlineRenderPass;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;

/** Real GL regression: a public loader stage need not bind Iris's colortex0 FBO. */
public final class BlockEntityTransparencyGlProbe {
    private static final int SIZE = Integer.getInteger("mmdskin.transparencyProbeSize", 64);
    private static int model, solid, water, source, stage, vao, white;
    private static ToonOutputProfile productionProfile;
    private static float[] nativeWaterPixel, nativeWaterOutside, nativeAuxiliary;
    private static float testWaterAlpha = .5f;
    private static final float[] CHEST = {.2f, .1f, .05f, 1};

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            var method = Class.forName("BslBatchGlProbe").getDeclaredMethod("profile",
                    java.util.zip.ZipFile.class, java.util.Map.class, String.class, String.class);
            method.setAccessible(true);
            try (var zip = new java.util.zip.ZipFile(args[0])) {
                productionProfile = (ToonOutputProfile) method.invoke(null, zip, java.util.Map.of(), "world0",
                        "gbuffers_entities_translucent");
                check(productionProfile.isSupported(), "actual BSL profile supported");
                System.out.println("Actual BSL profile: " + productionProfile.id());
            }
        }
        check(GLFW.glfwInit(), "GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "block entity alpha", 0, 0);
        check(window != 0, "OpenGL 4.3 context");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            setup();
            // Model the result of Iris's public output/flip verification, without loading Minecraft.
            fixture("primaryColorOutput", true);
            fixture("stablePrimaryColorOutput", true);
            if (java.util.Arrays.asList(args).contains("--water-performance")) {
                waterPerformance();
                return;
            }
            if (java.util.Arrays.asList(args).contains("--water-order")) {
                boolean reference = java.util.Arrays.asList(args).contains("--shared-water-reference");
                for (float alpha : new float[]{0, .002f, .5f, 1}) {
                    testWaterAlpha = alpha;
                    waterOrder(false, reference);
                    waterOrder(true, reference);
                }
                testWaterAlpha = .5f;
                if (!reference) { waterEmptyPass(); waterCrossing(); waterOverflow(); }
                check(GL46C.glGetError()==GL46C.GL_NO_ERROR,"water-order final GL error");
                return;
            }
            productionToonTextureAlpha(true);
            fixture("stablePrimaryColorOutput", false);
            productionToonTextureAlpha(false);
            fixture("stablePrimaryColorOutput", true);
            for (float alpha : new float[]{.002f, .01f, .12f, .5f}) scenario(alpha, false);
            scenario(.12f, true); // both stage callbacks bind a different wrapper of the same gbuffer
            replacedAttachmentIsNotWritten();
            fixture("stablePrimaryColorOutput", false);
            unverifiedTargetFallsBack();
            for (boolean flipped : new boolean[]{false, true}) {
                irisFinalOverwrite(flipped, false);
                irisFinalOverwrite(flipped, true);
            }
            for (boolean shaderPack : new boolean[]{true, false})
                for (boolean reverse : new boolean[]{false, true})
                    for (boolean opaquePmx : new boolean[]{true, false})
                        multiModelLateOpaque(reverse, shaderPack, opaquePmx);
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "final GL error");
            System.out.println("PASS BlockEntityTransparencyGlProbe: late chest front/behind, alpha .002/.01/.12/.5, "
                    + "holes, unrelated stage FBO, shared-storage wrapper, pre-water depth, MRT/state restoration, "
                    + "subsequent color sampling, replaced attachment protection, unverified-target fallback, "
                    + "Iris final overwrite with stable/flipped colortex0 and production outlines, "
                    + "two PMX layers in both orders and late-flushed opaque occluder, shader/no-shader");
        } finally {
            WorldTransparencyPipeline.release();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void productionToonTextureAlpha(boolean captureExpected) {
        ToonShaderCpu shader = new ToonShaderCpu();
        check(shader.init(), "production Toon shader initialization");
        if (productionProfile != null) check(shader.selectOutputProfile(productionProfile), "actual BSL profile selected");
        int originalModel = model;
        int originalWhite = white;
        int vertices = GL46C.glGenBuffers();
        try {
            model = shader.getMainProgram();
            shader.useMain();
            FloatBuffer matrix = buffer(64).asFloatBuffer();
            new Matrix4f().get(matrix);
            shader.setProjectionMatrix(matrix);
            shader.setModelViewMatrix(matrix);
            shader.setLightDirection(0, 0, 1);
            shader.setLightIntensity(1);
            shader.setAlphaCutoff(.001f);
            shader.setSampler0(0);
            shader.setEyeEmission(0);
            shader.setDisableTransparency(false);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, vertices);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, new float[]{
                    -1,-1,-.2f, 0,0,1, .5f,.5f,
                     3,-1,-.2f, 0,0,1, .5f,.5f,
                    -1, 3,-.2f, 0,0,1, .5f,.5f}, GL46C.GL_STATIC_DRAW);
            int[] locations = {shader.getPositionLocation(), shader.getNormalLocation(), shader.getUv0Location()};
            for (int i = 0; i < 3; i++) {
                GL46C.glEnableVertexAttribArray(locations[i]);
                GL46C.glVertexAttribPointer(locations[i], i == 2 ? 2 : 3, GL46C.GL_FLOAT, false, 32, i * 12L);
            }
            white = texture(GL46C.GL_RGBA8, 1);
            GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL46C.GL_RGBA,
                    GL46C.GL_UNSIGNED_BYTE, buffer(4).put(0,(byte)255).put(1,(byte)255).put(2,(byte)255).put(3,(byte)32));
            bind(stage);
            WorldTransparencyPipeline.beginFrame(false, true, true);
            WorldTransparencyPipeline.afterOpaqueWorld();
            WorldTransparencyPipeline.afterWorldTranslucency();
            WorldTransparencyPipeline.endFrame();
            bind(source);
            GL46C.glDepthMask(true);
            GL46C.glClearDepth(1);
            GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
            GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, CHEST);
            WorldTransparencyPipeline.beginFrame(false, true, true);
            drawModel(1, captureExpected);
            float[] depth = new float[1];
            GL46C.glReadPixels(16, 16, 1, 1, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, depth);
            check(depth[0] == 1, "production texture-alpha veil must not write main depth: " + depth[0]);
            GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
            drawSolid(.4f);
            bind(stage);
            WorldTransparencyPipeline.afterOpaqueWorld();
            WorldTransparencyPipeline.afterWorldTranslucency();
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, source);
            GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
            float[] color = new float[4];
            GL46C.glReadPixels(16, 16, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, color);
            System.out.println("Production Toon texture alpha 32/255, capture=" + captureExpected
                    + ": " + java.util.Arrays.toString(color));
            check(color[0] < .4f && color[1] < .3f && color[2] < .3f,
                    "production texture-alpha veil must retain visible chest color");
            for (int location : locations) GL46C.glDisableVertexAttribArray(location);
            WorldTransparencyPipeline.endFrame();
        } finally {
            model = originalModel;
            GL46C.glDeleteTextures(white);
            white = originalWhite;
            GL46C.glDeleteBuffers(vertices);
            shader.cleanup();
        }
    }

    private static void fixture(String name, boolean value) throws Exception {
        try {
            var field = IrisToonCompat.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(null, value);
            if (name.equals("stablePrimaryColorOutput"))
                targetFixture(value ? () -> { bind(source); return true; } : null);
        } catch (NoSuchFieldException e) {
            // Allows running this regression against the pre-fix production classes.
            if (!name.equals("stablePrimaryColorOutput")) throw e;
        }
    }

    private static void targetFixture(java.util.function.BooleanSupplier target) throws Exception {
        var field = IrisToonCompat.class.getDeclaredField("preparedWorldColorTarget");
        field.setAccessible(true);
        field.set(null, target);
    }

    private static void setup() {
        String vertex = """
                #version 330 core
                uniform float Depth;
                uniform float Slope;
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2) * 2.0 - 1.0;
                    gl_Position = vec4(p, Depth + Slope * p.x, 1.0);
                }
                """;
        model = program(vertex, """
                #version 330 core
                uniform vec4 MaterialColor;
                uniform int AlphaPass, DisableTransparency;
                layout(location=0) out vec4 toonOutput0;
                layout(location=1) out vec4 toonOutput1;
                void main() {
                    float a = DisableTransparency != 0 ? 1.0 : MaterialColor.a;
                    if (a < .001 || gl_FragCoord.x >= 56.0) discard;
                    if (AlphaPass == 1 && a < .999) discard;
                    if (AlphaPass == 2 && a >= .999) discard;
                    toonOutput0 = vec4(MaterialColor.rgb, a);
                    toonOutput1 = vec4(.75);
                }
                """);
        solid = program(vertex, """
                #version 330 core
                uniform vec4 Color;
                uniform sampler2D SceneColor;
                uniform int SampleScene;
                layout(location=0) out vec4 fragColor;
                void main() {
                    fragColor = SampleScene != 0 ? texelFetch(SceneColor, ivec2(gl_FragCoord.xy), 0) : Color;
                }
                """);
        water = program(vertex, IrisWaterShaderPatcher.patchFragment("""
                #version 330 core
                uniform float Unused;
                uniform vec4 Color;
                layout(location=0) out vec4 fragColor;
                layout(location=1) out vec4 auxiliary;
                void main() {
                    if (Color.a < .001 || gl_FragCoord.x < 4.0) discard;
                    fragColor = Color;
                    auxiliary = vec4(.75, .25, .5, 1.0);
                }
                """));
        vao = GL46C.glGenVertexArrays();
        GL46C.glBindVertexArray(vao);
        int ebo = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, new int[]{0, 1, 2}, GL46C.GL_STATIC_DRAW);
        white = texture(GL46C.GL_RGBA8, 1);
        GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL46C.GL_RGBA,
                GL46C.GL_UNSIGNED_BYTE, buffer(4).putInt(0, -1));
        source = target();
        stage = target();
        shareDepth(stage, source);
    }

    private static void shareDepth(int destination, int original) {
        int depth = attachment(original, GL46C.GL_DEPTH_ATTACHMENT);
        bind(destination);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                GL46C.GL_TEXTURE_2D, depth, 0);
    }

    /** Simulate the actual late-main callbacks followed by Iris overwriting main from colortex0. */
    private static void irisFinalOverwrite(boolean flipped, boolean outlines) throws Exception {
        WorldTransparencyPipeline.release();
        fixture("primaryColorOutput", true);
        fixture("stablePrimaryColorOutput", !flipped);
        int late = flipped ? target() : source;
        if (flipped) shareDepth(late, source);
        boolean[] afterDeferred = {false};
        targetFixture(() -> { bind(afterDeferred[0] ? late : source); return true; });
        bind(stage);
        WorldTransparencyPipeline.beginFrame(false, true, true);
        WorldTransparencyPipeline.afterOpaqueWorld();
        WorldTransparencyPipeline.afterWorldTranslucency();
        WorldTransparencyPipeline.endFrame();
        bind(source);
        GL46C.glDepthMask(true);
        GL46C.glClearDepth(1);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, CHEST);
        WorldTransparencyPipeline.beginFrame(false, true, true);
        drawModel(.12f);
        if (outlines) drawProductionOutline();
        afterDeferred[0] = true;
        bind(late);
        GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_COLOR_ATTACHMENT1});
        GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, CHEST);
        GL46C.glClearBufferfv(GL46C.GL_COLOR, 1, new float[]{.3f, .4f, .5f, 1});
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
        GL46C.glScissor(0, 0, 32, SIZE);
        drawSolid(.4f);
        GL46C.glScissor(32, 0, 32, SIZE);
        drawSolid(-.6f);
        GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_COLOR_ATTACHMENT1});
        bind(stage);
        GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, new float[]{1, 0, 1, 1});
        WorldTransparencyPipeline.afterOpaqueWorld();
        // Later water depth must not replace the post-chest opaque snapshot.
        bind(late);
        GL46C.glColorMask(false, false, false, false);
        drawSolid(-.9f);
        GL46C.glColorMask(true, true, true, true);
        bind(stage);
        GL46C.glEnablei(GL46C.GL_BLEND, 1);
        GL46C.glBlendFuncSeparatei(1, GL46C.GL_DST_COLOR, GL46C.GL_ONE, GL46C.GL_ONE, GL46C.GL_ZERO);
        WorldTransparencyPipeline.afterWorldTranslucency();
        check(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == stage, "late main FBO restored");
        check(GL46C.glIsEnabledi(GL46C.GL_BLEND, 1)
                && GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_RGB, 1) == GL46C.GL_DST_COLOR,
                "resolve preserves auxiliary indexed blending");
        expect(stage, GL46C.GL_COLOR_ATTACHMENT0, 16, new float[]{1,0,1,1}, "temporary main is untouched");
        bind(late);
        check(GL46C.glGetInteger(GL46C.GL_DRAW_BUFFER1) == GL46C.GL_COLOR_ATTACHMENT1, "late MRT restored");
        float[] beforeFinal = pixel(late, 16);
        check(Math.abs(beforeFinal[0] - CHEST[0]) > .01f, "translucent body/outline contributed before final");
        expect(late, GL46C.GL_COLOR_ATTACHMENT0, 40, CHEST, "front chest still occludes captured fragments");
        expect(late, GL46C.GL_COLOR_ATTACHMENT1, 16, new float[]{.3f,.4f,.5f,1}, "late material MRT untouched");
        // Final is a replacing fullscreen pass, not an alpha overlay on temporary main.
        int scene = attachment(late, GL46C.GL_COLOR_ATTACHMENT0);
        bind(stage);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        GL46C.glDisable(GL46C.GL_DEPTH_TEST);
        GL46C.glDisable(GL46C.GL_BLEND);
        GL46C.glUseProgram(solid);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, scene);
        GL46C.glUniform1i(GL46C.glGetUniformLocation(solid, "SampleScene"), 1);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        expect(stage, GL46C.GL_COLOR_ATTACHMENT0, 16, beforeFinal, "body/outline survives replacing Iris final");
        GL46C.glUniform1i(GL46C.glGetUniformLocation(solid, "SampleScene"), 0);
        System.out.println("Iris final overwrite: flipped=" + flipped + ", productionOutline=" + outlines
                + ", finalPixel=" + java.util.Arrays.toString(beforeFinal));
        WorldTransparencyPipeline.endFrame();
    }

    private static float[] pixel(int fbo, int x) {
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, fbo);
        GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        float[] result = new float[4];
        GL46C.glReadPixels(x, 16, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, result);
        return result;
    }

    private static void drawProductionOutline() {
        ToonShaderCpu shader = new ToonShaderCpu();
        check(productionProfile == null ? shader.init() : shader.selectOutputProfile(productionProfile),
                "production outline shader");
        int positions = GL46C.glGenBuffers(), normals = GL46C.glGenBuffers(), uv = GL46C.glGenBuffers();
        int fragmentsBefore = capturedFragments();
        try {
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, positions);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, new float[]{-1,-1,-.2f, -1,3,-.2f, 3,-1,-.2f}, GL46C.GL_STATIC_DRAW);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, normals);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, new float[]{0,0,-1, 0,0,-1, 0,0,-1}, GL46C.GL_STATIC_DRAW);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, uv);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, new float[]{.5f,.5f, .5f,.5f, .5f,.5f}, GL46C.GL_STATIC_DRAW);
            FloatBuffer matrix = buffer(64).asFloatBuffer();
            new Matrix4f().get(matrix);
            ByteBuffer mesh = buffer(20).putInt(0,0).putInt(4,0).putInt(8,3).putFloat(12,.25f)
                    .put(16,(byte)1).put(17,(byte)1).put(18,(byte)1);
            try (var ignored = WorldTransparencyPipeline.modelScene(RenderScene.WORLD)) {
                OutlineRenderPass.draw(shader, new OutlineRenderPass.Inputs(positions,normals,uv,mesh,
                        1,4,GL46C.GL_UNSIGNED_INT,matrix,matrix,1), (id,a) -> a, id -> white);
            }
            check(capturedFragments() > fragmentsBefore, "production outline must actually enter the shared queue");
        } finally {
            shader.cleanup();
            GL46C.glDeleteBuffers(positions); GL46C.glDeleteBuffers(normals); GL46C.glDeleteBuffers(uv);
        }
    }

    private static int capturedFragments() {
        int previous = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
        try {
            var instanceField = WorldTransparencyPipeline.class.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            var metaField = WorldTransparencyPipeline.class.getDeclaredField("meta");
            metaField.setAccessible(true);
            GL46C.glMemoryBarrier(GL46C.GL_SHADER_STORAGE_BARRIER_BIT | GL46C.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, metaField.getInt(instanceField.get(null)));
            int[] count = new int[1];
            GL46C.glGetBufferSubData(GL46C.GL_SHADER_STORAGE_BUFFER, 0, count);
            return count[0];
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        } finally {
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, previous);
        }
    }

    private static void scenario(float alpha, boolean sharedWrapper) {
        // Warm the public stage gate with callbacks on a target unrelated to entities.
        WorldTransparencyPipeline.endFrame();
        bind(stage);
        WorldTransparencyPipeline.beginFrame(false, true, true);
        WorldTransparencyPipeline.afterOpaqueWorld();
        WorldTransparencyPipeline.afterWorldTranslucency();
        WorldTransparencyPipeline.endFrame();
        bind(source);
        GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_COLOR_ATTACHMENT1});
        GL46C.glDepthMask(true);
        GL46C.glColorMask(true, true, true, true);
        GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, CHEST);
        GL46C.glClearBufferfv(GL46C.GL_COLOR, 1, new float[]{.3f, .4f, .5f, 1});
        GL46C.glClearDepth(1);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        WorldTransparencyPipeline.beginFrame(false, true, true);
        drawModel(alpha);
        // A chest is submitted after the model: behind on the left, in front on the right.
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
        GL46C.glScissor(0, 0, 32, SIZE);
        drawSolid(.4f);
        GL46C.glScissor(32, 0, 32, SIZE);
        drawSolid(-.6f);
        GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_COLOR_ATTACHMENT1});
        int event = sharedWrapper ? wrapper(source) : stage;
        bind(event);
        WorldTransparencyPipeline.afterOpaqueWorld();
        check(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == event, "opaque stage FBO restored");
        // Later water can write closer depth. It must not hide the saved low-alpha veil.
        bind(source);
        GL46C.glColorMask(false, false, false, false);
        drawSolid(-.9f);
        GL46C.glColorMask(true, true, true, true);
        bind(event);
        GL46C.glDepthMask(false);
        int originalProgram = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        WorldTransparencyPipeline.afterWorldTranslucency();
        check(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == event, "resolve stage FBO restored");
        check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM) == originalProgram, "resolve program restored");
        check(!GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK), "resolve depth mask restored");
        bind(source);
        check(GL46C.glGetInteger(GL46C.GL_DRAW_BUFFER1) == GL46C.GL_COLOR_ATTACHMENT1, "source MRT restored");
        expect(source, GL46C.GL_COLOR_ATTACHMENT0, 16,
                new float[]{CHEST[0] + (1 - CHEST[0]) * alpha, CHEST[1] + (1 - CHEST[1]) * alpha,
                        CHEST[2] + (1 - CHEST[2]) * alpha, 1}, "veil in front of chest alpha=" + alpha);
        expect(source, GL46C.GL_COLOR_ATTACHMENT0, 40, CHEST, "chest in front occludes veil");
        expect(source, GL46C.GL_COLOR_ATTACHMENT0, 60, CHEST, "transparent hole");
        expect(source, GL46C.GL_COLOR_ATTACHMENT1, 16, new float[]{.3f, .4f, .5f, 1}, "auxiliary material untouched");
        // A following composite-like pass samples this exact color storage, not the callback FBO.
        int color = attachment(source, GL46C.GL_COLOR_ATTACHMENT0);
        bind(stage);
        GL46C.glDisable(GL46C.GL_DEPTH_TEST);
        GL46C.glDisable(GL46C.GL_BLEND);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        GL46C.glUseProgram(solid);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, color);
        GL46C.glUniform1i(GL46C.glGetUniformLocation(solid, "SampleScene"), 1);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        expect(stage, GL46C.GL_COLOR_ATTACHMENT0, 16,
                new float[]{CHEST[0] + (1 - CHEST[0]) * alpha, CHEST[1] + (1 - CHEST[1]) * alpha,
                        CHEST[2] + (1 - CHEST[2]) * alpha, 1}, "later composite samples veil");
        GL46C.glUniform1i(GL46C.glGetUniformLocation(solid, "SampleScene"), 0);
        WorldTransparencyPipeline.endFrame();
        if (sharedWrapper) GL46C.glDeleteFramebuffers(event);
    }

    private static void unverifiedTargetFallsBack() {
        bind(stage);
        WorldTransparencyPipeline.beginFrame(false, true, true);
        WorldTransparencyPipeline.afterOpaqueWorld();
        WorldTransparencyPipeline.afterWorldTranslucency();
        WorldTransparencyPipeline.endFrame();
        bind(source);
        GL46C.glDepthMask(true);
        GL46C.glClearDepth(1);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        WorldTransparencyPipeline.beginFrame(false, true, true);
        GL46C.glUseProgram(model);
        GL46C.glEnable(GL46C.GL_DEPTH_TEST);
        GL46C.glDepthFunc(GL46C.GL_LEQUAL);
        try (var ignored = WorldTransparencyPipeline.modelScene(RenderScene.WORLD)) {
            check(!WorldTransparencyPipeline.prepareModel(), "unverified/flipped storage must retain immediate path");
            GL46C.glUniform1f(GL46C.glGetUniformLocation(model, "Depth"), -.2f);
            ModelMaterial material = new ModelMaterial();
            material.diffuse = new float[]{0, 0, 0, 1};
            material.ambient = new float[]{1, 1, 1};
            material.specular = new float[3];
            material.hasAlpha = material.hasTranslucentPixels = true;
            ByteBuffer mesh = buffer(20).putInt(0, 0).putInt(4, 0).putInt(8, 3).putFloat(12, .12f)
                    .put(16, (byte) 1).put(17, (byte) 1);
            SubMeshDrawHelper.draw(mesh, 1, 4, GL46C.GL_UNSIGNED_INT, id -> white, (id, a) -> a,
                    new ModelMaterial[]{material}, null, false, id -> {});
            float[] depth = new float[1];
            GL46C.glReadPixels(16, 16, 1, 1, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, depth);
            check(depth[0] == 1, "rejected model must not seal depth while frame queue is active: " + depth[0]);
            GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
            drawSolid(.4f);
            expect(source, GL46C.GL_COLOR_ATTACHMENT0, 16, CHEST,
                    "late chest must survive per-model queue rejection");
        }
        WorldTransparencyPipeline.endFrame();
    }

    private static void replacedAttachmentIsNotWritten() {
        bind(source);
        GL46C.glDepthMask(true);
        GL46C.glClearDepth(1);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        WorldTransparencyPipeline.beginFrame(false, true, true);
        drawModel(.5f);
        bind(stage);
        WorldTransparencyPipeline.afterOpaqueWorld();
        int original = attachment(source, GL46C.GL_COLOR_ATTACHMENT0);
        int replacement = texture(GL46C.GL_RGBA16F, SIZE);
        bind(source);
        GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0,
                GL46C.GL_TEXTURE_2D, replacement, 0);
        GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, CHEST);
        bind(stage);
        WorldTransparencyPipeline.afterWorldTranslucency();
        expect(source, GL46C.GL_COLOR_ATTACHMENT0, 16, CHEST, "replaced attachment is not written");
        bind(source);
        GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0,
                GL46C.GL_TEXTURE_2D, original, 0);
        GL46C.glDeleteTextures(replacement);
        WorldTransparencyPipeline.endFrame();
    }

    private static void drawModel(float alpha) {
        drawModel(alpha, true);
    }

    private static void drawModel(float alpha, boolean captureExpected) {
        drawModel(alpha, captureExpected, -.2f, new float[]{1, 1, 1});
    }

    private static void drawModel(float alpha, boolean captureExpected, float depth, float[] rgb) {
        GL46C.glEnable(GL46C.GL_DEPTH_TEST);
        GL46C.glDepthFunc(GL46C.GL_LEQUAL);
        GL46C.glUseProgram(model);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(model, "Depth"), depth);
        ModelMaterial material = new ModelMaterial();
        material.diffuse = new float[]{0, 0, 0, 1};
        material.ambient = rgb;
        material.specular = new float[3];
        material.hasAlpha = material.hasTranslucentPixels = true;
        ByteBuffer mesh = buffer(20).putInt(0, 0).putInt(4, 0).putInt(8, 3).putFloat(12, alpha)
                .put(16, (byte) 1).put(17, (byte) 1);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, white);
        try (var ignored = WorldTransparencyPipeline.modelScene(RenderScene.WORLD)) {
            SubMeshDrawHelper.draw(mesh, 1, 4, GL46C.GL_UNSIGNED_INT, id -> white, (id, a) -> a,
                    new ModelMaterial[]{material}, null, false, id -> {});
            check(WorldTransparencyPipeline.hasWorldQueue() == captureExpected,
                    "model capture state must match target eligibility");
        }
        check(!WorldTransparencyPipeline.classicDepthPrepass(), "no early translucent depth seal");
    }

    private static void multiModelLateOpaque(boolean reverse, boolean shaderPack, boolean opaquePmx) throws Exception {
        WorldTransparencyPipeline.release();
        fixture("primaryColorOutput", true);
        fixture("stablePrimaryColorOutput", true);
        bind(stage);
        WorldTransparencyPipeline.beginFrame(false, true, shaderPack);
        WorldTransparencyPipeline.afterWorldTranslucency();
        WorldTransparencyPipeline.endFrame();
        bind(source);
        GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        GL46C.glColorMask(true, true, true, true);
        GL46C.glDepthMask(true);
        GL46C.glClearDepth(1);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, CHEST);
        if (System.getProperty("mmdskin.debugTransparencyPixel") != null) {
            var instance = WorldTransparencyPipeline.class.getDeclaredField("INSTANCE");
            var frame = WorldTransparencyPipeline.class.getDeclaredField("debugFrame");
            instance.setAccessible(true); frame.setAccessible(true);
            frame.setInt(instance.get(null), 120);
        }
        WorldTransparencyPipeline.beginFrame(false, true, shaderPack);
        int second = shaderPack ? wrapper(source) : source;
        for (int i = 0; i < 2; i++) {
            bind(i == 0 ? source : second);
            boolean front = (i == 0) == reverse;
            drawModel(.5f, true, front ? -.4f : .2f,
                    front ? new float[]{0, 1, 0} : new float[]{1, 0, 0});
        }
        if (opaquePmx) {
            bind(source);
            GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
            GL46C.glScissor(32, 0, 32, SIZE);
            drawModel(1, true, -.6f, new float[]{CHEST[0], CHEST[1], CHEST[2]});
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        }
        bind(stage);
        WorldTransparencyPipeline.afterEntities();
        WorldTransparencyPipeline.afterOpaqueWorld();
        // Iris may submit buffered vanilla items only AFTER both loader callbacks.
        bind(source);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
        GL46C.glScissor(32, 0, 32, SIZE);
        if (!opaquePmx) drawSolid(-.6f);
        GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        // The new callback is at RenderType.translucent(), after Iris's buffered flush.
        // Reflection lets the same regression run against the unmodified PR baseline.
        GL46C.glPixelStorei(GL46C.GL_PACK_SKIP_PIXELS, 3);
        GL46C.glPixelStorei(GL46C.GL_PACK_SKIP_ROWS, 2);
        GL46C.glPixelStorei(GL46C.GL_PACK_ROW_LENGTH, 8);
        try {
            WorldTransparencyPipeline.class.getMethod("beforeWorldTranslucency").invoke(null);
        } catch (NoSuchMethodException baseline) {
            System.out.println("Baseline has no post-flush/pre-water depth callback");
        }
        check(GL46C.glGetInteger(GL46C.GL_PACK_SKIP_PIXELS) == 3
                        && GL46C.glGetInteger(GL46C.GL_PACK_SKIP_ROWS) == 2
                        && GL46C.glGetInteger(GL46C.GL_PACK_ROW_LENGTH) == 8,
                "pixel diagnostics restore pack layout");
        GL46C.glPixelStorei(GL46C.GL_PACK_SKIP_PIXELS, 0);
        GL46C.glPixelStorei(GL46C.GL_PACK_SKIP_ROWS, 0);
        GL46C.glPixelStorei(GL46C.GL_PACK_ROW_LENGTH, 0);
        // Water's nearer depth must never become the opaque cutoff.
        if (shaderPack) {
            GL46C.glColorMask(false, false, false, false);
            drawSolid(-.9f);
            GL46C.glColorMask(true, true, true, true);
        }
        bind(shaderPack ? stage : source);
        WorldTransparencyPipeline.afterWorldTranslucency();
        expect(source, GL46C.GL_COLOR_ATTACHMENT0, 16,
                new float[]{.25f + CHEST[0] * .25f, .5f + CHEST[1] * .25f, CHEST[2] * .25f, 1},
                "two PMX layers, wrapper sharing, reverse=" + reverse);
        expect(source, GL46C.GL_COLOR_ATTACHMENT0, 40, CHEST,
                "opaque PMX/item must occlude both PMX layers, reverse=" + reverse + " opaquePmx=" + opaquePmx);
        System.out.println("PASS multi-PMX reverse=" + reverse + " shaderPack=" + shaderPack
                + " opaquePmx=" + opaquePmx + " finalRight=" + java.util.Arrays.toString(pixel(source, 40)));
        WorldTransparencyPipeline.endFrame();
        if (second != source) GL46C.glDeleteFramebuffers(second);
    }

    private static void drawSolid(float depth) {
        GL46C.glUseProgram(solid);
        GL46C.glDisable(GL46C.GL_BLEND);
        GL46C.glEnable(GL46C.GL_DEPTH_TEST);
        GL46C.glDepthFunc(GL46C.GL_LEQUAL);
        GL46C.glDepthMask(true);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(solid, "Depth"), depth);
        GL46C.glUniform4f(GL46C.glGetUniformLocation(solid, "Color"), CHEST[0], CHEST[1], CHEST[2], 1);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
    }

    /** Failed on r5; the default now runs the actual production water capture scope. */
    private static void waterOrder(boolean modelBehind, boolean sharedReference) throws Exception {
        WorldTransparencyPipeline.release();
        fixture("primaryColorOutput", true);
        fixture("stablePrimaryColorOutput", true);
        bind(stage);
        WorldTransparencyPipeline.beginFrame(false, true, true);
        WorldTransparencyPipeline.afterWorldTranslucency();
        WorldTransparencyPipeline.endFrame();
        bind(source);
        GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        GL46C.glColorMask(true, true, true, true);
        GL46C.glDepthMask(true);
        GL46C.glClearDepth(1);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, CHEST);
        GL46C.glClearBufferfv(GL46C.GL_COLOR, 1, new float[]{.2f,.3f,.4f,1});
        WorldTransparencyPipeline.beginFrame(false, true, true);
        drawModel(.5f, true, modelBehind ? .4f : -.6f, new float[]{1, 1, 1});
        bind(stage);
        WorldTransparencyPipeline.afterEntities();
        WorldTransparencyPipeline.afterOpaqueWorld();
        bind(source);
        WorldTransparencyPipeline.beforeWorldTranslucency();
        GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_COLOR_ATTACHMENT1});
        int waterProgram = sharedReference ? solid : water;
        GL46C.glUseProgram(waterProgram);
        GL46C.glUniform1i(GL46C.glGetUniformLocation(waterProgram, "SampleScene"), 0);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(waterProgram, "Depth"), -.2f);
        GL46C.glUniform4f(GL46C.glGetUniformLocation(waterProgram, "Color"), 0, 0, 1, testWaterAlpha);
        GL46C.glEnable(GL46C.GL_DEPTH_TEST);
        GL46C.glDepthFunc(GL46C.GL_LEQUAL);
        GL46C.glDepthMask(true);
        GL46C.glEnable(GL46C.GL_BLEND);
        GL46C.glBlendEquation(GL46C.GL_FUNC_ADD);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
        if (sharedReference) {
            // Reference only: submit water RGBA/depth to the existing list, then emulate
            // native water depth without a duplicate primary-color blend. No production hook.
            try (var capture = WorldTransparencyPipeline.capture()) {
                GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
            }
            GL46C.glColorMask(false, false, false, false);
            GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
            GL46C.glColorMask(true, true, true, true);
        } else {
            int slot = GL46C.glGetInteger(GL46C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) - 2;
            int prior = GL46C.glGenBuffers();
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, prior);
            int alignment = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
            GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER,alignment + 64L,GL46C.GL_DYNAMIC_DRAW);
            GL46C.glBindBufferRange(GL46C.GL_SHADER_STORAGE_BUFFER,slot,prior,alignment,64);
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, prior);
            GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, prior);
            try (var capture = WorldTransparencyPipeline.beginWaterCapture()) {
                check(capture != null, "verified water joins the production queue");
                check(GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK), "native water depth writes remain active");
                GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
            }
            check(GL46C.glGetIntegeri(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING,slot)==prior
                    && GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_START,slot)==alignment
                    && GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_SIZE,slot)==64
                    && GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING)==prior,
                    "water scope restores existing SSBO ranges and generic binding");
            check(GL46C.glGetInteger(GL46C.GL_COPY_READ_BUFFER_BINDING) == prior
                    && GL46C.glGetInteger(GL46C.GL_COPY_WRITE_BUFFER_BINDING) == prior,
                    "water scope preserves both copy-buffer bindings");
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, 0);
            GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, 0);
            GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER,slot,0);
            GL46C.glDeleteBuffers(prior);
            expect(source, GL46C.GL_COLOR_ATTACHMENT0, 40, CHEST, "water RGB is not blended twice");
            expect(source, GL46C.GL_COLOR_ATTACHMENT1, 40,
                    nativeAuxiliary == null ? (testWaterAlpha == 0 ? new float[]{.2f,.3f,.4f,1}
                            : new float[]{.75f,.25f,.5f,1}) : nativeAuxiliary,
                    "native water auxiliary MRT remains active");
            check(Math.abs(readDepth(source, 40) - (nativeWaterPixel == null && testWaterAlpha == 0 ? 1 : .4f)) < 1e-6,
                    "native water depth stored only for surviving fragments");
            if (nativeWaterPixel == null) check(readDepth(source, 2) == 1, "discard does not leave an early water depth");
            check(GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_RGB, 0) == GL46C.GL_SRC_ALPHA
                    && GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_RGB, 0) == GL46C.GL_ONE_MINUS_SRC_ALPHA,
                    "native water blend restored");
            check(GL46C.glGetUniformi(waterProgram, GL46C.glGetUniformLocation(waterProgram, "MmdWaterCapture")) == 0,
                    "water capture disabled at scope exit");
            WorldTransparencyPipeline.afterWaterCapture();
        }
        bind(stage);
        WorldTransparencyPipeline.afterWorldTranslucency();
        float[] referenceWater = nativeWaterPixel == null ? new float[]{0,0,1,testWaterAlpha} : nativeWaterPixel;
        float[] expected = new float[]{0,0,0,1};
        float a = referenceWater[3];
        for (int c = 0; c < 3; c++) expected[c] = modelBehind
                ? referenceWater[c] * a + (.5f + .5f * CHEST[c]) * (1 - a)
                : .5f + .5f * (referenceWater[c] * a + CHEST[c] * (1 - a));
        System.out.println("Water order sharedReference=" + sharedReference + " modelBehind=" + modelBehind
                + " actual=" + java.util.Arrays.toString(pixel(source, 40))
                + " expected=" + java.util.Arrays.toString(expected));
        float[] outsideWater = nativeWaterOutside == null ? referenceWater : nativeWaterOutside;
        float[] outside = new float[]{0,0,0,1};
        for (int c = 0; c < 3; c++)
            outside[c] = outsideWater[c] * outsideWater[3] + CHEST[c] * (1 - outsideWater[3]);
        expect(source, GL46C.GL_COLOR_ATTACHMENT0, 60, outside,
                "water remains correct outside PMX coverage");
        expect(source, GL46C.GL_COLOR_ATTACHMENT0, 40, expected,
                "water must attenuate PMX only when the PMX is behind it");
        WorldTransparencyPipeline.endFrame();
    }

    /** Real BSL fragment shader with controlled varyings, textures and host uniforms. */
    public static void nativeWaterScenario(String fragment) throws Exception {
        WorldTransparencyPipeline.release();
        setup();
        water = program("""
                #version 330 core
                uniform float Depth;
                out float iris_FogFragCoord, mat, dist;
                out vec2 texCoord, lmCoord;
                out vec3 normal, binormal, tangent, sunVec, upVec, eastVec, viewVector;
                out vec4 color, vTexCoord, vTexCoordAM;
                void main() {
                    vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2)*2.0-1.0;
                    gl_Position=vec4(p,Depth,1);
                    iris_FogFragCoord=0; mat=1; dist=1;
                    texCoord=p*.5+.5; lmCoord=vec2(.5,1);
                    normal=vec3(0,0,1); binormal=vec3(0,1,0); tangent=vec3(1,0,0);
                    sunVec=vec3(0,1,0); upVec=sunVec; eastVec=tangent; viewVector=normal;
                    color=vec4(.4,.6,.8,1); vTexCoord=vec4(.5,.5,0,0); vTexCoordAM=vec4(0,0,1,1);
                }
                """, fragment);
        GL46C.glUseProgram(water);
        check(WorldTransparencyPipeline.beginWaterCapture()==null,"no water redirection without a PMX frame queue");
        float[] identity = new float[16]; new Matrix4f().get(identity);
        for (String name : new String[]{"iris_ModelViewMatrix", "iris_ProjectionMatrix", "iris_ProjectionMatrixInverse",
                "iris_ModelViewMatrixInverse", "gbufferProjection", "gbufferProjectionInverse", "gbufferModelView",
                "gbufferModelViewInverse", "shadowProjection", "shadowModelView", "gbufferPreviousProjection",
                "gbufferPreviousModelView", "iris_LightmapTextureMatrix"})
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(water,name),false,identity);
        for (String name : new String[]{"viewWidth", "viewHeight"}) uniformWater(name, SIZE);
        uniformWater("near",.05f); uniformWater("far",256); uniformWater("timeAngle",.25f);
        uniformWater("screenBrightness",1); uniformWater("timeBrightness",1); uniformWater("shadowFade",1);
        uniformWater("iris_FogEnd",512); uniformWater("iris_currentAlphaTest",.001f);
        GL46C.glUniform2i(GL46C.glGetUniformLocation(water,"eyeBrightnessSmooth"),120,240);
        GL46C.glUniform3f(GL46C.glGetUniformLocation(water,"cameraPosition"),0,64,0);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,white);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE1);
        int shadow = texture(GL46C.GL_DEPTH_COMPONENT24, 1);
        GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D,0,0,0,1,1,GL46C.GL_DEPTH_COMPONENT,GL46C.GL_FLOAT,new float[]{1});
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_COMPARE_MODE,GL46C.GL_COMPARE_REF_TO_TEXTURE);
        for (String name : new String[]{"shadowtex0","shadowtex1"})
            GL46C.glUniform1i(GL46C.glGetUniformLocation(water,name),1);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        bind(source);
        GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0,GL46C.GL_COLOR_ATTACHMENT1});
        GL46C.glDisable(GL46C.GL_BLEND); GL46C.glDisable(GL46C.GL_DEPTH_TEST);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(water,"Depth"),-.2f);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES,0,3);
        nativeWaterPixel=pixel(source,40); nativeWaterOutside=pixel(source,60);
        GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT1);
        nativeAuxiliary=new float[4];
        GL46C.glReadPixels(40,32,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,nativeAuxiliary);
        for(float value:nativeWaterPixel) check(Float.isFinite(value),"finite real BSL water output");
        check(nativeWaterPixel[3] > .001f && nativeWaterPixel[3] < 1,"nontrivial BSL water alpha");
        System.out.println("Native BSL raw water="+java.util.Arrays.toString(nativeWaterPixel));
        try { waterOrder(false,false); waterOrder(true,false); }
        finally { nativeWaterPixel=nativeWaterOutside=nativeAuxiliary=null; GL46C.glDeleteTextures(shadow); }
        check(GL46C.glGetError()==GL46C.GL_NO_ERROR,"native BSL water GL state");
    }

    private static void uniformWater(String name,float value) {
        GL46C.glUniform1f(GL46C.glGetUniformLocation(water,name),value);
    }

    private static float readDepth(int fbo, int x) {
        int previous = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, fbo);
        float[] value = new float[1];
        GL46C.glReadPixels(x, 32, 1, 1, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, value);
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, previous);
        return value[0];
    }

    private static void waterEmptyPass() throws Exception {
        WorldTransparencyPipeline.release();
        fixture("primaryColorOutput", true); fixture("stablePrimaryColorOutput", true);
        bind(source); WorldTransparencyPipeline.beginFrame(false, true, true);
        WorldTransparencyPipeline.afterWorldTranslucency(); WorldTransparencyPipeline.endFrame();
        bind(source);
        GL46C.glDepthMask(true); GL46C.glColorMask(true, true, true, true);
        GL46C.glClearDepth(1); GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, CHEST);
        WorldTransparencyPipeline.beginFrame(false, true, true);
        drawModel(.5f, true, 0, new float[]{1, 1, 1});
        WorldTransparencyPipeline.beforeWorldTranslucency();
        GL46C.glUseProgram(water);
        GL46C.glDepthMask(true);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(water, "Depth"), -.2f);
        GL46C.glUniform4f(GL46C.glGetUniformLocation(water, "Color"), 0, 0, 1, 0);
        try (var scope = WorldTransparencyPipeline.beginWaterCapture()) {
            check(scope != null, "empty water scope active");
            GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        }
        WorldTransparencyPipeline.afterWaterCapture();
        expect(source, GL46C.GL_COLOR_ATTACHMENT0, 40, CHEST, "empty water does not resolve PMX early");
        drawModel(.25f, true, .4f, new float[]{1, 0, 0});
        WorldTransparencyPipeline.afterWaterCapture(); // A subsequent non-water program reset.
        expect(source, GL46C.GL_COLOR_ATTACHMENT0, 40, CHEST, "non-water reset does not consume the queue");
        GL46C.glUseProgram(water);
        GL46C.glEnable(GL46C.GL_BLEND);
        GL46C.glBlendEquation(GL46C.GL_FUNC_ADD);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
        GL46C.glDepthMask(true);
        GL46C.glUniform4f(GL46C.glGetUniformLocation(water, "Color"), 0, 0, 1, .5f);
        try (var scope = WorldTransparencyPipeline.beginWaterCapture()) {
            check(scope != null, "nonempty water after discarded scope active");
            GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        }
        WorldTransparencyPipeline.afterWaterCapture();
        expect(source, GL46C.GL_COLOR_ATTACHMENT0, 40, new float[]{.35f, .26875f, .759375f, 1},
                "water after empty scope sorts with both PMX submissions");
        WorldTransparencyPipeline.afterWorldTranslucency(); WorldTransparencyPipeline.endFrame();
        System.out.println("PASS empty water keeps late PMX admission and later water ordering");
    }

    private static void waterCrossing() throws Exception {
        WorldTransparencyPipeline.release();
        fixture("primaryColorOutput",true); fixture("stablePrimaryColorOutput",true);
        bind(stage); WorldTransparencyPipeline.beginFrame(false,true,true);
        WorldTransparencyPipeline.afterWorldTranslucency(); WorldTransparencyPipeline.endFrame();
        bind(source);
        GL46C.glDepthMask(true); GL46C.glColorMask(true,true,true,true);
        GL46C.glClearDepth(1); GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glClearBufferfv(GL46C.GL_COLOR,0,CHEST);
        WorldTransparencyPipeline.beginFrame(false,true,true);
        drawModel(.5f,true,0,new float[]{1,1,1});
        WorldTransparencyPipeline.afterOpaqueWorld(); WorldTransparencyPipeline.beforeWorldTranslucency();
        GL46C.glUseProgram(water);
        GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0,GL46C.GL_COLOR_ATTACHMENT1});
        GL46C.glEnable(GL46C.GL_BLEND); GL46C.glEnable(GL46C.GL_DEPTH_TEST);
        GL46C.glDepthFunc(GL46C.GL_LEQUAL); GL46C.glDepthMask(true);
        GL46C.glBlendEquation(GL46C.GL_FUNC_ADD);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA,GL46C.GL_ONE_MINUS_SRC_ALPHA,GL46C.GL_ONE,GL46C.GL_ONE_MINUS_SRC_ALPHA);
        try (var capture=WorldTransparencyPipeline.beginWaterCapture()) {
            check(capture!=null,"crossing water enters production scope");
            GL46C.glUniform1f(GL46C.glGetUniformLocation(water,"Depth"),.8f);
            GL46C.glUniform1f(GL46C.glGetUniformLocation(water,"Slope"),0);
            GL46C.glUniform4f(GL46C.glGetUniformLocation(water,"Color"),0,0,1,.5f);
            GL46C.glDrawArrays(GL46C.GL_TRIANGLES,0,3);
            GL46C.glUniform1f(GL46C.glGetUniformLocation(water,"Depth"),0);
            GL46C.glUniform1f(GL46C.glGetUniformLocation(water,"Slope"),.6f);
            GL46C.glUniform4f(GL46C.glGetUniformLocation(water,"Color"),0,1,0,.25f);
            GL46C.glDrawArrays(GL46C.GL_TRIANGLES,0,3);
        }
        WorldTransparencyPipeline.afterWaterCapture();
        // A later translucent particle must see the completed native water color.
        expect(source,GL46C.GL_COLOR_ATTACHMENT0,16,new float[]{.4125f,.64375f,.571875f,1},
                "water color restored before later particle/entity submission");
        GL46C.glUseProgram(solid);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(solid,"Depth"),-.9f);
        GL46C.glUniform4f(GL46C.glGetUniformLocation(solid,"Color"),1,0,0,.5f);
        GL46C.glDepthMask(false);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES,0,3);
        bind(stage); WorldTransparencyPipeline.afterWorldTranslucency();
        expect(source,GL46C.GL_COLOR_ATTACHMENT0,16,new float[]{.70625f,.321875f,.2859375f,1},
                "later particle in front of crossing water and PMX; late callback does not resolve twice");
        expect(source,GL46C.GL_COLOR_ATTACHMENT0,40,new float[]{.76875f,.321875f,.3484375f,1},
                "later particle in front of PMX and both water layers");
        check(Math.abs(readDepth(source,16)-(.5f+.3f*((16.5f/SIZE)*2-1)))<1e-5,
                "crossing native water depth preserved");
        WorldTransparencyPipeline.endFrame();
        System.out.println("PASS crossing PMX/two-water-layer per-pixel ordering");
    }

    private static void waterOverflow() throws Exception {
        WorldTransparencyPipeline.release();
        fixture("primaryColorOutput",true); fixture("stablePrimaryColorOutput",true);
        bind(stage); WorldTransparencyPipeline.beginFrame(false,true,true);
        WorldTransparencyPipeline.afterWorldTranslucency(); WorldTransparencyPipeline.endFrame();
        bind(source);
        GL46C.glDepthMask(true); GL46C.glColorMask(true,true,true,true);
        GL46C.glClearDepth(1); GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glClearBufferfv(GL46C.GL_COLOR,0,CHEST);
        WorldTransparencyPipeline.beginFrame(false,true,true);
        drawModel(.5f,true,0,new float[]{1,1,1});
        WorldTransparencyPipeline.afterOpaqueWorld(); WorldTransparencyPipeline.beforeWorldTranslucency();
        GL46C.glUseProgram(water);
        GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0,GL46C.GL_COLOR_ATTACHMENT1});
        GL46C.glEnable(GL46C.GL_BLEND); GL46C.glEnable(GL46C.GL_DEPTH_TEST);
        GL46C.glDepthFunc(GL46C.GL_LEQUAL); GL46C.glDepthMask(true);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA,GL46C.GL_ONE_MINUS_SRC_ALPHA,GL46C.GL_ONE,GL46C.GL_ONE_MINUS_SRC_ALPHA);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(water,"Depth"),-.2f);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(water,"Slope"),0);
        GL46C.glUniform4f(GL46C.glGetUniformLocation(water,"Color"),0,0,1,.01f);
        try(var capture=WorldTransparencyPipeline.beginWaterCapture()) {
            check(capture!=null,"overflow water scope active");
            for(int i=0;i<25;i++) GL46C.glDrawArrays(GL46C.GL_TRIANGLES,0,3);
        }
        check(capturedFragments()>SIZE*SIZE*8,"water test exceeds normal node capacity");
        WorldTransparencyPipeline.afterWaterCapture();
        float transmission=(float)Math.pow(.99,25);
        expect(source,GL46C.GL_COLOR_ATTACHMENT0,40,new float[]{.6f*transmission,.55f*transmission,
                1-transmission+.525f*transmission,1},"overflow retains same-color water coverage above PMX");
        bind(stage); WorldTransparencyPipeline.afterWorldTranslucency(); WorldTransparencyPipeline.endFrame();
        System.out.println("PASS water overflow coverage (weighted tail remains approximate)");
    }

    /** Synthetic throughput only: no game, physics, shader-pack lighting or post processing. */
    private static void waterPerformance() throws Exception {
        System.setProperty("mmdskin.debugTransparencyStages", "false");
        System.setProperty("mmdskin.debugTransparencyModels", "false");
        System.setProperty("mmdskin.profileWorldTransparency", "false");
        System.clearProperty("mmdskin.debugTransparencyPixel");
        bind(source);
        WorldTransparencyPipeline.beginFrame(false, true, true);
        WorldTransparencyPipeline.afterWorldTranslucency();
        WorldTransparencyPipeline.endFrame();
        System.out.println("Water benchmark renderer=" + GL46C.glGetString(GL46C.GL_RENDERER)
                + " size=" + SIZE + "x" + SIZE + " warmup=12 samples=48");
        for (int layers : new int[]{1, 4}) {
            long[] wall = new long[48], capture = new long[48], resolve = new long[48];
            int query = GL46C.glGenQueries();
            for (int sample = -12; sample < wall.length; sample++) {
                bind(source);
                GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
                GL46C.glDepthMask(true);
                GL46C.glClearDepth(1);
                GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
                GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, CHEST);
                WorldTransparencyPipeline.beginFrame(false, true, true);
                drawModel(.5f, true, .2f, new float[]{1, 1, 1});
                WorldTransparencyPipeline.beforeWorldTranslucency();
                GL46C.glUseProgram(water);
                GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_COLOR_ATTACHMENT1});
                GL46C.glEnable(GL46C.GL_DEPTH_TEST);
                GL46C.glDepthFunc(GL46C.GL_LEQUAL);
                GL46C.glDepthMask(true);
                GL46C.glEnable(GL46C.GL_BLEND);
                GL46C.glBlendEquation(GL46C.GL_FUNC_ADD);
                GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                        GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
                GL46C.glUniform4f(GL46C.glGetUniformLocation(water, "Color"), 0, 0, 1, .3f);
                GL46C.glFinish(); // Benchmark isolation only; never used by production code.
                long start = System.nanoTime();
                try (var scope = WorldTransparencyPipeline.beginWaterCapture()) {
                    check(scope != null, "benchmark uses production water scope");
                    GL46C.glBeginQuery(GL46C.GL_TIME_ELAPSED, query);
                    for (int layer = 0; layer < layers; layer++) {
                        GL46C.glUniform1f(GL46C.glGetUniformLocation(water, "Depth"), .8f - layer * .2f);
                        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
                    }
                    GL46C.glEndQuery(GL46C.GL_TIME_ELAPSED);
                }
                WorldTransparencyPipeline.afterWaterCapture();
                GL46C.glFinish();
                long elapsed = System.nanoTime() - start;
                long draw = GL46C.glGetQueryObjectui64(query, GL46C.GL_QUERY_RESULT);
                long composite = resolveGpuTime(query);
                WorldTransparencyPipeline.afterWorldTranslucency();
                WorldTransparencyPipeline.endFrame();
                if (sample >= 0) { wall[sample] = elapsed; capture[sample] = draw; resolve[sample] = composite; }
            }
            GL46C.glDeleteQueries(query);
            java.util.Arrays.sort(wall); java.util.Arrays.sort(capture); java.util.Arrays.sort(resolve);
            System.out.printf(java.util.Locale.ROOT,
                    "WATER_PERF layers=%d stageWallMedianMs=%.4f stageWallP95Ms=%.4f captureGpuMedianMs=%.4f captureGpuP95Ms=%.4f resolveGpuMedianMs=%.4f resolveGpuP95Ms=%.4f%n",
                    layers, wall[24] / 1e6, wall[45] / 1e6, capture[24] / 1e6, capture[45] / 1e6,
                    resolve[24] / 1e6, resolve[45] / 1e6);
        }
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "water benchmark GL state");
    }

    /** Re-submit the same production resolve to measure its draw independently of host readback. */
    private static long resolveGpuTime(int query) throws Exception {
        var singleton = WorldTransparencyPipeline.class.getDeclaredField("INSTANCE");
        singleton.setAccessible(true);
        Object pipeline = singleton.get(null);
        java.util.function.ToIntFunction<String> field = name -> {
            try {
                var value = WorldTransparencyPipeline.class.getDeclaredField(name);
                value.setAccessible(true);
                return value.getInt(pipeline);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        };
        int metaSlot = field.applyAsInt("metaBinding"), nodeSlot = field.applyAsInt("nodeBinding");
        int oldProgram = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        int meta = GL46C.glGetIntegeri(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, metaSlot);
        int nodes = GL46C.glGetIntegeri(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, nodeSlot);
        int generic = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
        GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, metaSlot, field.applyAsInt("meta"));
        GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, nodeSlot, field.applyAsInt("nodes"));
        bind(source); GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        GL46C.glDisable(GL46C.GL_DEPTH_TEST); GL46C.glDisable(GL46C.GL_BLEND);
        GL46C.glUseProgram(field.applyAsInt("resolveProgram"));
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, field.applyAsInt("depthTexture"));
        GL46C.glBeginQuery(GL46C.GL_TIME_ELAPSED, query);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        GL46C.glEndQuery(GL46C.GL_TIME_ELAPSED);
        GL46C.glFinish();
        long elapsed = GL46C.glGetQueryObjectui64(query, GL46C.GL_QUERY_RESULT);
        GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, metaSlot, meta);
        GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, nodeSlot, nodes);
        GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, generic);
        GL46C.glUseProgram(oldProgram);
        return elapsed;
    }

    private static int target() {
        int fbo = GL46C.glGenFramebuffers();
        bind(fbo);
        for (int i = 0; i < 2; i++) GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER,
                GL46C.GL_COLOR_ATTACHMENT0 + i, GL46C.GL_TEXTURE_2D, texture(GL46C.GL_RGBA16F, SIZE), 0);
        int depth = texture(GL46C.GL_DEPTH_COMPONENT24, SIZE);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_TEXTURE_2D, depth, 0);
        GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_COLOR_ATTACHMENT1});
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "complete FBO");
        return fbo;
    }

    private static int wrapper(int original) {
        int[] attachments = {GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_COLOR_ATTACHMENT1, GL46C.GL_DEPTH_ATTACHMENT};
        int[] textures = new int[3];
        for (int i = 0; i < 3; i++) textures[i] = attachment(original, attachments[i]);
        int fbo = GL46C.glGenFramebuffers();
        bind(fbo);
        for (int i = 0; i < 3; i++) GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER,
                attachments[i], GL46C.GL_TEXTURE_2D, textures[i], 0);
        GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_COLOR_ATTACHMENT1});
        return fbo;
    }

    private static int attachment(int fbo, int attachment) {
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, fbo);
        return GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_READ_FRAMEBUFFER,
                attachment, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
    }

    private static void expect(int fbo, int attachment, int x, float[] expected, String message) {
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, fbo);
        GL46C.glReadBuffer(attachment);
        float[] actual = new float[4];
        GL46C.glReadPixels(x, 16, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, actual);
        for (int i = 0; i < 4; i++) check(Math.abs(actual[i] - expected[i]) < .001,
                message + " channel=" + i + " actual=" + actual[i] + " expected=" + expected[i]);
    }

    private static int texture(int format, int size) {
        int id = GL46C.glGenTextures();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
        GL46C.glTexStorage2D(GL46C.GL_TEXTURE_2D, 1, format, size, size);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        return id;
    }

    private static int program(String vertex, String fragment) {
        int vs = shader(GL46C.GL_VERTEX_SHADER, vertex), fs = shader(GL46C.GL_FRAGMENT_SHADER, fragment);
        int program = GL46C.glCreateProgram();
        GL46C.glAttachShader(program, vs);
        GL46C.glAttachShader(program, fs);
        GL46C.glLinkProgram(program);
        check(GL46C.glGetProgrami(program, GL46C.GL_LINK_STATUS) != 0, GL46C.glGetProgramInfoLog(program));
        GL46C.glDeleteShader(vs);
        GL46C.glDeleteShader(fs);
        return program;
    }

    private static int shader(int type, String source) {
        int shader = GL46C.glCreateShader(type);
        GL46C.glShaderSource(shader, source);
        GL46C.glCompileShader(shader);
        check(GL46C.glGetShaderi(shader, GL46C.GL_COMPILE_STATUS) != 0, GL46C.glGetShaderInfoLog(shader));
        return shader;
    }

    private static void bind(int fbo) {
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        GL46C.glViewport(0, 0, SIZE, SIZE);
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
