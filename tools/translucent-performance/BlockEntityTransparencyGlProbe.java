package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.compat.iris.IrisToonCompat;
import com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyPipeline;
import com.shiroha.mmdskin.render.scene.RenderScene;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Real GL regression: a public loader stage need not bind Iris's colortex0 FBO. */
public final class BlockEntityTransparencyGlProbe {
    private static final int SIZE = 64;
    private static int model, solid, source, stage, vao, white;
    private static final float[] CHEST = {.2f, .1f, .05f, 1};

    public static void main(String[] args) throws Exception {
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
            for (float alpha : new float[]{.002f, .01f, .12f, .5f}) scenario(alpha, false);
            scenario(.12f, true); // both stage callbacks bind a different wrapper of the same gbuffer
            replacedAttachmentIsNotWritten();
            fixture("stablePrimaryColorOutput", false);
            unverifiedTargetFallsBack();
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "final GL error");
            System.out.println("PASS BlockEntityTransparencyGlProbe: late chest front/behind, alpha .002/.01/.12/.5, "
                    + "holes, unrelated stage FBO, shared-storage wrapper, pre-water depth, MRT/state restoration, "
                    + "subsequent color sampling, replaced attachment protection, unverified-target fallback");
        } finally {
            WorldTransparencyPipeline.release();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void fixture(String name, boolean value) throws Exception {
        try {
            var field = IrisToonCompat.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(null, value);
        } catch (NoSuchFieldException e) {
            // Allows running this regression against the pre-fix production classes.
            if (!name.equals("stablePrimaryColorOutput")) throw e;
        }
    }

    private static void setup() {
        String vertex = """
                #version 330 core
                uniform float Depth;
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2) * 2.0 - 1.0;
                    gl_Position = vec4(p, Depth, 1.0);
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
        WorldTransparencyPipeline.beginFrame(false, true, true);
        GL46C.glUseProgram(model);
        try (var ignored = WorldTransparencyPipeline.modelScene(RenderScene.WORLD)) {
            check(!WorldTransparencyPipeline.prepareModel(), "unverified/flipped storage must retain immediate path");
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
        GL46C.glUseProgram(model);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(model, "Depth"), -.2f);
        ModelMaterial material = new ModelMaterial();
        material.diffuse = new float[]{0, 0, 0, 1};
        material.ambient = new float[]{1, 1, 1};
        material.specular = new float[3];
        material.hasAlpha = material.hasTranslucentPixels = true;
        ByteBuffer mesh = buffer(20).putInt(0, 0).putInt(4, 0).putInt(8, 3).putFloat(12, alpha)
                .put(16, (byte) 1).put(17, (byte) 1);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, white);
        try (var ignored = WorldTransparencyPipeline.modelScene(RenderScene.WORLD)) {
            SubMeshDrawHelper.draw(mesh, 1, 4, GL46C.GL_UNSIGNED_INT, id -> white, (id, a) -> a,
                    new ModelMaterial[]{material}, null, false, id -> {});
            check(WorldTransparencyPipeline.hasWorldQueue(), "verified stable colortex0 must defer despite unrelated stage FBO");
        }
        check(!WorldTransparencyPipeline.classicDepthPrepass(), "no early translucent depth seal");
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
