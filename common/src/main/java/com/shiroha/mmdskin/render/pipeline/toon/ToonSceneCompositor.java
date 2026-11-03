package com.shiroha.mmdskin.render.pipeline.toon;

import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** Display color only, after Iris final. Depth testing took place during the late geometry replay. */
final class ToonSceneCompositor {
    private int program, vao;

    static boolean verifiedPresentationTarget(int width, int height, int expectedColor) {
        if (!(GL.getCapabilities().OpenGL45 || GL.getCapabilities().GL_ARB_direct_state_access)
                || GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == 0
                || GL46C.glCheckFramebufferStatus(GL46C.GL_DRAW_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE
                || GL46C.glGetInteger(GL46C.GL_SAMPLES) != 0) return false;
        int target = GL46C.GL_DRAW_FRAMEBUFFER, color = GL46C.GL_COLOR_ATTACHMENT0;
        if (GL46C.glGetFramebufferAttachmentParameteri(target, color, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL46C.GL_TEXTURE
                || GL46C.glGetFramebufferAttachmentParameteri(target, color, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) != expectedColor
                || GL46C.glGetFramebufferAttachmentParameteri(target, color, GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL) != 0
                || GL46C.glGetFramebufferAttachmentParameteri(target, color, GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER) != 0
                || GL46C.glGetFramebufferAttachmentParameteri(target, color, GL46C.GL_FRAMEBUFFER_ATTACHMENT_LAYERED) != 0
                || GL46C.glGetFramebufferAttachmentParameteri(target, color, GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE) != 0)
            return false;
        // Minecraft's LDR presentation storage. Never infer an arbitrary HDR/MRT encoding.
        return GL46C.glGetTextureLevelParameteri(expectedColor, 0, GL46C.GL_TEXTURE_INTERNAL_FORMAT) == GL46C.GL_RGBA8
                && GL46C.glGetTextureLevelParameteri(expectedColor, 0, GL46C.GL_TEXTURE_WIDTH) == width
                && GL46C.glGetTextureLevelParameteri(expectedColor, 0, GL46C.GL_TEXTURE_HEIGHT) == height
                && GL46C.glGetFramebufferAttachmentParameteri(target, color, GL46C.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING) == GL46C.GL_LINEAR;
    }

    void compose(int texture, int width, int height) {
        if (program == 0) {
            program = ShaderCompiler.compileRenderProgram("""
                    #version 330 core
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                    }
                    """, """
                    #version 330 core
                    uniform sampler2D ToonColor;
                    layout(location = 0) out vec4 Color;
                    void main() {
                        Color = texelFetch(ToonColor, ivec2(gl_FragCoord.xy), 0);
                        if (Color.a <= 0.0) discard;
                    }
                    """, "Independent Toon presentation");
            if (program == 0) throw new IllegalStateException("TOON_COMPOSITE_COMPILE_FAILED");
            vao = GL46C.glGenVertexArrays();
        }
        int[] mapping = ToonGlState.drawBuffers();
        try {
            GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
            GL46C.glViewport(0, 0, width, height);
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glDisable(GL46C.GL_CULL_FACE);
            GL46C.glDisable(GL46C.GL_DEPTH_TEST);
            GL46C.glDepthMask(false);
            GL46C.glDisable(GL46C.GL_FRAMEBUFFER_SRGB);
            GL46C.glColorMaski(0, true, true, true, true);
            GL46C.glEnablei(GL46C.GL_BLEND, 0);
            GL46C.glBlendEquationSeparatei(0, GL46C.GL_FUNC_ADD, GL46C.GL_FUNC_ADD);
            GL46C.glBlendFuncSeparatei(0, GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA, GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
            GL46C.glBindSampler(0, 0);
            GL46C.glUseProgram(program);
            GL46C.glUniform1i(GL46C.glGetUniformLocation(program, "ToonColor"), 0);
            GL46C.glBindVertexArray(vao);
            GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        } finally { GL46C.glDrawBuffers(mapping); }
    }

    void release() {
        if (program != 0) GL46C.glDeleteProgram(program);
        if (vao != 0) GL46C.glDeleteVertexArrays(vao);
        program = vao = 0;
    }
}
