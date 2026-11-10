package com.shiroha.mmdskin.compat.iris;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL46C;

import java.nio.ByteBuffer;

/** Unified GL and Blaze3D state touched by terrain shadow copies and the native replay draw. */
final class ShadowReplayGlState implements AutoCloseable {
    private enum Mode {
        DEPTH_COPY,
        FULL
    }

    final int draw;
    final int read;
    final int active;
    final int activeTexture;
    final int unpack;
    final boolean scissor;
    final int[] scissorBox = new int[4];

    final int program;
    final int vao;
    final int ebo;
    final int array;
    final int depthFunc;
    final int cullFace;
    final boolean depthMask;
    final boolean depthTest;
    final boolean cull;
    final boolean blend;
    final int[] viewport;
    final int[] textures;
    final int[] samplers;
    final int srcRgb;
    final int dstRgb;
    final int srcAlpha;
    final int dstAlpha;
    final int eqRgb;
    final int eqAlpha;
    final ByteBuffer colorMask;
    private final BlendTarget[] targets;

    private final Mode mode;
    private final boolean restoreEngineCaches;
    private final net.minecraft.client.renderer.ShaderInstance shader;
    private final int[] shaderTextures;

    private record BlendTarget(boolean enabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha,
                               int eqRgb, int eqAlpha, ByteBuffer mask) {}

    ShadowReplayGlState() {
        this(Mode.FULL, false);
    }

    ShadowReplayGlState(boolean restoreEngineCaches) {
        this(Mode.FULL, restoreEngineCaches);
    }

    private ShadowReplayGlState(Mode mode, boolean restoreEngineCaches) {
        this.mode = mode;
        this.restoreEngineCaches = restoreEngineCaches && mode == Mode.FULL;

        draw = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        read = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
        active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        unpack = GL46C.glGetInteger(GL46C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        scissor = GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST);
        GL46C.glGetIntegerv(GL46C.GL_SCISSOR_BOX, scissorBox);
        activeTexture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);

        if (mode == Mode.DEPTH_COPY) {
            program = vao = ebo = array = depthFunc = cullFace = 0;
            depthMask = depthTest = cull = blend = false;
            viewport = textures = samplers = null;
            srcRgb = dstRgb = srcAlpha = dstAlpha = eqRgb = eqAlpha = 0;
            colorMask = null;
            targets = null;
            shader = null;
            shaderTextures = null;
            return;
        }

        program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        vao = GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING);
        ebo = GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        array = GL46C.glGetInteger(GL46C.GL_ARRAY_BUFFER_BINDING);
        depthFunc = GL46C.glGetInteger(GL46C.GL_DEPTH_FUNC);
        cullFace = GL46C.glGetInteger(GL46C.GL_CULL_FACE_MODE);
        depthMask = GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK);
        depthTest = GL46C.glIsEnabled(GL46C.GL_DEPTH_TEST);
        cull = GL46C.glIsEnabled(GL46C.GL_CULL_FACE);
        blend = GL46C.glIsEnabledi(GL46C.GL_BLEND, 0);
        viewport = new int[4];
        GL46C.glGetIntegerv(GL46C.GL_VIEWPORT, viewport);
        textures = new int[GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_IMAGE_UNITS)];
        samplers = new int[textures.length];
        srcRgb = GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_RGB, 0);
        dstRgb = GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_RGB, 0);
        srcAlpha = GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_ALPHA, 0);
        dstAlpha = GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_ALPHA, 0);
        eqRgb = GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_RGB, 0);
        eqAlpha = GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_ALPHA, 0);
        colorMask = BufferUtils.createByteBuffer(4);
        GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, 0, colorMask);
        targets = new BlendTarget[GL46C.glGetInteger(GL46C.GL_MAX_DRAW_BUFFERS)];
        for (int i = 0; i < targets.length; i++) {
            ByteBuffer mask = BufferUtils.createByteBuffer(4);
            GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, i, mask);
            targets[i] = new BlendTarget(GL46C.glIsEnabledi(GL46C.GL_BLEND, i),
                    GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_RGB, i),
                    GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_RGB, i),
                    GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_ALPHA, i),
                    GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_ALPHA, i),
                    GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_RGB, i),
                    GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_ALPHA, i), mask);
        }
        for (int i = 0; i < textures.length; i++) {
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + i);
            textures[i] = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
            samplers[i] = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, i);
        }
        GL46C.glActiveTexture(active);
        if (restoreEngineCaches) {
            shader = RenderSystem.getShader();
            shaderTextures = new int[12];
            for (int i = 0; i < shaderTextures.length; i++) {
                shaderTextures[i] = RenderSystem.getShaderTexture(i);
            }
        } else {
            shader = null;
            shaderTextures = null;
        }
    }

    static ShadowReplayGlState depthCopy() {
        return new ShadowReplayGlState(Mode.DEPTH_COPY, false);
    }

    @Override
    public void close() {
        try {
            if (restoreEngineCaches) {
                var current = RenderSystem.getShader();
                if (current != null) current.clear();
            }
        } finally {
            restore();
            if (restoreEngineCaches) restoreEngineCaches();
        }
    }

    void restore() {
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, draw);
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, read);
        GL46C.glActiveTexture(active);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, activeTexture);
        GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, unpack);
        GL46C.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        enabled(GL46C.GL_SCISSOR_TEST, scissor);
        if (mode == Mode.DEPTH_COPY) return;

        GL46C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
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
        GL46C.glCullFace(cullFace);
        restoreBlendTargets();
    }

    private void restoreEngineCaches() {
        RenderSystem.setShader(() -> shader);
        for (int i = 0; i < shaderTextures.length; i++) {
            RenderSystem.setShaderTexture(i, shaderTextures[i]);
        }
        for (int i = 0; i < Math.min(shaderTextures.length, textures.length); i++) {
            GlStateManager._activeTexture(GL46C.GL_TEXTURE0 + i);
            GlStateManager._bindTexture(textures[i]);
        }
        GlStateManager._activeTexture(active);
        if (depthTest) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
        GlStateManager._depthFunc(depthFunc);
        GlStateManager._depthMask(depthMask);
        if (cull) GlStateManager._enableCull(); else GlStateManager._disableCull();
        if (blend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
        GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GlStateManager._blendEquation(eqRgb);
        GlStateManager._colorMask(colorMask.get(0) != 0, colorMask.get(1) != 0,
                colorMask.get(2) != 0, colorMask.get(3) != 0);
        // Preserve a distinct alpha equation after synchronizing the engine's RGB cache.
        restoreBlendTargets();
        BufferUploader.invalidate();
    }

    void restoreBlendTargets() {
        for (int i = 0; i < targets.length; i++) {
            BlendTarget target = targets[i];
            if (target.enabled) GL46C.glEnablei(GL46C.GL_BLEND, i);
            else GL46C.glDisablei(GL46C.GL_BLEND, i);
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
