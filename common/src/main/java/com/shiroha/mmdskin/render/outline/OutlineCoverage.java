package com.shiroha.mmdskin.render.outline;

import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import com.shiroha.mmdskin.render.gl.DepthAttachment;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** 2×2 描边覆盖率采样。场景颜色不参与滤波，深度只读复制。 */
public final class OutlineCoverage implements AutoCloseable {
    private static final long MAX_BYTES = 384L * 1024 * 1024;
    private static int framebuffer, color, depth, geometryVao, compositeVao, program;
    private static int width, height, depthFormat;
    private static boolean warned;
    private final OutlineGlState state = new OutlineGlState();
    private final int colorSlot;
    private boolean sampled;
    private DepthAttachment sourceDepth;
    private String fallbackReason = "DEPTH_TARGET";
    private final java.nio.ByteBuffer targetMask = org.lwjgl.BufferUtils.createByteBuffer(4);

    private OutlineCoverage(int colorSlot) {
        this.colorSlot = colorSlot;
        if (colorSlot < 0 || colorSlot >= state.drawBuffers.length) {
            state.close();
            throw new IllegalArgumentException("描边主颜色槽无效");
        }
        GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, colorSlot, targetMask);
    }

    public static OutlineCoverage begin(int colorSlot) {
        return begin(colorSlot, false);
    }

    /** Shared world fragments need original geometry depth, so bypass the flattened private color image. */
    public static OutlineCoverage begin(int colorSlot, boolean sharedWorld) {
        OutlineCoverage pass = new OutlineCoverage(colorSlot);
        try {
            if (geometryVao == 0) geometryVao = GL46C.glGenVertexArrays();
            GL46C.glBindVertexArray(geometryVao);
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, pass.state.ebo);
            int format = sharedWorld ? 0 : pass.sourceDepthFormat();
            if (format != 0) pass.fallbackReason = "SIZE_BUDGET_OR_GL";
            int w = pass.state.viewport[2], h = pass.state.viewport[3];
            int maxSize = Math.min(GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_SIZE), GL46C.glGetInteger(GL46C.GL_MAX_RENDERBUFFER_SIZE));
            if (format != 0 && w > 0 && h > 0 && w <= maxSize / 2 && h <= maxSize / 2
                    && (long) w * h * 4 * 12 <= MAX_BYTES && GL.getCapabilities().OpenGL40) {
                pass.allocate(w * 2, h * 2, format);
                pass.sampled = pass.copyDepth();
                pass.fallbackReason = pass.sampled ? "ACTIVE" : "DEPTH_COPY_FAILED";
            }
            if (!pass.sampled) {
                if (!sharedWorld && !warned) {
                    warned = true;
                    LogManager.getLogger().warn("描边覆盖率采样不可用，保持直接描边: reason={}, FBO={}, depth={}, viewport={}x{}",
                            pass.fallbackReason, pass.state.drawFbo, pass.sourceDepth, w, h);
                }
                pass.state.restoreTarget();
                pass.routeColor();
                pass.applyTargetMask();
                GL46C.glEnablei(GL46C.GL_BLEND, 0);
                if (pass.state.indexedBlend) {
                    GL46C.glBlendEquationSeparatei(0, GL46C.GL_FUNC_ADD, GL46C.GL_FUNC_ADD);
                    GL46C.glBlendFuncSeparatei(0, GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                            GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
                } else {
                    GL46C.glBlendEquationSeparate(GL46C.GL_FUNC_ADD, GL46C.GL_FUNC_ADD);
                    GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                            GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
                }
            }
            GL46C.glBindSampler(0, 0);
            GL46C.glDepthMask(false);
            GL46C.glEnable(GL46C.GL_DEPTH_TEST);
            GL46C.glEnable(GL46C.GL_CULL_FACE);
            GL46C.glCullFace(GL46C.GL_FRONT);
            return pass;
        } catch (RuntimeException e) {
            pass.close();
            release();
            throw e;
        }
    }

    public boolean isSampled() { return sampled; }

    /** Only the private depth copy is writable; never seal the host scene for outlines. */
    public void beginOcclusion() {
        if (!sampled) throw new IllegalStateException("描边遮挡需要独立深度目标");
        GL46C.glColorMaski(0, false, false, false, false);
        GL46C.glDepthMask(true);
        GL46C.glDisable(GL46C.GL_CULL_FACE);
    }

    public void endOcclusion() {
        GL46C.glColorMaski(0, true, true, true, true);
        GL46C.glDepthMask(false);
        GL46C.glEnable(GL46C.GL_CULL_FACE);
        GL46C.glCullFace(GL46C.GL_FRONT);
    }

    private int sourceDepthFormat() {
        if (state.drawFbo == 0) { fallbackReason = "DEFAULT_FRAMEBUFFER"; return 0; }
        if (GL46C.glGetInteger(GL46C.GL_SAMPLES) != 0) { fallbackReason = "MULTISAMPLE"; return 0; }
        if (GL46C.glIsEnabled(GL46C.GL_STENCIL_TEST) || GL46C.glIsEnabled(GL46C.GL_COLOR_LOGIC_OP)) {
            fallbackReason = "STENCIL_OR_LOGIC_OP"; return 0;
        }
        sourceDepth = DepthAttachment.inspect();
        if (sourceDepth == null) return 0;
        if (!sourceDepth.contains(state.viewport)) { fallbackReason = "VIEWPORT"; return 0; }
        if (sourceDepth.format() == 0) fallbackReason = "DEPTH_FORMAT";
        return sourceDepth.format();
    }

    private void allocate(int w, int h, int format) {
        if (framebuffer != 0 && width == w && height == h && depthFormat == format) {
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, framebuffer);
            return;
        }
        deleteAttachments();
        width = w; height = h; depthFormat = format;
        framebuffer = GL46C.glGenFramebuffers();
        color = GL46C.glGenTextures();
        depth = GL46C.glGenRenderbuffers();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, color);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, w, h, 0, GL46C.GL_RGBA, GL46C.GL_FLOAT, 0L);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, depth);
        GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER, format, w, h);
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, color, 0);
        boolean stencil = format == GL46C.GL_DEPTH24_STENCIL8 || format == GL46C.GL_DEPTH32F_STENCIL8;
        GL46C.glFramebufferRenderbuffer(GL46C.GL_DRAW_FRAMEBUFFER,
                stencil ? GL46C.GL_DEPTH_STENCIL_ATTACHMENT : GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_RENDERBUFFER, depth);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        if (GL46C.glCheckFramebufferStatus(GL46C.GL_DRAW_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("描边覆盖率 FBO 不完整");
        }
    }

    private boolean copyDepth() {
        GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, state.drawFbo);
        int[] v = state.viewport;
        // 深度格式严格匹配，只对单采样缓冲使用 NEAREST 缩放。
        if (!DepthAttachment.blit(v[0], v[1], v[0] + v[2], v[1] + v[3], 0, 0, width, height)) return false;
        GL46C.glColorMaski(0, true, true, true, true);
        GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, new float[]{0, 0, 0, 0});
        GL46C.glViewport(0, 0, width, height);
        if (state.scissor) {
            int[] s = state.scissorBox;
            GL46C.glScissor((s[0] - v[0]) * 2, (s[1] - v[1]) * 2, s[2] * 2, s[3] * 2);
            GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
        }
        GL46C.glEnablei(GL46C.GL_BLEND, 0);
        GL46C.glBlendEquationSeparatei(0, GL46C.GL_FUNC_ADD, GL46C.GL_FUNC_ADD);
        GL46C.glBlendFuncSeparatei(0, GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, state.texture0);
        return true;
    }

    private void routeColor() {
        if (colorSlot < 0 || colorSlot >= state.drawBuffers.length) throw new IllegalStateException("描边主颜色槽无效");
        GL46C.glDrawBuffer(state.drawBuffers[colorSlot]);
    }

    private void applyTargetMask() {
        GL46C.glColorMaski(0, targetMask.get(0) != 0, targetMask.get(1) != 0,
                targetMask.get(2) != 0, targetMask.get(3) != 0);
    }

    public void composite() {
        if (!sampled) return;
        if (program == 0) {
            program = ShaderCompiler.compileRenderProgram("""
                    #version 330 core
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                    }
                    """, """
                    #version 330 core
                    uniform sampler2D Coverage;
                    uniform ivec2 ViewOrigin;
                    layout(location = 0) out vec4 Color;
                    void main() {
                        ivec2 p = (ivec2(gl_FragCoord.xy) - ViewOrigin) * 2;
                        Color = (texelFetch(Coverage, p, 0) + texelFetch(Coverage, p + ivec2(1,0), 0)
                              + texelFetch(Coverage, p + ivec2(0,1), 0) + texelFetch(Coverage, p + ivec2(1,1), 0)) * 0.25;
                        if (Color.a == 0.0) discard;
                    }
                    """, "描边覆盖率合成");
            if (program == 0) throw new IllegalStateException("描边覆盖率合成程序编译失败");
            compositeVao = GL46C.glGenVertexArrays();
        }
        state.restoreTarget();
        routeColor();
        applyTargetMask();
        GL46C.glDisable(GL46C.GL_DEPTH_TEST);
        GL46C.glDisable(GL46C.GL_CULL_FACE);
        GL46C.glUseProgram(program);
        GL46C.glUniform1i(GL46C.glGetUniformLocation(program, "Coverage"), 0);
        GL46C.glUniform2i(GL46C.glGetUniformLocation(program, "ViewOrigin"), state.viewport[0], state.viewport[1]);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, color);
        GL46C.glBindVertexArray(compositeVao);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
    }

    @Override public void close() { state.close(); }

    private static void deleteAttachments() {
        if (framebuffer != 0) GL46C.glDeleteFramebuffers(framebuffer);
        if (color != 0) GL46C.glDeleteTextures(color);
        if (depth != 0) GL46C.glDeleteRenderbuffers(depth);
        framebuffer = color = depth = 0;
    }

    /** 必须在渲染线程释放；关闭选项与 shader 清理共用。 */
    public static void release() {
        deleteAttachments();
        if (geometryVao != 0) GL46C.glDeleteVertexArrays(geometryVao);
        if (compositeVao != 0) GL46C.glDeleteVertexArrays(compositeVao);
        if (program != 0) GL46C.glDeleteProgram(program);
        geometryVao = compositeVao = program = 0;
        width = height = depthFormat = 0;
        warned = false;
    }
}
