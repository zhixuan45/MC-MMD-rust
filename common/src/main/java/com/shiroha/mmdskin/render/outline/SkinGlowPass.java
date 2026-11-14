package com.shiroha.mmdskin.render.outline;

import com.shiroha.mmdskin.render.gl.DepthAttachment;
import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import com.shiroha.mmdskin.render.shader.ToonShaderBase;
import com.shiroha.mmdskin.util.AssetsUtil;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL46C;

public final class SkinGlowPass {
    private static final String VERTEX = """
            #version 330 core
            out vec2 uv;
            void main() {
                uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final int[] framebuffers = new int[3];
    private static final int[] colors = new int[3];
    private static int depth, width, height, depthFormat;
    private static int blurProgram, compositeProgram, fullscreenVao;
    private static boolean warned;

    private SkinGlowPass() {}

    public static void draw(ToonShaderBase shader, Runnable depthDraw, Runnable emissionDraw) {
        try (OutlineGlState state = new OutlineGlState()) {
            if (!org.lwjgl.opengl.GL.getCapabilities().OpenGL40) return;
            int slot = shader.getOutlineColorSlot();
            DepthAttachment attachment = DepthAttachment.inspect();
            if (slot < 0 || slot >= state.drawBuffers.length || state.drawBuffers[slot] == GL46C.GL_NONE
                    || attachment == null || attachment.format() == 0 || attachment.samples() != 0
                    || !attachment.contains(state.viewport) || GL46C.glIsEnabled(GL46C.GL_STENCIL_TEST)
                    || GL46C.glIsEnabled(GL46C.GL_COLOR_LOGIC_OP)) return;
            int requestedWidth = state.viewport[2], requestedHeight = state.viewport[3];
            if (requestedWidth <= 0 || requestedHeight <= 0
                    || (long) requestedWidth * requestedHeight * 16 > 256L * 1024 * 1024) return;
            var targetMask = org.lwjgl.BufferUtils.createByteBuffer(4);
            GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, slot, targetMask);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE1);
            int texture1 = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
            int sampler1 = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, 1);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE2);
            int texture2 = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
            int sampler2 = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, 2);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
            try {
                initialize();
                allocate(requestedWidth, requestedHeight, attachment.format());
                GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
                GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, state.drawFbo);
                GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, framebuffers[0]);
                int[] viewport = state.viewport;
                if (!DepthAttachment.blit(viewport[0], viewport[1], viewport[0] + width,
                        viewport[1] + height, 0, 0, width, height)) return;
                GL46C.glViewport(0, 0, width, height);
                GL46C.glColorMaski(0, true, true, true, true);
                GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, new float[]{0, 0, 0, 0});
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, state.texture0);
                GL46C.glBindSampler(0, 0);
                GL46C.glDisablei(GL46C.GL_BLEND, 0);
                GL46C.glEnable(GL46C.GL_DEPTH_TEST);
                GL46C.glDepthFunc(GL46C.GL_LEQUAL);
                GL46C.glDepthMask(true);
                GL46C.glColorMaski(0, false, false, false, false);
                shader.setSkinGlowCapture(1);
                depthDraw.run();
                GL46C.glDepthMask(false);
                GL46C.glColorMaski(0, true, true, true, true);
                shader.setSkinGlowCapture(2);
                emissionDraw.run();
                shader.setSkinGlowCapture(0);

                GL46C.glDisable(GL46C.GL_DEPTH_TEST);
                GL46C.glDisable(GL46C.GL_CULL_FACE);
                GL46C.glBindVertexArray(fullscreenVao);
                GL46C.glUseProgram(blurProgram);
                GL46C.glUniform1i(GL46C.glGetUniformLocation(blurProgram, "Source"), 0);
                GL46C.glViewport(0, 0, halfWidth(), halfHeight());
                blur(colors[0], framebuffers[1], 1.0f / halfWidth(), 0);
                blur(colors[1], framebuffers[2], 0, 1.0f / halfHeight());

                state.restoreTarget();
                GL46C.glDrawBuffer(state.drawBuffers[slot]);
                GL46C.glColorMaski(0, targetMask.get(0) != 0, targetMask.get(1) != 0,
                        targetMask.get(2) != 0, false);
                GL46C.glEnablei(GL46C.GL_BLEND, 0);
                GL46C.glBlendEquationSeparatei(0, GL46C.GL_FUNC_ADD, GL46C.GL_FUNC_ADD);
                GL46C.glBlendFuncSeparatei(0, GL46C.GL_ONE, GL46C.GL_ONE, GL46C.GL_ZERO, GL46C.GL_ONE);
                GL46C.glUseProgram(compositeProgram);
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, colors[2]);
                GL46C.glUniform1i(GL46C.glGetUniformLocation(compositeProgram, "Glow"), 0);
                GL46C.glActiveTexture(GL46C.GL_TEXTURE1);
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, depth);
                GL46C.glBindSampler(1, 0);
                GL46C.glUniform1i(GL46C.glGetUniformLocation(compositeProgram, "SceneDepth"), 1);
                GL46C.glActiveTexture(GL46C.GL_TEXTURE2);
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, colors[0]);
                GL46C.glBindSampler(2, 0);
                GL46C.glUniform1i(GL46C.glGetUniformLocation(compositeProgram, "VisibleEmission"), 2);
                GL46C.glUniform1i(GL46C.glGetUniformLocation(compositeProgram, "OutputEncoding"),
                        shader.getColorOutputEncoding());
                GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
            } catch (RuntimeException exception) {
                if (!warned) {
                    warned = true;
                    LogManager.getLogger().warn("皮肤柔光扩散失败: {}", exception.getMessage());
                }
            } finally {
                GL46C.glActiveTexture(GL46C.GL_TEXTURE2);
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture2);
                GL46C.glBindSampler(2, sampler2);
                GL46C.glActiveTexture(GL46C.GL_TEXTURE1);
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture1);
                GL46C.glBindSampler(1, sampler1);
                GL46C.glUseProgram(state.program);
                shader.setSkinGlowCapture(0);
            }
        }
    }

    private static void initialize() {
        if (blurProgram == 0) blurProgram = ShaderCompiler.compileRenderProgram(VERTEX,
                AssetsUtil.getAssetsAsString("shader/skin_glow_blur.frag.glsl"), "皮肤柔光模糊");
        if (compositeProgram == 0) compositeProgram = ShaderCompiler.compileRenderProgram(VERTEX,
                AssetsUtil.getAssetsAsString("shader/skin_glow_composite.frag.glsl"), "皮肤柔光合成");
        if (blurProgram == 0 || compositeProgram == 0) throw new IllegalStateException("皮肤柔光着色器编译失败");
        if (fullscreenVao == 0) fullscreenVao = GL46C.glGenVertexArrays();
    }

    private static void blur(int source, int target, float horizontal, float vertical) {
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, target);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, source);
        GL46C.glUniform2f(GL46C.glGetUniformLocation(blurProgram, "Direction"), horizontal, vertical);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
    }

    private static int halfWidth() { return Math.max(1, (width + 1) / 2); }
    private static int halfHeight() { return Math.max(1, (height + 1) / 2); }

    private static void allocate(int requestedWidth, int requestedHeight, int format) {
        if (framebuffers[0] != 0 && width == requestedWidth && height == requestedHeight && depthFormat == format) return;
        releaseAttachments();
        width = requestedWidth;
        height = requestedHeight;
        depthFormat = format;
        for (int target = 0; target < 3; target++) {
            framebuffers[target] = GL46C.glGenFramebuffers();
            colors[target] = GL46C.glGenTextures();
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, framebuffers[target]);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, colors[target]);
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA16F, target == 0 ? width : halfWidth(),
                    target == 0 ? height : halfHeight(), 0, GL46C.GL_RGBA, GL46C.GL_FLOAT, 0L);
            textureParameters(GL46C.GL_LINEAR);
            GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0,
                    GL46C.GL_TEXTURE_2D, colors[target], 0);
            if (target == 0) {
                depth = GL46C.glGenTextures();
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, depth);
                boolean stencil = format == GL46C.GL_DEPTH24_STENCIL8 || format == GL46C.GL_DEPTH32F_STENCIL8;
                int type = format == GL46C.GL_DEPTH24_STENCIL8 ? GL46C.GL_UNSIGNED_INT_24_8
                        : format == GL46C.GL_DEPTH32F_STENCIL8 ? GL46C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL46C.GL_FLOAT;
                GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, format, width, height, 0,
                        stencil ? GL46C.GL_DEPTH_STENCIL : GL46C.GL_DEPTH_COMPONENT, type, 0L);
                textureParameters(GL46C.GL_NEAREST);
                GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER,
                        stencil ? GL46C.GL_DEPTH_STENCIL_ATTACHMENT : GL46C.GL_DEPTH_ATTACHMENT,
                        GL46C.GL_TEXTURE_2D, depth, 0);
            }
            GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
            if (GL46C.glCheckFramebufferStatus(GL46C.GL_DRAW_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("皮肤柔光 FBO 不完整");
        }
    }

    private static void textureParameters(int filter) {
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, filter);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, filter);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, GL46C.GL_CLAMP_TO_EDGE);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_T, GL46C.GL_CLAMP_TO_EDGE);
    }

    private static void releaseAttachments() {
        for (int target = 0; target < 3; target++) {
            if (framebuffers[target] != 0) GL46C.glDeleteFramebuffers(framebuffers[target]);
            if (colors[target] != 0) GL46C.glDeleteTextures(colors[target]);
            framebuffers[target] = colors[target] = 0;
        }
        if (depth != 0) GL46C.glDeleteTextures(depth);
        depth = 0;
    }

    public static void release() {
        releaseAttachments();
        if (blurProgram != 0) GL46C.glDeleteProgram(blurProgram);
        if (compositeProgram != 0) GL46C.glDeleteProgram(compositeProgram);
        if (fullscreenVao != 0) GL46C.glDeleteVertexArrays(fullscreenVao);
        blurProgram = compositeProgram = fullscreenVao = 0;
    }
}
