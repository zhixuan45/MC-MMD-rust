package com.shiroha.mmdskin.compat.iris;

import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import org.lwjgl.opengl.GL46C;
import java.util.Arrays;

/** Real rasterization and exact host-attachment restoration, including an exceptional replay. */
public final class TransmissionGlProbe {
    public static void checkProtocol(String fragment, String vertex, boolean expected) {
        check(IrisTerrainShadowReplay.supports(fragment, vertex) == expected, "Real BSL shadow replay protocol");
    }

    public static void run() {
        if (!org.lwjgl.opengl.GL.getCapabilities().OpenGL43)
            throw new IllegalStateException("Transmission probe requires OpenGL 4.3");
        var initialState = new ShadowReplayGlState();
        int oldDraw = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldRead = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
        int sourceDepth = texture(GL46C.GL_DEPTH_COMPONENT24, GL46C.GL_DEPTH_COMPONENT);
        int opaqueDepth = texture(GL46C.GL_DEPTH_COMPONENT24, GL46C.GL_DEPTH_COMPONENT);
        int sourceColor = texture(GL46C.GL_RGBA8, GL46C.GL_RGBA);
        int source = framebuffer(sourceDepth, sourceColor), opaque = framebuffer(opaqueDepth, 0);
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
            GL46C.glBlitFramebuffer(0, 0, 16, 16, 0, 0, 16, 16, GL46C.GL_DEPTH_BUFFER_BIT, GL46C.GL_NEAREST);
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
                    check(IrisTerrainTransmission.capture(source, sourceColor, opaque, 16, GL46C.GL_DEPTH_COMPONENT24, () -> {
                        draw.run();
                        if (fail) throw new IllegalStateException("deliberate replay failure");
                    }), "Glass capture");
                    check(!fail, "Exceptional replay must propagate");
                    float[] depth = texels(IrisTerrainTransmission.depthTexture(), GL46C.GL_DEPTH_COMPONENT, 1);
                    close(depth[8 * 16 + 3], .1f, "Opaque terrain takes precedence");
                    close(depth[8 * 16 + 10], .3f, "Glass depth captured");
                    close(depth[8 * 16 + 15], .8f, "Alpha hole retains terrain depth");
                    float[] color = texels(IrisTerrainTransmission.colorTexture(), GL46C.GL_RGBA, 4);
                    close(color[(8 * 16 + 10) * 4], .8f, "Glass transmission color captured");
                } catch (IllegalStateException expected) {
                    check(fail && expected.getMessage().equals("deliberate replay failure"), "Expected replay failure");
                    check(IrisTerrainTransmission.depthTexture() == 0, "Failed replay invalidates transmission");
                }
                check(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == opaque, "Replay draw FBO restored");
                int[] viewport = new int[4];
                GL46C.glGetIntegerv(GL46C.GL_VIEWPORT, viewport);
                check(Arrays.equals(viewport, new int[]{1, 2, 11, 12}), "Replay viewport restored");
                check(GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST), "Replay scissor restored");
                check(GL46C.glIsEnabledi(GL46C.GL_BLEND, 1), "Other MRT blend states restored");
                var mask = org.lwjgl.BufferUtils.createByteBuffer(4);
                GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, 1, mask);
                check(mask.get(0) == 0 && mask.get(1) != 0 && mask.get(2) == 0 && mask.get(3) != 0,
                        "Other MRT write masks restored");
                check(Arrays.equals(originalDepth, read(source, GL46C.GL_DEPTH_COMPONENT, 1)), "World depth exactly restored");
                check(Arrays.equals(originalColor, read(source, GL46C.GL_RGBA, 4)), "World color exactly restored");
            }
            float[] captured = texels(IrisTerrainTransmission.depthTexture(), GL46C.GL_DEPTH_COMPONENT, 1);
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, source);
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glDepthMask(true);
            GL46C.glClearDepth(0);
            GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
            check(Arrays.equals(captured, texels(IrisTerrainTransmission.depthTexture(), GL46C.GL_DEPTH_COMPONENT, 1)),
                    "Later character shadow writes cannot enter the terrain/glass snapshot");
            IrisTerrainTransmission.beginFrame();
            check(IrisTerrainTransmission.depthTexture() == 0 && IrisTerrainTransmission.colorTexture() == 0,
                    "Missing replay must not reuse the previous frame");
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "Transmission replay GL error");
            System.out.println("PASS glass replay: real caster draw, opaque precedence, alpha hole, exact depth/color restoration, exceptional exit, viewport/scissor, later caster isolation, missing frame");
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
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, format, 16, 16, 0, components, GL46C.GL_FLOAT, 0L);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        return id;
    }

    private static int framebuffer(int depth, int color) {
        int id = GL46C.glGenFramebuffers();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, id);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_TEXTURE_2D, depth, 0);
        if (color != 0) GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, color, 0);
        GL46C.glDrawBuffer(color == 0 ? GL46C.GL_NONE : GL46C.GL_COLOR_ATTACHMENT0);
        GL46C.glReadBuffer(color == 0 ? GL46C.GL_NONE : GL46C.GL_COLOR_ATTACHMENT0);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "Glass fixture FBO");
        return id;
    }

    private static float[] read(int fbo, int components, int channels) {
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, fbo);
        float[] result = new float[16 * 16 * channels];
        GL46C.glReadPixels(0, 0, 16, 16, components, GL46C.GL_FLOAT, result);
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

    private static void close(float a, float b, String message) { check(Math.abs(a - b) < .004f, message); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
