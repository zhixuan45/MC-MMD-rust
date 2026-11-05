package com.shiroha.mmdskin.compat.iris;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL46C;
import java.nio.ByteBuffer;

/** State touched by a native single-output terrain shadow draw and our texture copies. */
final class ShadowReplayGlState implements AutoCloseable {
    final int draw = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
    final int read = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
    final int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
    final int vao = GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING);
    final int ebo = GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
    final int array = GL46C.glGetInteger(GL46C.GL_ARRAY_BUFFER_BINDING);
    final int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
    final int depthFunc = GL46C.glGetInteger(GL46C.GL_DEPTH_FUNC);
    final int cullFace = GL46C.glGetInteger(GL46C.GL_CULL_FACE_MODE);
    final boolean depthMask = GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK);
    final boolean depthTest = GL46C.glIsEnabled(GL46C.GL_DEPTH_TEST);
    final boolean cull = GL46C.glIsEnabled(GL46C.GL_CULL_FACE);
    final boolean scissor = GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST);
    final boolean blend = GL46C.glIsEnabledi(GL46C.GL_BLEND, 0);
    final int[] viewport = new int[4], scissorBox = new int[4];
    final int[] textures = new int[GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_IMAGE_UNITS)];
    final int[] samplers = new int[textures.length];
    final int srcRgb = GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_RGB, 0);
    final int dstRgb = GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_RGB, 0);
    final int srcAlpha = GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_ALPHA, 0);
    final int dstAlpha = GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_ALPHA, 0);
    final int eqRgb = GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_RGB, 0);
    final int eqAlpha = GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_ALPHA, 0);
    final ByteBuffer colorMask = BufferUtils.createByteBuffer(4);
    private final BlendTarget[] targets = new BlendTarget[GL46C.glGetInteger(GL46C.GL_MAX_DRAW_BUFFERS)];

    private record BlendTarget(boolean enabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha,
                               int eqRgb, int eqAlpha, ByteBuffer mask) {}

    ShadowReplayGlState() {
        GL46C.glGetIntegerv(GL46C.GL_VIEWPORT, viewport);
        GL46C.glGetIntegerv(GL46C.GL_SCISSOR_BOX, scissorBox);
        GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, 0, colorMask);
        for (int i = 0; i < targets.length; i++) {
            ByteBuffer mask = BufferUtils.createByteBuffer(4);
            GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, i, mask);
            targets[i] = new BlendTarget(GL46C.glIsEnabledi(GL46C.GL_BLEND, i),
                    GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_RGB, i), GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_RGB, i),
                    GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_ALPHA, i), GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_ALPHA, i),
                    GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_RGB, i), GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_ALPHA, i), mask);
        }
        for (int i = 0; i < textures.length; i++) {
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + i);
            textures[i] = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
            samplers[i] = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, i);
        }
        GL46C.glActiveTexture(active);
    }

    @Override public void close() {
        restore();
    }

    void restore() {
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, draw);
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, read);
        GL46C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL46C.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        GL46C.glUseProgram(program);
        GL46C.glBindVertexArray(vao);
        if (vao != 0) GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, array);
        for (int i = 0; i < textures.length; i++) {
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + i);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, textures[i]);
            GL46C.glBindSampler(i, samplers[i]);
        }
        GL46C.glActiveTexture(active);
        GL46C.glDepthFunc(depthFunc);
        GL46C.glDepthMask(depthMask);
        enabled(GL46C.GL_DEPTH_TEST, depthTest);
        enabled(GL46C.GL_CULL_FACE, cull);
        enabled(GL46C.GL_SCISSOR_TEST, scissor);
        GL46C.glCullFace(cullFace);
        restoreBlendTargets();
    }

    void restoreBlendTargets() {
        for (int i = 0; i < targets.length; i++) {
            BlendTarget target = targets[i];
            if (target.enabled) GL46C.glEnablei(GL46C.GL_BLEND, i); else GL46C.glDisablei(GL46C.GL_BLEND, i);
            GL46C.glBlendFuncSeparatei(i, target.srcRgb, target.dstRgb, target.srcAlpha, target.dstAlpha);
            GL46C.glBlendEquationSeparatei(i, target.eqRgb, target.eqAlpha);
            GL46C.glColorMaski(i, target.mask.get(0) != 0, target.mask.get(1) != 0,
                    target.mask.get(2) != 0, target.mask.get(3) != 0);
        }
    }

    private static void enabled(int capability, boolean enabled) {
        if (enabled) GL46C.glEnable(capability); else GL46C.glDisable(capability);
    }
}
