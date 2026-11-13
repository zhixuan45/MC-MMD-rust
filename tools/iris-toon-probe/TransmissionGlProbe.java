package com.shiroha.mmdskin.compat.iris;

import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import org.lwjgl.opengl.GL46C;

import java.util.Arrays;

/** Real MRT rasterization and exact restoration of one or two host color targets plus depth. */
public final class TransmissionGlProbe {
    public static void checkProtocol(String fragment, String vertex, boolean expected) {
        check(IrisTerrainShadowReplay.supports(fragment, vertex) == expected, "Shadow replay protocol");
        check((IrisTerrainShadowReplay.colorTargetCount(fragment, vertex) > 0) == expected,
                "Color target count agrees with shadow replay support");
    }

    public static void checkProtocol(String fragment, String vertex, int expectedTargets) {
        int count = IrisTerrainShadowReplay.colorTargetCount(fragment, vertex);
        check(count == expectedTargets, "Shadow replay color target count");
        check(IrisTerrainShadowReplay.supports(fragment, vertex) == (count > 0),
                "Shadow replay support agrees with color target count");
    }

    public static void run() {
        for (int sourceFormat : new int[]{GL46C.GL_RGBA, GL46C.GL_RGBA8,
                GL46C.GL_RGBA16F, GL46C.GL_RGBA32F}) {
            runDualTarget(sourceFormat);
            runLegacySingleTarget(sourceFormat);
        }
    }

