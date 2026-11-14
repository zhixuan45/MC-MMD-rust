package com.shiroha.mmdskin.render.outline;

import com.shiroha.mmdskin.render.material.ProgramSamplerLayout;
import com.shiroha.mmdskin.render.shader.ToonShaderBase;
import org.lwjgl.opengl.GL46C;

import java.nio.ByteBuffer;

/** 可见材质在屏幕上的投影，供外扩片元判断是否位于模型内部。 */
final class OutlineBodyMask implements AutoCloseable {
    private static int framebuffer, texture, width, height;
    private final int[] viewport;
    private final int unit, previousTexture, previousSampler;

    private OutlineBodyMask(int[] viewport, int unit) {
        this.viewport = viewport;
        this.unit = unit;
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
        previousTexture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        previousSampler = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, unit);
        GL46C.glActiveTexture(active);
    }

    static OutlineBodyMask open(ToonShaderBase shader) {
        int location = shader.getOutlineBodyMaskLocation();
        if (location < 0 || GL46C.glIsEnabled(GL46C.GL_COLOR_LOGIC_OP)) return null;
        int[] viewport = new int[4];
        GL46C.glGetIntegerv(GL46C.GL_VIEWPORT, viewport);
        int maxSize = GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_SIZE);
        if (viewport[2] <= 0 || viewport[3] <= 0 || viewport[2] > maxSize || viewport[3] > maxSize
                || (long) viewport[2] * viewport[3] > 64L * 1024 * 1024) return null;
        int[] units = ProgramSamplerLayout.effectUnits(shader.getOutlineProgram(), new int[]{location}, 1);
        return units.length == 0 ? null : new OutlineBodyMask(viewport, units[0]);
    }

    boolean capture(ToonShaderBase shader, Runnable drawBody) {
        int drawFbo = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST);
        boolean depth = GL46C.glIsEnabled(GL46C.GL_DEPTH_TEST);
        boolean cull = GL46C.glIsEnabled(GL46C.GL_CULL_FACE);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, 0, mask);
            try {
                if (!bindTarget()) return false;
                GL46C.glViewport(0, 0, width, height);
                GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
                GL46C.glDisable(GL46C.GL_DEPTH_TEST);
                GL46C.glDisable(GL46C.GL_CULL_FACE);
                GL46C.glColorMaski(0, true, true, true, true);
                GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, new float[4]);
                shader.setOutlineBodyMask(unit, viewport[0], viewport[1]);
                drawBody.run();
                bindTexture(texture, 0);
                return true;
            } finally {
                GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, drawFbo);
                GL46C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                OutlineGlState.enabled(GL46C.GL_SCISSOR_TEST, scissor);
                OutlineGlState.enabled(GL46C.GL_DEPTH_TEST, depth);
                OutlineGlState.enabled(GL46C.GL_CULL_FACE, cull);
                GL46C.glColorMaski(0, mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
            }
        }
    }

    private boolean bindTarget() {
        if (framebuffer == 0) {
            framebuffer = GL46C.glGenFramebuffers();
            texture = GL46C.glGenTextures();
        }
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, framebuffer);
        if (width != viewport[2] || height != viewport[3]) {
            int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
            try {
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
                GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_R8, viewport[2], viewport[3], 0,
                        GL46C.GL_RED, GL46C.GL_UNSIGNED_BYTE, 0L);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
            } finally {
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, 0);
                GL46C.glActiveTexture(active);
            }
            GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0,
                    GL46C.GL_TEXTURE_2D, texture, 0);
            GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
            if (GL46C.glCheckFramebufferStatus(GL46C.GL_DRAW_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE) {
                release();
                return false;
            }
            width = viewport[2];
            height = viewport[3];
        }
        bindTexture(0, 0);
        return true;
    }

    private void bindTexture(int id, int sampler) {
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
        GL46C.glBindSampler(unit, sampler);
        GL46C.glActiveTexture(active);
    }

    @Override public void close() {
        bindTexture(previousTexture, previousSampler);
    }

    static void release() {
        if (framebuffer != 0) GL46C.glDeleteFramebuffers(framebuffer);
        if (texture != 0) GL46C.glDeleteTextures(texture);
        framebuffer = texture = width = height = 0;
    }
}
