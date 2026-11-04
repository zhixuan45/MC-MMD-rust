import com.shiroha.mmdskin.compat.iris.IrisTerrainShadow;
import com.shiroha.mmdskin.render.shader.ShadowSourceFixture;
import org.lwjgl.opengl.GL46C;

/** Exercises the production depth copy, including hostile host state and resource replacement. */
public final class TerrainShadowGlProbe {
    public static void main(String[] args) {
        if (!org.lwjgl.glfw.GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization");
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE, org.lwjgl.glfw.GLFW.GLFW_FALSE);
        long window = org.lwjgl.glfw.GLFW.glfwCreateWindow(16, 16, "Terrain depth validation", 0, 0);
        if (window == 0) throw new IllegalStateException("Hidden GL context");
        try {
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
            org.lwjgl.opengl.GL.createCapabilities();
            run();
        } finally {
            org.lwjgl.glfw.GLFW.glfwDestroyWindow(window);
            org.lwjgl.glfw.GLFW.glfwTerminate();
        }
    }

    public static void run() {
        int texture = GL46C.glGenTextures();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
        allocate(16);
        int fbo = GL46C.glGenFramebuffers();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_TEXTURE_2D, texture, 0);
        GL46C.glDrawBuffer(GL46C.GL_NONE);
        GL46C.glReadBuffer(GL46C.GL_NONE);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "source framebuffer");
        GL46C.glDepthMask(true);
        GL46C.glClearDepth(.9);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
        GL46C.glScissor(0, 0, 8, 16);
        GL46C.glClearDepth(.2);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glScissor(0, 0, 1, 1);
        int pbo = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, pbo);
        GL46C.glBufferData(GL46C.GL_PIXEL_UNPACK_BUFFER, 4L, GL46C.GL_STATIC_DRAW);
        int sentinel = GL46C.glGenTextures();
        int sampler = GL46C.glGenSamplers();
        GL46C.glActiveTexture(GL46C.GL_TEXTURE5);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, sentinel);
        GL46C.glBindSampler(5, sampler);
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, 0);
        ShadowSourceFixture.Renderer owner = new ShadowSourceFixture.Renderer(fbo, texture, 16);
        int screenDepth = GL46C.glGenTextures();
        GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, 0);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, screenDepth);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_DEPTH_COMPONENT24, 20, 12, 0,
                GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, 0L);
        int screenFbo = GL46C.glGenFramebuffers();
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, screenFbo);
        GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                GL46C.GL_TEXTURE_2D, screenDepth, 0);
        GL46C.glDrawBuffer(GL46C.GL_NONE);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, sentinel);
        GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, pbo);
        IrisTerrainShadow.beginFrame(owner, true);
        IrisTerrainShadow.capture(owner);
        check(IrisTerrainShadow.texture() != 0, "snapshot exists");
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, IrisTerrainShadow.texture());
        check(GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_WIDTH) == 16,
                "capture must use owned shadow depth while a different screen framebuffer is bound");
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, sentinel);
        check(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == screenFbo, "draw framebuffer restored");
        check(GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING) == 0, "read framebuffer restored");
        check(GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE) == GL46C.GL_TEXTURE5, "active texture restored");
        check(GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D) == sentinel, "texture binding restored");
        check(GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, 5) == sampler, "sampler unchanged");
        check(GL46C.glGetInteger(GL46C.GL_PIXEL_UNPACK_BUFFER_BINDING) == pbo, "unpack PBO restored");
        check(GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST), "scissor restored");
        assertSplit(IrisTerrainShadow.texture(), 16);
        assertSplit(texture, 16);
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, fbo);
        GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        drawCaster();
        assertSplit(IrisTerrainShadow.texture(), 16);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
        float[] host = new float[16 * 16];
        GL46C.glGetTexImage(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, host);
        check(Math.abs(host[12] - .05f) < .0001f, "later entity writes retained in host map");
        IrisTerrainShadow.beginFrame(owner, true);
        check(IrisTerrainShadow.texture() == 0, "uncaptured frame invalidated");
        GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, 0);
        allocate(8);
        owner.update(fbo, texture, 8);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        IrisTerrainShadow.capture(owner);
        check(IrisTerrainShadow.texture() != 0, "resize capture");
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, IrisTerrainShadow.texture());
        check(GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_WIDTH) == 8, "resize extent");
        // Iris allocates shadowtex0 with unsized GL_DEPTH_COMPONENT, not a sized 32F format.
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_DEPTH_COMPONENT, 8, 8, 0,
                GL46C.GL_DEPTH_COMPONENT, GL46C.GL_UNSIGNED_INT, 0L);
        GL46C.glClearDepth(.35);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        System.out.printf("Iris depth allocation: internal=%d, bits=%d, type=%d%n",
                GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_INTERNAL_FORMAT),
                GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_DEPTH_SIZE),
                GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_DEPTH_TYPE));
        IrisTerrainShadow.beginFrame(owner, true);
        IrisTerrainShadow.capture(owner);
        check(IrisTerrainShadow.texture() != 0, "Iris unsized depth capture");
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, IrisTerrainShadow.texture());
        float[] irisDepth = new float[8 * 8];
        GL46C.glGetTexImage(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, irisDepth);
        check(Math.abs(irisDepth[0] - .35f) < .0001f, "Iris unsized depth copied");
        owner.update(fbo, screenDepth, 8);
        IrisTerrainShadow.beginFrame(owner, true);
        IrisTerrainShadow.capture(owner);
        check(IrisTerrainShadow.texture() == 0, "reject a foreign texture even if the framebuffer has depth");
        owner.update(screenFbo, screenDepth, 8);
        IrisTerrainShadow.beginFrame(owner, true);
        IrisTerrainShadow.capture(owner);
        check(IrisTerrainShadow.texture() == 0, "reject screen dimensions that do not match Iris shadow resolution");
        owner.update(fbo, texture, 8);
        IrisTerrainShadow.beginFrame(owner, false);
        IrisTerrainShadow.capture(owner);
        check(IrisTerrainShadow.texture() == 0, "disabled capture");
        IrisTerrainShadow.beginFrame(owner, true);
        IrisTerrainShadow.capture(owner);
        int last = IrisTerrainShadow.texture();
        IrisTerrainShadow.beginFrame(new Object(), true);
        check(IrisTerrainShadow.texture() == 0 && !GL46C.glIsTexture(last), "renderer reload releases old storage");
        IrisTerrainShadow.release();
        GL46C.glBindSampler(5, 0);
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, 0);
        GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, 0);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glClearDepth(1);
        GL46C.glDeleteSamplers(sampler);
        GL46C.glDeleteBuffers(pbo);
        GL46C.glDeleteFramebuffers(fbo);
        GL46C.glDeleteFramebuffers(screenFbo);
        GL46C.glDeleteTextures(texture);
        GL46C.glDeleteTextures(sentinel);
        GL46C.glDeleteTextures(screenDepth);
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL error");
        System.out.println("PASS terrain depth copy: owned source while screen FBO bound, real caster depth writes, identity/extent rejection, split, state restoration, PBO, resize, missing frame, disable, reload");
    }

    private static void allocate(int size) {
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_DEPTH_COMPONENT32F, size, size, 0,
                GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, 0L);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
    }

    private static void assertSplit(int texture, int size) {
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
        float[] depth = new float[size * size];
        GL46C.glGetTexImage(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, depth);
        check(Math.abs(depth[3] - .2f) < .0001f && Math.abs(depth[12] - .9f) < .0001f, "full terrain split copied");
    }

    private static void drawCaster() {
        int shader = com.shiroha.mmdskin.render.shader.ShaderCompiler.compileRenderProgram("""
                #version 330 core
                void main() {
                    vec2 p = gl_VertexID == 0 ? vec2(-1.0, -1.0)
                            : gl_VertexID == 1 ? vec2(3.0, -1.0) : vec2(-1.0, 3.0);
                    gl_Position = vec4(p, -0.9, 1.0);
                }
                """, "#version 330 core\nvoid main() {}", "Caster depth after terrain copy");
        check(shader > 0, "caster shader compilation");
        int vao = GL46C.glGenVertexArrays();
        GL46C.glBindVertexArray(vao);
        GL46C.glUseProgram(shader);
        GL46C.glViewport(0, 0, 16, 16);
        GL46C.glEnable(GL46C.GL_DEPTH_TEST);
        GL46C.glDepthFunc(GL46C.GL_LESS);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        GL46C.glDisable(GL46C.GL_DEPTH_TEST);
        GL46C.glUseProgram(0);
        GL46C.glBindVertexArray(0);
        GL46C.glDeleteVertexArrays(vao);
        GL46C.glDeleteProgram(shader);
    }

    private static void check(boolean value, String detail) {
        if (!value) throw new AssertionError("Terrain shadow: " + detail);
    }
}