    private static void runDualTarget(int sourceFormat) {
        if (!org.lwjgl.opengl.GL.getCapabilities().OpenGL43)
            throw new IllegalStateException("Transmission probe requires OpenGL 4.3");
        var initialState = new ShadowReplayGlState();
        int oldDraw = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldRead = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
        int sourceDepth = texture(GL46C.GL_DEPTH_COMPONENT24, GL46C.GL_DEPTH_COMPONENT);
        int opaqueDepth = texture(GL46C.GL_DEPTH_COMPONENT24, GL46C.GL_DEPTH_COMPONENT);
        int sourceColor0 = texture(sourceFormat, GL46C.GL_RGBA);
        int reportedFormat = GL46C.glGetTexLevelParameteri(
                GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_INTERNAL_FORMAT);
        if (sourceFormat == GL46C.GL_RGBA)
            check(reportedFormat == GL46C.GL_RGBA || reportedFormat == GL46C.GL_RGBA8,
                    "Unsized RGBA must resolve to verified 8-bit storage");
        int sourceColor1 = texture(sourceFormat, GL46C.GL_RGBA);
        System.out.println("Glass replay MRT source format: requested="
                + sourceFormat + ", reported=" + reportedFormat);
        int source = framebuffer(sourceDepth, sourceColor0, sourceColor1);
        int opaque = framebuffer(opaqueDepth);
        int vao = GL46C.glGenVertexArrays();
        int program = ShaderCompiler.compileRenderProgram("""
                #version 330 core
                void main() {
                    vec2 p=vec2((gl_VertexID << 1)&2,gl_VertexID&2);
                    gl_Position=vec4(p*2.0-1.0,-0.4,1.0);
                }
                """, """
                #version 330 core
                layout(location=0) out vec4 ShadowColor;
                layout(location=1) out vec4 LightColor;
                void main() {
                    if (gl_FragCoord.x > 12.0) discard;
                    ShadowColor=vec4(0.8,0.2,0.1,1.0);
                    LightColor=vec4(0.05,0.9,0.3,0.75);
                }
                """, "terrain-only dual-color glass replay");
        check(program != 0, "Dual-color glass caster compile");
        try {
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, source);
            GL46C.glViewport(0, 0, 16, 16);
            GL46C.glDepthMask(true);
            GL46C.glColorMask(true, true, true, true);
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glDrawBuffers(colorAttachments(2));
            GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, new float[]{.12f, .24f, .36f, .48f});
            GL46C.glClearBufferfv(GL46C.GL_COLOR, 1, new float[]{.56f, .44f, .32f, .20f});
            GL46C.glClearBufferfv(GL46C.GL_DEPTH, 0, new float[]{.8f});
            GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
            GL46C.glScissor(0, 0, 8, 16);
            GL46C.glClearBufferfv(GL46C.GL_DEPTH, 0, new float[]{.1f});
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, source);
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, opaque);
            GL46C.glBlitFramebuffer(0, 0, 16, 16, 0, 0, 16, 16,
                    GL46C.GL_DEPTH_BUFFER_BIT, GL46C.GL_NEAREST);
            float[] originalDepth = read(source, GL46C.GL_DEPTH_COMPONENT, 1);
            float[] originalColor0 = readColor(source, 0, 4);
            float[] originalColor1 = readColor(source, 1, 4);
            check(!Arrays.equals(originalColor0, originalColor1),
                    "Dual host color targets start with different texels");
            Runnable draw = () -> {
                GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, source);
                GL46C.glDrawBuffers(colorAttachments(2));
                GL46C.glUseProgram(program);
                GL46C.glBindVertexArray(vao);
                GL46C.glEnable(GL46C.GL_DEPTH_TEST);
                GL46C.glDepthFunc(GL46C.GL_LESS);
                GL46C.glDepthMask(true);
                GL46C.glDisable(GL46C.GL_BLEND);
                GL46C.glColorMask(true, true, true, true);
                GL46C.glDisable(GL46C.GL_CULL_FACE);
                GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
                GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
            };
            for (boolean fail : new boolean[]{false, true, false}) {
                prepareCallerState(source, opaque);
                try {
                    boolean captured = IrisTerrainTransmission.capture(
                            source, new int[]{sourceColor0, sourceColor1},
                            new int[]{sourceFormat, sourceFormat}, opaque,
                            16, GL46C.GL_DEPTH_COMPONENT24, () -> {
                                draw.run();
                                if (fail) throw new IllegalStateException("deliberate MRT replay failure");
                            });
                    check(!fail, "Exceptional MRT replay must propagate");
                    check(captured, "Dual-color glass capture");
                    checkDualTransmission();
                } catch (IllegalStateException expected) {
                    check(fail && expected.getMessage().equals("deliberate MRT replay failure"),
                            "Expected MRT replay failure");
                    check(IrisTerrainTransmission.depthTexture() == 0,
                            "Failed MRT replay invalidates transmission depth");
                    check(IrisTerrainTransmission.colorTexture() == 0,
                            "Failed MRT replay invalidates transmission color0");
                }
                checkCallerState(source, opaque);
                check(Arrays.equals(originalDepth, read(source, GL46C.GL_DEPTH_COMPONENT, 1)),
                        "World depth exactly restored after MRT replay");
                check(Arrays.equals(originalColor0, readColor(source, 0, 4)),
                        "World color0 exactly restored after MRT replay");
                check(Arrays.equals(originalColor1, readColor(source, 1, 4)),
                        "World color1 exactly restored after MRT replay");
            }
            float[] captured = texels(IrisTerrainTransmission.depthTexture(), GL46C.GL_DEPTH_COMPONENT, 1);
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, source);
            GL46C.glDrawBuffers(colorAttachments(2));
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glDepthMask(true);
            GL46C.glClearBufferfv(GL46C.GL_DEPTH, 0, new float[]{0});
            check(Arrays.equals(captured,
                            texels(IrisTerrainTransmission.depthTexture(), GL46C.GL_DEPTH_COMPONENT, 1)),
                    "Later MRT shadow writes cannot enter the terrain/glass snapshot");
            IrisTerrainTransmission.beginFrame();
            check(IrisTerrainTransmission.depthTexture() == 0
                            && IrisTerrainTransmission.colorTexture() == 0,
                    "Missing MRT replay must not reuse the previous frame");
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "Dual-color transmission replay GL error");
            System.out.println("PASS glass MRT replay format=" + reportedFormat
                    + ": color0/color1 and depth restored after normal/failure/retry, "
                    + "independent color0+depth capture");
        } finally {
            IrisTerrainTransmission.release();
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glDisable(GL46C.GL_DEPTH_TEST);
            GL46C.glUseProgram(0);
            GL46C.glBindVertexArray(0);
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, oldDraw);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, oldRead);
            GL46C.glDeleteProgram(program);
            GL46C.glDeleteVertexArrays(vao);
            GL46C.glDeleteFramebuffers(source);
            GL46C.glDeleteFramebuffers(opaque);
            GL46C.glDeleteTextures(sourceDepth);
            GL46C.glDeleteTextures(opaqueDepth);
            GL46C.glDeleteTextures(sourceColor0);
            GL46C.glDeleteTextures(sourceColor1);
            GL46C.glClearDepth(1);
            initialState.close();
        }
    }

    private static void runLegacySingleTarget(int sourceFormat) {
        if (!org.lwjgl.opengl.GL.getCapabilities().OpenGL43)
            throw new IllegalStateException("Transmission probe requires OpenGL 4.3");
        var initialState = new ShadowReplayGlState();
        int oldDraw = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldRead = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
        int sourceDepth = texture(GL46C.GL_DEPTH_COMPONENT24, GL46C.GL_DEPTH_COMPONENT);
        int opaqueDepth = texture(GL46C.GL_DEPTH_COMPONENT24, GL46C.GL_DEPTH_COMPONENT);
        int sourceColor = texture(sourceFormat, GL46C.GL_RGBA);
        int reportedFormat = GL46C.glGetTexLevelParameteri(
                GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_INTERNAL_FORMAT);
        if (sourceFormat == GL46C.GL_RGBA)
            check(reportedFormat == GL46C.GL_RGBA || reportedFormat == GL46C.GL_RGBA8,
                    "Unsized RGBA must resolve to verified 8-bit storage");
        System.out.println("Glass replay source format: requested="
                + sourceFormat + ", reported=" + reportedFormat);
        int source = framebuffer(sourceDepth, sourceColor);
        int opaque = framebuffer(opaqueDepth);
        int vao = GL46C.glGenVertexArrays();
        int program = ShaderCompiler.compileRenderProgram("""
                #version 330 core
                void main() {
                    vec2 p=vec2((gl_VertexID << 1)&2,gl_VertexID&2);
                    gl_Position=vec4(p*2.0-1.0,-0.4,1.0);
                }
                """, """
                #version 330 core
                layout(location=0) out vec4 Color;
                void main() {
                    if (gl_FragCoord.x > 12.0) discard;
                    Color=vec4(0.8,0.2,0.1,1.0);
                }
                """, "terrain-only glass replay");
        check(program != 0, "Glass caster compile");
        try {
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, source);
            GL46C.glViewport(0, 0, 16, 16);
            GL46C.glDepthMask(true);
            GL46C.glColorMask(true, true, true, true);
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glClearDepth(.8);
            GL46C.glClearColor(.12f, .24f, .36f, .48f);
            GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
            GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
            GL46C.glScissor(0, 0, 8, 16);
            GL46C.glClearDepth(.1);
            GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, source);
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, opaque);
            GL46C.glBlitFramebuffer(0, 0, 16, 16, 0, 0, 16, 16,
                    GL46C.GL_DEPTH_BUFFER_BIT, GL46C.GL_NEAREST);
            float[] originalDepth = read(source, GL46C.GL_DEPTH_COMPONENT, 1);
            float[] originalColor = read(source, GL46C.GL_RGBA, 4);
            Runnable draw = () -> {
                GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, source);
                GL46C.glUseProgram(program);
                GL46C.glBindVertexArray(vao);
                GL46C.glEnable(GL46C.GL_DEPTH_TEST);
                GL46C.glDepthFunc(GL46C.GL_LESS);
                GL46C.glDepthMask(true);
                GL46C.glDisable(GL46C.GL_BLEND);
                GL46C.glColorMask(true, true, true, true);
                GL46C.glDisable(GL46C.GL_CULL_FACE);
                GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
                GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
            };
            for (boolean fail : new boolean[]{false, true, false}) {
                GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, opaque);
                GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, source);
                GL46C.glViewport(1, 2, 11, 12);
                GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
                GL46C.glScissor(2, 3, 1, 1);
                GL46C.glEnablei(GL46C.GL_BLEND, 1);
                GL46C.glBlendFuncSeparatei(1, GL46C.GL_ONE, GL46C.GL_ZERO, GL46C.GL_ZERO, GL46C.GL_ONE);
                GL46C.glColorMaski(1, false, true, false, true);
                try {
                    check(IrisTerrainTransmission.capture(
                            source, new int[]{sourceColor}, new int[]{sourceFormat}, opaque,
                            16, GL46C.GL_DEPTH_COMPONENT24, () -> {
                                draw.run();
                                if (fail) throw new IllegalStateException("deliberate replay failure");
                            }), "Glass capture");
                    check(!fail, "Exceptional replay must propagate");
                    float[] depth = texels(IrisTerrainTransmission.depthTexture(),
                            GL46C.GL_DEPTH_COMPONENT, 1);
                    close(depth[8 * 16 + 3], .1f, "Opaque terrain takes precedence");
                    close(depth[8 * 16 + 10], .3f, "Glass depth captured");
                    close(depth[8 * 16 + 15], .8f, "Alpha hole retains terrain depth");
                    float[] color = texels(IrisTerrainTransmission.colorTexture(), GL46C.GL_RGBA, 4);
                    close(color[(8 * 16 + 10) * 4], .8f, "Glass transmission color captured");
                } catch (IllegalStateException expected) {
                    check(fail && expected.getMessage().equals("deliberate replay failure"),
                            "Expected replay failure");
                    check(IrisTerrainTransmission.depthTexture() == 0,
                            "Failed replay invalidates transmission");
                }
                check(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == opaque,
                        "Replay draw FBO restored");
                int[] viewport = new int[4];
                GL46C.glGetIntegerv(GL46C.GL_VIEWPORT, viewport);
                check(Arrays.equals(viewport, new int[]{1, 2, 11, 12}), "Replay viewport restored");
                check(GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST), "Replay scissor restored");
                check(GL46C.glIsEnabledi(GL46C.GL_BLEND, 1), "Other MRT blend states restored");
                var mask = org.lwjgl.BufferUtils.createByteBuffer(4);
                GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, 1, mask);
                check(mask.get(0) == 0 && mask.get(1) != 0 && mask.get(2) == 0 && mask.get(3) != 0,
                        "Other MRT write masks restored");
                check(Arrays.equals(originalDepth, read(source, GL46C.GL_DEPTH_COMPONENT, 1)),
                        "World depth exactly restored");
                check(Arrays.equals(originalColor, read(source, GL46C.GL_RGBA, 4)),
                        "World color exactly restored");
            }
            float[] captured = texels(IrisTerrainTransmission.depthTexture(),
                    GL46C.GL_DEPTH_COMPONENT, 1);
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, source);
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glDepthMask(true);
            GL46C.glClearDepth(0);
            GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
            check(Arrays.equals(captured,
                            texels(IrisTerrainTransmission.depthTexture(), GL46C.GL_DEPTH_COMPONENT, 1)),
                    "Later character shadow writes cannot enter the terrain/glass snapshot");
            IrisTerrainTransmission.beginFrame();
            check(IrisTerrainTransmission.depthTexture() == 0
                            && IrisTerrainTransmission.colorTexture() == 0,
                    "Missing replay must not reuse the previous frame");
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "Transmission replay GL error");
            System.out.println("PASS legacy single-target glass replay format=" + reportedFormat
                    + ": exact depth/color restoration and exceptional exit");
        } finally {
            IrisTerrainTransmission.release();
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glDisable(GL46C.GL_DEPTH_TEST);
            GL46C.glUseProgram(0);
            GL46C.glBindVertexArray(0);
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, oldDraw);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, oldRead);
            GL46C.glDeleteProgram(program);
            GL46C.glDeleteVertexArrays(vao);
            GL46C.glDeleteFramebuffers(source);
            GL46C.glDeleteFramebuffers(opaque);
            GL46C.glDeleteTextures(sourceDepth);
            GL46C.glDeleteTextures(opaqueDepth);
            GL46C.glDeleteTextures(sourceColor);
            GL46C.glClearDepth(1);
            initialState.close();
        }
    }

    private static int texture(int format, int components) {
        int id = GL46C.glGenTextures();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, format,
                16, 16, 0, components, GL46C.GL_FLOAT, 0L);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        return id;
    }

    private static void prepareCallerState(int source, int opaque) {
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, opaque);
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, source);
        GL46C.glViewport(1, 2, 11, 12);
        GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
        GL46C.glScissor(2, 3, 1, 1);
        GL46C.glEnablei(GL46C.GL_BLEND, 1);
        GL46C.glBlendFuncSeparatei(1, GL46C.GL_ONE, GL46C.GL_ZERO, GL46C.GL_ZERO, GL46C.GL_ONE);
        GL46C.glColorMaski(1, false, true, false, true);
    }

    private static void checkCallerState(int source, int opaque) {
        check(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == opaque,
                "MRT replay draw FBO restored");
        int[] viewport = new int[4];
        GL46C.glGetIntegerv(GL46C.GL_VIEWPORT, viewport);
        check(Arrays.equals(viewport, new int[]{1, 2, 11, 12}), "MRT replay viewport restored");
        check(GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST), "MRT replay scissor restored");
        check(GL46C.glIsEnabledi(GL46C.GL_BLEND, 1), "Other MRT blend states restored");
        var mask = org.lwjgl.BufferUtils.createByteBuffer(4);
        GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, 1, mask);
        check(mask.get(0) == 0 && mask.get(1) != 0 && mask.get(2) == 0 && mask.get(3) != 0,
                "Other MRT write masks restored");
    }

    private static void checkDualTransmission() {
        float[] depth = texels(IrisTerrainTransmission.depthTexture(), GL46C.GL_DEPTH_COMPONENT, 1);
        float[] color = texels(IrisTerrainTransmission.colorTexture(), GL46C.GL_RGBA, 4);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int pixel = y * 16 + x;
                float expectedDepth = x < 8 ? .1f : x < 12 ? .3f : .8f;
                float expectedColor = x >= 8 && x < 12 ? .8f : .12f;
                close(depth[pixel], expectedDepth, "Dual replay depth pixel " + x + "," + y);
                close(color[pixel * 4], expectedColor, "Dual replay color0 pixel " + x + "," + y);
            }
        }
    }

    private static int[] colorAttachments(int count) {
        int[] attachments = new int[count];
        for (int i = 0; i < count; i++) attachments[i] = GL46C.GL_COLOR_ATTACHMENT0 + i;
        return attachments;
    }

    private static int framebuffer(int depth, int... colors) {
        int id = GL46C.glGenFramebuffers();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, id);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                GL46C.GL_TEXTURE_2D, depth, 0);
        for (int i = 0; i < colors.length; i++) {
            GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0 + i,
                    GL46C.GL_TEXTURE_2D, colors[i], 0);
        }
        if (colors.length == 0) GL46C.glDrawBuffer(GL46C.GL_NONE);
        else GL46C.glDrawBuffers(colorAttachments(colors.length));
        GL46C.glReadBuffer(colors.length == 0 ? GL46C.GL_NONE : GL46C.GL_COLOR_ATTACHMENT0);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE,
                "Glass fixture FBO");
        return id;
    }

    private static float[] read(int fbo, int components, int channels) {
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, fbo);
        float[] result = new float[16 * 16 * channels];
        GL46C.glReadPixels(0, 0, 16, 16, components, GL46C.GL_FLOAT, result);
        return result;
    }

    private static float[] readColor(int fbo, int attachment, int channels) {
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, fbo);
        GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0 + attachment);
        float[] result = new float[16 * 16 * channels];
        GL46C.glReadPixels(0, 0, 16, 16, GL46C.GL_RGBA, GL46C.GL_FLOAT, result);
        return result;
    }

    private static float[] texels(int texture, int components, int channels) {
        int old = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
        float[] result = new float[16 * 16 * channels];
        GL46C.glGetTexImage(GL46C.GL_TEXTURE_2D, 0, components, GL46C.GL_FLOAT, result);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, old);
        return result;
    }

    private static void close(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < .004f, message);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
