package com.shiroha.mmdskin.ui.paperdoll;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.shiroha.mmdskin.render.pipeline.RenderPerformanceProfiler;
import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL46C;

/** One full-resolution transparent model image including outline, with isolated depth. */
public final class PaperDollFrameCache implements AutoCloseable {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private final PaperDollRefreshClock clock = new PaperDollRefreshClock();
    private int framebuffer, color, depth, program, vao, width, height;
    private boolean failed;

    public void invalidate() { clock.invalidate(); }

    /** False means allocation/target unsupported; the caller renders directly once. */
    public boolean render(Object key, int fps, long now, Runnable draw) {
        if (fps <= 0 || failed || GL46C.glIsEnabled(GL46C.GL_STENCIL_TEST)
                || GL46C.glIsEnabled(GL46C.GL_COLOR_LOGIC_OP)
                || GL46C.glIsEnabled(GL46C.GL_FRAMEBUFFER_SRGB)) return false;
        Target target = new Target();
        // A single color image cannot represent multiple outputs or per-target blending.
        // Reject those targets before changing any state (including unused indexed state).
        if (!singleColorTarget()) return false;
        int w = target.viewport[2], h = target.viewport[3];
        int max = Math.min(GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_SIZE), GL46C.glGetInteger(GL46C.GL_MAX_RENDERBUFFER_SIZE));
        if (w <= 0 || h <= 0 || w > max || h > max || (long) w * h * 8 > MAX_BYTES) return false;
        try {
            allocate(w, h);
        } catch (RuntimeException e) {
            close();
            failed = true;
            LogManager.getLogger().warn("纸娃娃离屏缓存不可用，回退每帧绘制", e);
            return false;
        } finally {
            target.restore();
        }
        boolean refresh = clock.due(key, fps, now);
        RenderPerformanceProfiler profiler = RenderPerformanceProfiler.get();
        if (refresh) {
            long timer = profiler.startTimer();
            clock.invalidate();
            try {
                GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, framebuffer);
                RenderSystem.viewport(0, 0, w, h);
                boolean mask = GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK);
                var colorMask = org.lwjgl.BufferUtils.createByteBuffer(4);
                GL46C.glGetBooleanv(GL46C.GL_COLOR_WRITEMASK, colorMask);
                RenderSystem.disableScissor();
                RenderSystem.depthMask(true);
                RenderSystem.colorMask(true, true, true, true);
                GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, new float[]{0, 0, 0, 0});
                GL46C.glClearBufferfv(GL46C.GL_DEPTH, 0, new float[]{1});
                RenderSystem.depthMask(mask);
                RenderSystem.colorMask(colorMask.get(0) != 0, colorMask.get(1) != 0,
                        colorMask.get(2) != 0, colorMask.get(3) != 0);
                if (target.scissor) RenderSystem.enableScissor(target.box[0] - target.viewport[0],
                        target.box[1] - target.viewport[1], target.box[2], target.box[3]);
                draw.run();
                clock.rendered(key, fps, now);
            } finally {
                target.restore();
                profiler.endTimer("paperDoll.cacheRefresh", timer);
            }
        }
        long timer = profiler.startTimer();
        try (CompositeState state = new CompositeState()) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.blendEquation(GL46C.GL_FUNC_ADD);
            // The offscreen target already contains RGB multiplied by coverage alpha.
            RenderSystem.blendFuncSeparate(GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                    GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
            RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
            RenderSystem.bindTexture(color);
            GL46C.glBindSampler(0, 0);
            GL46C.glUseProgram(program);
            GL46C.glUniform2i(GL46C.glGetUniformLocation(program, "Origin"), target.viewport[0], target.viewport[1]);
            BufferUploader.reset();
            GL46C.glBindVertexArray(vao);
            GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        } finally {
            profiler.endTimer("paperDoll.cacheComposite", timer);
        }
        profiler.recordPaperDollCache(refresh);
        return true;
    }

    private static boolean singleColorTarget() {
        int count = GL46C.glGetInteger(GL46C.GL_MAX_DRAW_BUFFERS);
        int first = GL46C.glGetInteger(GL46C.GL_DRAW_BUFFER0);
        if (first == GL46C.GL_NONE) return false;
        // Composition uses global blend setters; require identical indexed state so restoring
        // index zero also restores every other slot, even currently unused attachments.
        int[] properties = {GL46C.GL_BLEND_SRC_RGB, GL46C.GL_BLEND_DST_RGB,
                GL46C.GL_BLEND_SRC_ALPHA, GL46C.GL_BLEND_DST_ALPHA,
                GL46C.GL_BLEND_EQUATION_RGB, GL46C.GL_BLEND_EQUATION_ALPHA};
        var mask = org.lwjgl.BufferUtils.createByteBuffer(4);
        var other = org.lwjgl.BufferUtils.createByteBuffer(4);
        GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, 0, mask);
        boolean blend = GL46C.glIsEnabledi(GL46C.GL_BLEND, 0);
        for (int i = 1; i < count; i++) {
            if (GL46C.glGetInteger(GL46C.GL_DRAW_BUFFER0 + i) != GL46C.GL_NONE
                    || GL46C.glIsEnabledi(GL46C.GL_BLEND, i) != blend) return false;
            GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, i, other);
            for (int c = 0; c < 4; c++) if (mask.get(c) != other.get(c)) return false;
            if (org.lwjgl.opengl.GL.getCapabilities().OpenGL40) for (int property : properties)
                if (GL46C.glGetIntegeri(property, i) != GL46C.glGetIntegeri(property, 0)) return false;
        }
        return true;
    }

    private void allocate(int w, int h) {
        if (program == 0) {
            program = ShaderCompiler.compileRenderProgram("""
                    #version 330 core
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                    }
                    """, """
                    #version 330 core
                    uniform sampler2D Image;
                    uniform ivec2 Origin;
                    out vec4 Color;
                    void main() { Color = texelFetch(Image, ivec2(gl_FragCoord.xy) - Origin, 0); }
                    """, "纸娃娃缓存合成");
            if (program == 0) throw new IllegalStateException("纸娃娃缓存合成程序编译失败");
            vao = GL46C.glGenVertexArrays();
        }
        if (framebuffer != 0 && width == w && height == h) return;
        deleteAttachments();
        clock.invalidate();
        // Allocation uses raw GL and restores bindings before any Blaze3D rendering runs.
        int texture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        int renderbuffer = GL46C.glGetInteger(GL46C.GL_RENDERBUFFER_BINDING);
        try {
            framebuffer = GL46C.glGenFramebuffers();
            color = GL46C.glGenTextures();
            depth = GL46C.glGenRenderbuffers();
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, color);
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, w, h, 0, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, 0L);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
            GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, depth);
            GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER, GL46C.GL_DEPTH_COMPONENT24, w, h);
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, framebuffer);
            GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, color, 0);
            GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_RENDERBUFFER, depth);
            GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
            if (GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("纸娃娃缓存 FBO 不完整");
            width = w; height = h;
        } finally {
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
            GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, renderbuffer);
        }
    }

    private static final class Target {
        final int draw = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        final int read = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
        final int[] viewport = new int[4], box = new int[4];
        final boolean scissor = GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST);
        Target() { GL46C.glGetIntegerv(GL46C.GL_VIEWPORT, viewport); GL46C.glGetIntegerv(GL46C.GL_SCISSOR_BOX, box); }
        void restore() {
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, draw);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, read);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (scissor) RenderSystem.enableScissor(box[0], box[1], box[2], box[3]);
            else { RenderSystem.enableScissor(box[0], box[1], box[2], box[3]); RenderSystem.disableScissor(); }
        }
    }

    /** Only composition changes these states. Restore with Blaze3D so its cache stays correct. */
    private static final class CompositeState implements AutoCloseable {
        final int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM), vao = GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING);
        final int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE), texture, sampler;
        final boolean blend = GL46C.glIsEnabled(GL46C.GL_BLEND), depth = GL46C.glIsEnabled(GL46C.GL_DEPTH_TEST),
                cull = GL46C.glIsEnabled(GL46C.GL_CULL_FACE), mask = GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK);
        final int src = GL46C.glGetInteger(GL46C.GL_BLEND_SRC_RGB), dst = GL46C.glGetInteger(GL46C.GL_BLEND_DST_RGB),
                srcA = GL46C.glGetInteger(GL46C.GL_BLEND_SRC_ALPHA), dstA = GL46C.glGetInteger(GL46C.GL_BLEND_DST_ALPHA),
                eq = GL46C.glGetInteger(GL46C.GL_BLEND_EQUATION_RGB), eqA = GL46C.glGetInteger(GL46C.GL_BLEND_EQUATION_ALPHA);
        CompositeState() {
            RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
            texture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
            sampler = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, 0);
        }
        public void close() {
            GL46C.glUseProgram(program); GL46C.glBindVertexArray(vao);
            RenderSystem.activeTexture(GL46C.GL_TEXTURE0); RenderSystem.bindTexture(texture);
            GL46C.glBindSampler(0, sampler); RenderSystem.activeTexture(active);
            RenderSystem.blendFuncSeparate(src, dst, srcA, dstA);
            RenderSystem.blendEquation(eq); GL46C.glBlendEquationSeparate(eq, eqA);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            RenderSystem.depthMask(mask);
        }
    }

    public long vramBytes() { return (long) width * height * 8; }
    private void deleteAttachments() {
        if (framebuffer != 0) GL46C.glDeleteFramebuffers(framebuffer);
        if (color != 0) GL46C.glDeleteTextures(color);
        if (depth != 0) GL46C.glDeleteRenderbuffers(depth);
        framebuffer = color = depth = width = height = 0;
    }
    @Override public void close() {
        clock.invalidate(); deleteAttachments();
        if (program != 0) GL46C.glDeleteProgram(program);
        if (vao != 0) GL46C.glDeleteVertexArrays(vao);
        program = vao = 0; failed = false;
    }
}
