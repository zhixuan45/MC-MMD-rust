package com.shiroha.mmdskin.render.pipeline.toon;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

/** Covers the shared CPU/GPU draw helper and composition; also resynchronizes Blaze3D's caches. */
final class ToonGlState implements AutoCloseable {
    final int draw = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
    final int read = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
    final int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
    final int vao = GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING);
    final int array = GL46C.glGetInteger(GL46C.GL_ARRAY_BUFFER_BINDING);
    final int element = GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
    final int renderbuffer = GL46C.glGetInteger(GL46C.GL_RENDERBUFFER_BINDING);
    final int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
    final int depthFunc = GL46C.glGetInteger(GL46C.GL_DEPTH_FUNC);
    final int cullFace = GL46C.glGetInteger(GL46C.GL_CULL_FACE_MODE);
    final boolean depth = GL46C.glIsEnabled(GL46C.GL_DEPTH_TEST);
    final boolean depthMask = GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK);
    final boolean cull = GL46C.glIsEnabled(GL46C.GL_CULL_FACE);
    final boolean scissor = GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST);
    final boolean srgb = GL46C.glIsEnabled(GL46C.GL_FRAMEBUFFER_SRGB);
    final int[] viewport = new int[4], scissorBox = new int[4];
    final int[] drawBuffers = drawBuffers();
    final Blend[] blends = new Blend[GL46C.glGetInteger(GL46C.GL_MAX_DRAW_BUFFERS)];
    final int texture, sampler, shaderTexture = RenderSystem.getShaderTexture(0);
    final float[] shaderColor = RenderSystem.getShaderColor().clone();
    // EyeEmissionBuffer.unbind changes this context-wide constant, outside VAO storage.
    final float[] eyeConstant = new float[4];
    final int[] integerEyeConstant = new int[4];
    final boolean integerEye = vao != 0 && GL46C.glGetVertexAttribi(3, GL46C.GL_VERTEX_ATTRIB_ARRAY_INTEGER) != 0;

    ToonGlState() {
        GL46C.glGetIntegerv(GL46C.GL_VIEWPORT, viewport);
        GL46C.glGetIntegerv(GL46C.GL_SCISSOR_BOX, scissorBox);
        for (int i = 0; i < blends.length; i++) blends[i] = new Blend(i);
        if (integerEye) GL46C.glGetVertexAttribIiv(3, GL46C.GL_CURRENT_VERTEX_ATTRIB, integerEyeConstant);
        else GL46C.glGetVertexAttribfv(3, GL46C.GL_CURRENT_VERTEX_ATTRIB, eyeConstant);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        texture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        sampler = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, 0);
        GL46C.glActiveTexture(active);
    }

    static int[] drawBuffers() {
        int count = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == 0
                ? 1 : GL46C.glGetInteger(GL46C.GL_MAX_DRAW_BUFFERS);
        int[] buffers = new int[count];
        for (int i = 0; i < count; i++) buffers[i] = GL46C.glGetInteger(GL46C.GL_DRAW_BUFFER0 + i);
        return buffers;
    }

    private static final class Blend {
        final int slot, srcRgb, dstRgb, srcAlpha, dstAlpha, eqRgb, eqAlpha;
        final boolean enabled;
        final boolean[] mask = new boolean[4];
        Blend(int slot) {
            this.slot = slot;
            enabled = GL46C.glIsEnabledi(GL46C.GL_BLEND, slot);
            srcRgb = GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_RGB, slot);
            dstRgb = GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_RGB, slot);
            srcAlpha = GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_ALPHA, slot);
            dstAlpha = GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_ALPHA, slot);
            eqRgb = GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_RGB, slot);
            eqAlpha = GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_ALPHA, slot);
            try (var stack = MemoryStack.stackPush()) {
                var bytes = stack.malloc(4);
                GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, slot, bytes);
                for (int j = 0; j < 4; j++) mask[j] = bytes.get(j) != 0;
            }
        }
        void restore() {
            if (enabled) GL46C.glEnablei(GL46C.GL_BLEND, slot); else GL46C.glDisablei(GL46C.GL_BLEND, slot);
            GL46C.glBlendFuncSeparatei(slot, srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL46C.glBlendEquationSeparatei(slot, eqRgb, eqAlpha);
            GL46C.glColorMaski(slot, mask[0], mask[1], mask[2], mask[3]);
        }
    }

    @Override public void close() {
        GlStateManager._glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, draw);
        GL46C.glDrawBuffers(drawBuffers);
        GlStateManager._glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, read);
        GL46C.glBindVertexArray(vao);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, array);
        if (vao != 0) GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, element);
        if (integerEye) GL46C.glVertexAttribI4iv(3, integerEyeConstant);
        else GL46C.glVertexAttrib4fv(3, eyeConstant);
        GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, renderbuffer);
        RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
        RenderSystem.bindTexture(texture);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
        GL46C.glBindSampler(0, sampler);
        RenderSystem.activeTexture(active);
        GL46C.glActiveTexture(active);
        RenderSystem.setShaderTexture(0, shaderTexture);
        RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
        RenderSystem.depthMask(depthMask);
        RenderSystem.depthFunc(depthFunc);
        if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        // Raw GL helpers (coverage/composite) intentionally bypass Blaze3D caches.
        // A cached setter may be a no-op, so restore actual GL after synchronizing the cache.
        GL46C.glDepthMask(depthMask);
        GL46C.glDepthFunc(depthFunc);
        if (depth) GL46C.glEnable(GL46C.GL_DEPTH_TEST); else GL46C.glDisable(GL46C.GL_DEPTH_TEST);
        if (cull) GL46C.glEnable(GL46C.GL_CULL_FACE); else GL46C.glDisable(GL46C.GL_CULL_FACE);
        GL46C.glCullFace(cullFace);
        RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL46C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL46C.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        if (scissor) GL46C.glEnable(GL46C.GL_SCISSOR_TEST); else GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        if (srgb) GL46C.glEnable(GL46C.GL_FRAMEBUFFER_SRGB); else GL46C.glDisable(GL46C.GL_FRAMEBUFFER_SRGB);
        var first = blends[0];
        if (first.enabled) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        RenderSystem.blendFuncSeparate(first.srcRgb, first.dstRgb, first.srcAlpha, first.dstAlpha);
        RenderSystem.blendEquation(first.eqRgb);
        RenderSystem.colorMask(first.mask[0], first.mask[1], first.mask[2], first.mask[3]);
        for (var blend : blends) blend.restore();
        GlStateManager._glUseProgram(program);
        GL46C.glUseProgram(program);
    }
}
