package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.gl.DepthAttachment;
import com.shiroha.mmdskin.render.outline.OutlineCoverage;
import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** Real GL storage/copy regression, including the unsized vanilla depth texture. Run from repo root. */
public final class DepthAttachmentGlProbe {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void close(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < .00001f, message + ": " + actual + " != " + expected);
    }
    private static float depth() {
        float[] value = new float[1];
        GL46C.glReadPixels(8, 8, 1, 1, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, value);
        return value[0];
    }
    private static void clearDepth(float value) {
        GL46C.glDepthMask(true); GL46C.glClearDepth(value); GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
    }
    public static void run() throws Exception {
        RenderSystem.initRenderThread();
        System.setProperty("mmdskin.toonTransparencyLayerCount", "8");
        int fbo = GL46C.glGenFramebuffers(), tex = GL46C.glGenTextures(), color = GL46C.glGenTextures();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, color);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, 32, 32, 0, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, 0L);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, color, 0);
        GL46C.glViewport(0, 0, 32, 32); GL46C.glDepthFunc(GL46C.GL_LEQUAL);
        int program = ShaderCompiler.compileRenderProgram("""
            #version 330 core
            void main() { gl_Position = vec4(0,0,0,1); }
            """, """
            #version 330 core
            uniform int ToonTransparencyMode, ToonTransparencyHasPrevious;
            uniform sampler2D ToonTransparencyDepth, ToonTransparencyCoverage;
            out vec4 Color;
            void main() { Color = vec4(ToonTransparencyMode + ToonTransparencyHasPrevious)
                + texelFetch(ToonTransparencyDepth, ivec2(0), 0) + texelFetch(ToonTransparencyCoverage, ivec2(0), 0); }
            """, "depth attachment probe");
        check(program != 0, "probe shader"); GL46C.glUseProgram(program);
        try {
            for (int format : new int[]{GL46C.GL_DEPTH_COMPONENT, GL46C.GL_DEPTH_COMPONENT16,
                    GL46C.GL_DEPTH_COMPONENT24, GL46C.GL_DEPTH_COMPONENT32F,
                    GL46C.GL_DEPTH24_STENCIL8, GL46C.GL_DEPTH32F_STENCIL8, GL46C.GL_DEPTH_COMPONENT}) {
                boolean packed = format == GL46C.GL_DEPTH24_STENCIL8 || format == GL46C.GL_DEPTH32F_STENCIL8;
                GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
                GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_STENCIL_ATTACHMENT, GL46C.GL_TEXTURE_2D, 0, 0);
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, tex);
                GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, format, 32, 32, 0,
                        packed ? GL46C.GL_DEPTH_STENCIL : GL46C.GL_DEPTH_COMPONENT,
                        format == GL46C.GL_DEPTH32F_STENCIL8 ? GL46C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV
                        : packed ? GL46C.GL_UNSIGNED_INT_24_8 : GL46C.GL_UNSIGNED_INT, 0L);
                GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, packed ? GL46C.GL_DEPTH_STENCIL_ATTACHMENT : GL46C.GL_DEPTH_ATTACHMENT,
                        GL46C.GL_TEXTURE_2D, tex, 0);
                check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "source FBO");
                DepthAttachment descriptor = DepthAttachment.inspect();
                check(descriptor != null && descriptor.format() != 0, "supported " + format);
                clearDepth(.8f);
                try (ToonTransparencyLayers layers = ToonTransparencyLayers.begin()) {
                    check(layers != null && layers.layers() == 8, "8 layers active: " + descriptor);
                    // begin occurs before the opaque model pass: this later depth MUST be copied.
                    clearDepth(.25f);
                    layers.beginPeel(0);
                    int peel = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
                    GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, peel);
                    close(depth(), .25f, "new opaque model occlusion");
                    layers.endPeel();
                }
                close(depth(), .25f, "host depth preserved after layering");
                clearDepth(.75f);
                try (OutlineCoverage coverage = OutlineCoverage.begin(0)) {
                    check(coverage.isSampled(), "sampled outline for " + descriptor);
                    int internal = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
                    GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, internal);
                    close(depth(), .75f, "outline copied host depth");
                    coverage.beginOcclusion(); clearDepth(.125f); coverage.endOcclusion();
                }
                close(depth(), .75f, "private outline depth never writes host");
                check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL errors for " + descriptor);
                System.out.println("PASS storage/copy/layer/outline " + descriptor);
            }
            testOutlineCoverageShader();
            // A mismatched depth blit must fail, not advertise ACTIVE or use stale pixels.
            int bad = GL46C.glGenFramebuffers(), rb = GL46C.glGenRenderbuffers();
            GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, rb);
            GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER, GL46C.GL_DEPTH_COMPONENT16, 32, 32);
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, bad);
            GL46C.glFramebufferRenderbuffer(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_RENDERBUFFER, rb);
            GL46C.glDrawBuffer(GL46C.GL_NONE);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, fbo);
            check(!DepthAttachment.blit(0,0,32,32,0,0,32,32), "mismatched format rejected");
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
            GL46C.glDeleteFramebuffers(bad); GL46C.glDeleteRenderbuffers(rb);
            System.out.println("PASS mismatched copy rejected");
        } finally {
            ToonTransparencyLayers.cleanup(); OutlineCoverage.release();
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, 0);
            GL46C.glDeleteFramebuffers(fbo); GL46C.glDeleteTextures(new int[]{tex,color}); GL46C.glDeleteProgram(program);
            System.clearProperty("mmdskin.toonTransparencyLayerCount");
        }
    }

    private static void testOutlineCoverageShader() throws Exception {
        Path shaders = Path.of("common/src/main/resources/assets/mmdskin/shader");
        String body = Files.readString(shaders.resolve("toon_outline_body.frag.glsl"));
        String lighting = Files.readString(shaders.resolve("toon_surface_lighting.glsl"))
                .replace("/* TOON_LIGHTING */", "vec3 applyPackLighting(vec3 c, vec3 n, vec3 p) { return c; }");
        body = body.replace("/* TOON_SURFACE_LIGHTING */", lighting)
                .replace("/* TOON_OUTPUT_DECLARATIONS */", "layout(location=0) out vec4 outlineColor;")
                .replace("/* TOON_OUTPUT_WRITER */", "void writeToonOutputs(vec3 a, vec3 c, vec3 n, float alpha) { outlineColor=vec4(c,alpha); }");
        String vertex = """
            #version 330 core
            out vec3 viewNormal,viewPos,surfaceViewPos,viewLightDir; out vec2 texCoord0;
            void main() { vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);
                gl_Position=vec4(p*2-1,-.5,1); viewNormal=vec3(0,0,1);viewPos=surfaceViewPos=vec3(0,0,-1);
                viewLightDir=vec3(0,0,1);texCoord0=vec2(0); }
            """;
        int program = ShaderCompiler.compileRenderProgram(vertex, body, "production outline coverage fragment");
        check(program != 0, "production outline fragment compiles");
        int previous = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        int previousVao = GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING);
        int vao = GL46C.glGenVertexArrays(); GL46C.glBindVertexArray(vao);
        try {
            for (float alpha : new float[]{0, .043f, .25f, .5f, .75f, 1}) {
                try (OutlineCoverage pass = OutlineCoverage.begin(0)) {
                    check(pass.isSampled(), "private coverage target");
                    GL46C.glUseProgram(program);
                    GL46C.glUniform1i(GL46C.glGetUniformLocation(program,"OcclusionPass"),1);
                    GL46C.glUniform1f(GL46C.glGetUniformLocation(program,"OutlineAlpha"),alpha);
                    pass.beginOcclusion(); GL46C.glDrawArrays(GL46C.GL_TRIANGLES,0,3); pass.endOcclusion();
                    GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER,GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING));
                    float[] samples = new float[4];
                    GL46C.glReadPixels(8,8,2,2,GL46C.GL_DEPTH_COMPONENT,GL46C.GL_FLOAT,samples);
                    int covered=0;for(float z:samples)if(Math.abs(z-.25f)<.00001f)covered++;
                    check(covered == Math.round(alpha*4), "alpha coverage " + alpha + " samples=" + covered);
                }
                close(depth(),.75f,"host preserved with coverage alpha="+alpha);
            }
            check(GL46C.glGetError()==GL46C.GL_NO_ERROR,"outline fragment GL errors");
            System.out.println("PASS production outline fragment: 0/.043/.25/.5/.75/1 coverage, host preservation");
        } finally { GL46C.glUseProgram(previous); GL46C.glBindVertexArray(previousVao);
            GL46C.glDeleteVertexArrays(vao);GL46C.glDeleteProgram(program); }
    }
    public static void main(String[] args) throws Exception {
        check(GLFW.glfwInit(), "GLFW"); GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4); GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 5);
        long window = GLFW.glfwCreateWindow(32, 32, "depth format regression", 0, 0);
        check(window != 0, "window");
        try { GLFW.glfwMakeContextCurrent(window); GL.createCapabilities(); run(); }
        finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }
}
