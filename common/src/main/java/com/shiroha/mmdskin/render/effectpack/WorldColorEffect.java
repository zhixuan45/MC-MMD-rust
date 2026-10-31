package com.shiroha.mmdskin.render.effectpack;

import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL33C;
import java.util.Objects;

/** 世界完成后、HUD 前的可选 RGB 变换；复制源颜色避免读写同一纹理。 */
public final class WorldColorEffect {
    private static final String VERTEX = """
            #version 330 core
            out vec2 fxUv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                fxUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static int program, texture, vao, width, height;
    private static String compiled;
    private static final java.util.Map<String, Integer> programs = new java.util.LinkedHashMap<>();

    public static void render() {
        FxEffect effect = EffectPackRuntime.get().world();
        if (effect == null) return;
        var target = Minecraft.getInstance().getMainRenderTarget();
        if (target.width <= 0 || target.height <= 0) return;
        if (effect.graph() != null) {
            FxGraphGlBackend.render(effect, target.frameBufferId, target.width, target.height);
            return;
        }
        apply(effect, target.frameBufferId, target.width, target.height);
    }

    static boolean apply(FxEffect effect, int framebuffer, int targetWidth, int targetHeight) {
        try (State state = new State()) {
            if (!Objects.equals(compiled, effect.colorFunction())) {
                compiled = effect.colorFunction();
                program = programs.computeIfAbsent(compiled, key -> ShaderCompiler.compileRenderProgram(VERTEX, fragment(key), "MMD FX 世界/" + effect.name()));
                if (program == 0) EffectPackRuntime.get().problem("world-compile:" + effect.id(), "世界效果编译失败: " + effect.name() + "；详见客户端日志");
            }
            if (program == 0) return false;
            if (vao == 0) vao = GL33C.glGenVertexArrays();
            if (texture == 0) texture = GL33C.glGenTextures();
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            GL33C.glBindSampler(0, 0);
            if (width != targetWidth || height != targetHeight) {
                width = targetWidth;
                height = targetHeight;
                GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
                GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA16F, width, height, 0, GL33C.GL_RGBA, GL33C.GL_FLOAT, 0L);
                GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
                GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
            }
            GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, framebuffer);
            int oldReadBuffer = GL33C.glGetInteger(GL33C.GL_READ_BUFFER);
            GL33C.glReadBuffer(GL33C.GL_COLOR_ATTACHMENT0);
            GL33C.glCopyTexSubImage2D(GL33C.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
            GL33C.glReadBuffer(oldReadBuffer);
            GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL33C.glViewport(0, 0, width, height);
            GL33C.glDisablei(GL33C.GL_BLEND, 0);
            GL33C.glDisable(GL33C.GL_DEPTH_TEST);
            GL33C.glDisable(GL33C.GL_CULL_FACE);
            GL33C.glDisable(GL33C.GL_SCISSOR_TEST);
            GL33C.glDepthMask(false);
            GL33C.glDisable(GL33C.GL_FRAMEBUFFER_SRGB);
            GL33C.glColorMaski(0, true, true, true, true);
            GL33C.glUseProgram(program);
            GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "FxScreen"), 0);
            GL33C.glBindVertexArray(vao);
            GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
            return true;
        }
    }

    public static String fragment(String colorFunction) {
        return "#version 330 core\nin vec2 fxUv;\nuniform sampler2D FxScreen;\nout vec4 fxColor;\n"
                + colorFunction + "\nvoid main() { vec4 c = texture(FxScreen, fxUv); fxColor = vec4(mmdFxColor(c).rgb, c.a); }";
    }

    public static void release() {
        for (int cached : programs.values()) if (cached != 0) GL33C.glDeleteProgram(cached);
        programs.clear();
        FxGraphGlBackend.release();
        if (texture != 0) GL33C.glDeleteTextures(texture);
        if (vao != 0) GL33C.glDeleteVertexArrays(vao);
        program = texture = vao = width = height = 0;
        compiled = null;
    }

    /** 只使用原始 GL 修改并成对恢复，保持 RenderSystem 状态缓存与原状态一致。 */
    static final class State implements AutoCloseable {
        final int program = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
        final int vao = GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING);
        final int active = GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE);
        final int read = GL33C.glGetInteger(GL33C.GL_READ_FRAMEBUFFER_BINDING);
        final int draw = GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
        final int unpack = GL33C.glGetInteger(GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        final int[] viewport = new int[4];
        final byte[] mask = new byte[4];
        final boolean depthMask = GL33C.glGetBoolean(GL33C.GL_DEPTH_WRITEMASK);
        final int[] caps = {GL33C.GL_FRAMEBUFFER_SRGB, GL33C.GL_DEPTH_TEST, GL33C.GL_CULL_FACE, GL33C.GL_SCISSOR_TEST};
        final boolean[] enabled = new boolean[4];
        final boolean blend = GL33C.glIsEnabledi(GL33C.GL_BLEND, 0);
        final int texture, sampler;
        State() {
            GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, viewport);
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var bytes = stack.malloc(4);
                GL33C.glGetBooleanv(GL33C.GL_COLOR_WRITEMASK, bytes);
                bytes.get(mask);
            }
            for (int i = 0; i < caps.length; i++) enabled[i] = GL33C.glIsEnabled(caps[i]);
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
            texture = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
            sampler = GL33C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, 0);
            GL33C.glActiveTexture(active);
        }
        @Override public void close() {
            GL33C.glUseProgram(program);
            GL33C.glBindVertexArray(vao);
            GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, read);
            GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, draw);
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, unpack);
            GL33C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL33C.glColorMaski(0, mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0);
            if (blend) GL33C.glEnablei(GL33C.GL_BLEND, 0); else GL33C.glDisablei(GL33C.GL_BLEND, 0);
            GL33C.glDepthMask(depthMask);
            for (int i = 0; i < caps.length; i++) { if (enabled[i]) GL33C.glEnable(caps[i]); else GL33C.glDisable(caps[i]); }
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            GL33C.glBindSampler(0, sampler);
            GL33C.glActiveTexture(active);
        }
    }
    private WorldColorEffect() { }
}
