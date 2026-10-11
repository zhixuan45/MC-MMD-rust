package com.shiroha.mmdskin.render.outline;

import com.shiroha.mmdskin.render.shader.ShaderCompiler;
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
        OutlineCoverage pass = new OutlineCoverage(colorSlot);
        try {
            if (geometryVao == 0) geometryVao = GL46C.glGenVertexArrays();
            GL46C.glBindVertexArray(geometryVao);
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, pass.state.ebo);
            int format = pass.sourceDepthFormat();
            int w = pass.state.viewport[2], h = pass.state.viewport[3];
            int maxSize = Math.min(GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_SIZE), GL46C.glGetInteger(GL46C.GL_MAX_RENDERBUFFER_SIZE));
            if (format != 0 && w > 0 && h > 0 && w <= maxSize / 2 && h <= maxSize / 2
                    && (long) w * h * 4 * 12 <= MAX_BYTES && GL.getCapabilities().OpenGL40) {
                pass.allocate(w * 2, h * 2, format);
                pass.copyDepth();
                pass.sampled = true;
            } else {
                if (!warned) {
                    warned = true;
                    LogManager.getLogger().warn("描边覆盖率采样不可用，保持直接描边: FBO={}, depthFormat={}, viewport={}x{}",
                            pass.state.drawFbo, format, w, h);
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

    private int sourceDepthFormat() {
        if (state.drawFbo == 0 || GL46C.glGetInteger(GL46C.GL_SAMPLES) != 0
                || GL46C.glIsEnabled(GL46C.GL_STENCIL_TEST) || GL46C.glIsEnabled(GL46C.GL_COLOR_LOGIC_OP)) return 0;
        int kind = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_DRAW_FRAMEBUFFER,
                GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (kind == GL46C.GL_NONE) return 0;
        int id = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_DRAW_FRAMEBUFFER,
                GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int format, sourceWidth, sourceHeight;
        if (kind == GL46C.GL_RENDERBUFFER) {
            GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, id);
            format = GL46C.glGetRenderbufferParameteri(GL46C.GL_RENDERBUFFER, GL46C.GL_RENDERBUFFER_INTERNAL_FORMAT);
            sourceWidth = GL46C.glGetRenderbufferParameteri(GL46C.GL_RENDERBUFFER, GL46C.GL_RENDERBUFFER_WIDTH);
            sourceHeight = GL46C.glGetRenderbufferParameteri(GL46C.GL_RENDERBUFFER, GL46C.GL_RENDERBUFFER_HEIGHT);
        } else if (kind == GL46C.GL_TEXTURE && GL.getCapabilities().OpenGL45) {
            int level = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_DRAW_FRAMEBUFFER,
                    GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
            format = GL46C.glGetTextureLevelParameteri(id, level, GL46C.GL_TEXTURE_INTERNAL_FORMAT);
            sourceWidth = GL46C.glGetTextureLevelParameteri(id, level, GL46C.GL_TEXTURE_WIDTH);
            sourceHeight = GL46C.glGetTextureLevelParameteri(id, level, GL46C.GL_TEXTURE_HEIGHT);
        } else {
            // 旧驱动不猜测未知 texture target，保留深度正确的直接绘制。
            return 0;
        }
        int[] v = state.viewport;
        if (v[0] < 0 || v[1] < 0 || (long) v[0] + v[2] > sourceWidth || (long) v[1] + v[3] > sourceHeight) return 0;
        return switch (format) {
            case GL46C.GL_DEPTH_COMPONENT16, GL46C.GL_DEPTH_COMPONENT24, GL46C.GL_DEPTH_COMPONENT32,
                    GL46C.GL_DEPTH_COMPONENT32F, GL46C.GL_DEPTH24_STENCIL8, GL46C.GL_DEPTH32F_STENCIL8 -> format;
            default -> 0;
        };
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

    private void copyDepth() {
        GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, state.drawFbo);
        int[] v = state.viewport;
        // 深度格式严格匹配，只对单采样缓冲使用 NEAREST 缩放。
        GL46C.glBlitFramebuffer(v[0], v[1], v[0] + v[2], v[1] + v[3], 0, 0, width, height,
                GL46C.GL_DEPTH_BUFFER_BIT, GL46C.GL_NEAREST);
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
