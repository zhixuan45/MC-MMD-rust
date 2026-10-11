package com.shiroha.mmdskin.render.outline;

import org.lwjgl.opengl.GL46C;

/** 原始 GL 改动成对恢复，不改 Blaze3D 的缓存。 */
final class OutlineGlState implements AutoCloseable {
    final int drawFbo = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
    final int readFbo = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
    final int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
    final int vao = GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING);
    final int ebo = GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
    final int arrayBuffer = GL46C.glGetInteger(GL46C.GL_ARRAY_BUFFER_BINDING);
    final int textureUnit = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
    final int renderbuffer = GL46C.glGetInteger(GL46C.GL_RENDERBUFFER_BINDING);
    final int[] viewport = new int[4], scissorBox = new int[4];
    final int[] drawBuffers;
    final int depthFunc = GL46C.glGetInteger(GL46C.GL_DEPTH_FUNC);
    final boolean depthMask = GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK);
    final boolean depthTest = GL46C.glIsEnabled(GL46C.GL_DEPTH_TEST);
    final boolean cull = GL46C.glIsEnabled(GL46C.GL_CULL_FACE);
    final int cullFace = GL46C.glGetInteger(GL46C.GL_CULL_FACE_MODE);
    final boolean scissor = GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST);
    final boolean indexedBlend = org.lwjgl.opengl.GL.getCapabilities().OpenGL40;
    final boolean blend = GL46C.glIsEnabledi(GL46C.GL_BLEND, 0);
    final int srcRgb = blendParameter(GL46C.GL_BLEND_SRC_RGB);
    final int dstRgb = blendParameter(GL46C.GL_BLEND_DST_RGB);
    final int srcAlpha = blendParameter(GL46C.GL_BLEND_SRC_ALPHA);
    final int dstAlpha = blendParameter(GL46C.GL_BLEND_DST_ALPHA);
    final int eqRgb = blendParameter(GL46C.GL_BLEND_EQUATION_RGB);
    final int eqAlpha = blendParameter(GL46C.GL_BLEND_EQUATION_ALPHA);
    final java.nio.ByteBuffer colorMask = org.lwjgl.BufferUtils.createByteBuffer(4);
    final int texture0, sampler0;

    OutlineGlState() {
        GL46C.glGetIntegerv(GL46C.GL_VIEWPORT, viewport);
        GL46C.glGetIntegerv(GL46C.GL_SCISSOR_BOX, scissorBox);
        GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, 0, colorMask);
        drawBuffers = new int[drawFbo == 0 ? 1 : GL46C.glGetInteger(GL46C.GL_MAX_DRAW_BUFFERS)];
        for (int i = 0; i < drawBuffers.length; i++) drawBuffers[i] = GL46C.glGetInteger(GL46C.GL_DRAW_BUFFER0 + i);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        texture0 = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        sampler0 = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, 0);
    }

    private int blendParameter(int parameter) {
        return indexedBlend ? GL46C.glGetIntegeri(parameter, 0) : GL46C.glGetInteger(parameter);
    }

    void restoreTarget() {
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL46C.glDrawBuffers(drawBuffers);
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, readFbo);
        GL46C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL46C.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        enabled(GL46C.GL_SCISSOR_TEST, scissor);
    }

    static void enabled(int capability, boolean value) {
        if (value) GL46C.glEnable(capability); else GL46C.glDisable(capability);
    }

    @Override public void close() {
        restoreTarget();
        GL46C.glUseProgram(program);
        GL46C.glBindVertexArray(vao);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, arrayBuffer);
        GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, renderbuffer);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture0);
        GL46C.glBindSampler(0, sampler0);
        GL46C.glActiveTexture(textureUnit);
        GL46C.glDepthFunc(depthFunc);
        GL46C.glDepthMask(depthMask);
        enabled(GL46C.GL_DEPTH_TEST, depthTest);
        enabled(GL46C.GL_CULL_FACE, cull);
        GL46C.glCullFace(cullFace);
        if (blend) GL46C.glEnablei(GL46C.GL_BLEND, 0); else GL46C.glDisablei(GL46C.GL_BLEND, 0);
        if (indexedBlend) {
            GL46C.glBlendFuncSeparatei(0, srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL46C.glBlendEquationSeparatei(0, eqRgb, eqAlpha);
        } else {
            GL46C.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL46C.glBlendEquationSeparate(eqRgb, eqAlpha);
        }
        GL46C.glColorMaski(0, colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
    }
}
