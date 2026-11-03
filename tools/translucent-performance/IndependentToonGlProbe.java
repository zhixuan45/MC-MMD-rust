package com.shiroha.mmdskin.render.pipeline.toon;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.config.AbstractMmdSkinConfig;
import com.shiroha.mmdskin.config.ConfigData;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.render.outline.OutlineRenderPass;
import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import org.joml.Matrix4f;
import org.lwjgl.egl.EGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.lwjgl.egl.EGL10.*;
import static org.lwjgl.egl.EGL14.*;
import static org.lwjgl.egl.EGL15.*;
import static org.lwjgl.opengl.GL46C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/** Synthetic OpenGL pixels, production Toon/outline/frame renderer. This does NOT boot Iris or Minecraft. */
public final class IndependentToonGlProbe {
    private static final int SIZE = 128;
    private static final float[] BACKGROUND = {.1f, .2f, .3f, 1};
    private static final float[] CHEST = {.15f, .1f, .05f, 1};
    private static final Matrix4f PROJECTION = new Matrix4f().perspective((float) Math.PI / 2, 1, .1f, 100);
    private static int positions, normals, uv, indices, vao, solid, white;
    private static ToonShaderCpu toon;
    private static final ConfigData CONFIG = new ConfigData();
    private static final Object OWNER = new Object();
    private record Target(int fbo, int color, int depth, int width, int auxiliary) {}

    public static void main(String[] args) {
        long display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        try (var stack = MemoryStack.stackPush()) {
            check(eglInitialize(display, stack.mallocInt(1), stack.mallocInt(1)), "EGL initialize");
            EGL.createDisplayCapabilities(display);
            check(eglBindAPI(EGL_OPENGL_API), "EGL OpenGL API");
            var configs = stack.mallocPointer(1); var count = stack.mallocInt(1);
            check(eglChooseConfig(display, stack.ints(EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE,
                    EGL_OPENGL_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                    EGL_DEPTH_SIZE, 24, EGL_NONE), configs, count), "EGL config");
            long surface = eglCreatePbufferSurface(display, configs.get(0), stack.ints(EGL_WIDTH, SIZE, EGL_HEIGHT, SIZE, EGL_NONE));
            long context = eglCreateContext(display, configs.get(0), NULL, stack.ints(EGL_CONTEXT_MAJOR_VERSION, 4,
                    EGL_CONTEXT_MINOR_VERSION, 5, EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT, EGL_NONE));
            check(surface != NULL && context != NULL && eglMakeCurrent(display, surface, surface, context), "EGL context");
            try {
                GL.createCapabilities();
                if (args.length == 0 || (args[0].equals("ToonTransparencyGlProbe") || args[0].equals("BlockEntityTransparencyGlProbe"))) RenderSystem.initRenderThread();
                System.out.println("GL: " + glGetString(GL_VERSION) + " / " + glGetString(GL_RENDERER));
                if (args.length == 0) run();
                else runExisting(args);
            } finally {
                eglMakeCurrent(display, NULL, NULL, NULL); eglDestroyContext(display, context);
                eglDestroySurface(display, surface); eglTerminate(display);
            }
        }
    }

    private static void runExisting(String[] args) {
        try {
            String name = "com.shiroha.mmdskin.render.material." + args[0];
            Class<?> type = Class.forName(name);
            if (args[0].equals("BlockEntityTransparencyGlProbe")) {
                try {
                    type.getMethod("runInCurrentContext", String[].class).invoke(null,
                            (Object) Arrays.copyOfRange(args, 1, args.length));
                } finally { com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyPipeline.release(); }
                return;
            }
            var method = args.length > 1 ? type.getDeclaredMethod("run", boolean.class) : type.getDeclaredMethod("run");
            method.setAccessible(true);
            if (args.length > 1) method.invoke(null, Boolean.parseBoolean(args[1]));
            else method.invoke(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Existing GL regression failed", e); }
    }

    private static void run() {
        CONFIG.toonRenderingEnabled = true;
        CONFIG.toonOutlineWidth = .1f; // Deliberately enlarged synthetic silhouette, independent of the GUI clamp.
        CONFIG.toonOutlineR = .02f; CONFIG.toonOutlineG = .02f; CONFIG.toonOutlineB = .8f;
        CONFIG.toonOutlineFollowLighting = false;
        ConfigManager.init(new AbstractMmdSkinConfig(CONFIG) {});
        setup();
        var main = target(SIZE, 0);
        var entity = target(SIZE, main.depth); // Different, unknown color attachment; shared actual world depth.
        warmup(main);
        for (boolean chest : new boolean[]{false, true}) {
            frame(main, entity, chest, false, false, false);
            float[] image = pixels(main);
            int model = 0, outline = 0;
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                int p = (y * SIZE + x) * 4;
                if (image[p] > image[p + 1] * 2 && image[p] > .15f) model++;
                if (image[p + 2] > .5f && image[p] < .1f) outline++;
                if (chest && x >= SIZE / 2) near(Arrays.copyOfRange(image, p, p + 4), CHEST, "late chest occludes body and outline");
            }
            check(model > 100, "production Toon surface covers an area");
            check(outline > 4, "production antialiased outline covers an area");
            near(pixel(image, 2, 2), BACKGROUND, "uncovered scene unchanged");
            System.out.println("opaque/outline/chest=" + chest + " area: body=" + model + " outline=" + outline);
        }
        frame(main, entity, false, true, false, false);
        near(pixel(pixels(main), SIZE / 2, SIZE / 2), BACKGROUND, "world transparency cancels overlay");
        frame(main, entity, false, false, true, false);
        near(pixel(pixels(main), SIZE / 2, SIZE / 2), BACKGROUND, "failed late draw preserves final color");
        frame(main, entity, false, false, false, true);
        near(pixel(pixels(main), SIZE / 2, SIZE / 2), BACKGROUND, "changed buffers reject whole overlay");
        var differentDepth = target(SIZE, 0);
        frame(main, differentDepth, false, false, false, false);
        near(pixel(pixels(main), SIZE / 2, SIZE / 2), BACKGROUND, "unrelated depth target rejected");
        var small = target(SIZE / 2, 0);
        frame(small, small, false, false, false, false);
        check(pixel(pixels(small), SIZE / 4, SIZE / 4)[0] > .15f, "viewport resize rebuilds storage");
        ToonOffscreenRenderer.release();
        warmup(main);
        frame(main, entity, false, false, false, false);
        check(pixel(pixels(main), SIZE / 2, SIZE / 2)[0] > .15f, "release and reinitialize");
        ToonOffscreenRenderer.release();
        check(glGetError() == GL_NO_ERROR, "no GL error after all cases");
        toon.cleanup();
        System.out.println("PASS independent Toon: final overwrite, opaque depth, outline area, conservative transparency rejection, exceptions, stale buffers, resize, release, GL state.");
    }

    private static void warmup(Target main) {
        ToonOffscreenRenderer.beginVerifiedFrame(OWNER);
        check(!ToonOffscreenRenderer.available(), "first frame cannot assume a post-final hook");
        ToonOffscreenRenderer.beforeFinal(OWNER, main.fbo, main.color, main.width, main.width);
        ToonOffscreenRenderer.afterFinal(OWNER, main.fbo, main.color, main.width, main.width);
    }

    private static void frame(Target main, Target source, boolean chest, boolean blended, boolean fail, boolean stale) {
        glBindFramebuffer(GL_FRAMEBUFFER, source.fbo); glViewport(0, 0, main.width, main.width);
        RenderSystem.disableScissor(); RenderSystem.enableDepthTest(); RenderSystem.depthMask(true); RenderSystem.depthFunc(GL_LESS);
        // Reset the synthetic host's raw final state from the preceding case too.
        glEnable(GL_DEPTH_TEST); glDepthMask(true); glDepthFunc(GL_LESS);
        RenderSystem.disableBlend(); RenderSystem.colorMask(true, true, true, true);
        glClearBufferfv(GL_COLOR, 0, BACKGROUND); glClearBufferfv(GL_DEPTH, 0, new float[]{1});
        ToonOffscreenRenderer.beginVerifiedFrame(OWNER);
        ToonOffscreenRenderer.NativeScope nativeDraw;
        try (var scope = ToonOffscreenRenderer.nativeOpaqueDraw(null)) {
            nativeDraw = scope; scope.captureTarget(PROJECTION.get(new float[16]));
        }
        // Native geometry already occupies world depth; it is retained as a fallback in the game.
        drawSolidSurface();
        if (source.auxiliary != 0) glClearBufferfv(GL_COLOR, 1, new float[]{.21f, .32f, .43f, 1});
        check(ToonOffscreenRenderer.enqueue(nativeDraw, () -> !stale, () -> {
            if (fail) throw new IllegalStateException("intentional probe exception");
            drawProductionToon();
        }), "admit compatible opaque native geometry");
        if (blended) ToonOffscreenRenderer.reject("WORLD_TRANSPARENCY_REQUIRES_INTERLEAVING: synthetic fixture");
        glBindFramebuffer(GL_FRAMEBUFFER, main.fbo);
        if (chest) {
            glEnable(GL_SCISSOR_TEST); glScissor(main.width / 2, 0, main.width / 2, main.width);
            glClearBufferfv(GL_DEPTH, 0, new float[]{.2f}); glDisable(GL_SCISSOR_TEST);
        }
        // Unusual caller state must survive capture, late geometry and composition, even on failure.
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, source.fbo); glBindFramebuffer(GL_READ_FRAMEBUFFER, main.fbo);
        glUseProgram(solid); glViewport(0, 0, main.width, main.width);
        glVertexAttrib4f(3, .17f, .23f, .31f, .9f);
        glEnable(GL_SCISSOR_TEST); glScissor(3, 4, 10, 11); glEnable(GL_FRAMEBUFFER_SRGB);
        var before = state();
        ToonOffscreenRenderer.beforeFinal(OWNER, main.fbo, main.color, main.width, main.width);
        check(before.equals(state()), "capture restores GL state");
        // Simulate a pack final that replaces ALL scene color after the public loader callbacks.
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, main.fbo); glDisable(GL_SCISSOR_TEST);
        glClearBufferfv(GL_COLOR, 0, BACKGROUND);
        if (chest) { glEnable(GL_SCISSOR_TEST); glScissor(main.width / 2, 0, main.width / 2, main.width);
            glClearBufferfv(GL_COLOR, 0, CHEST); glDisable(GL_SCISSOR_TEST); }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, source.fbo); glEnable(GL_SCISSOR_TEST); glScissor(3, 4, 10, 11);
        // Iris final uses raw GL too; exercise stale Blaze3D caches, not just ordinary setter state.
        glDisable(GL_DEPTH_TEST); glDepthFunc(GL_ALWAYS); glDepthMask(false);
        before = state();
        ToonOffscreenRenderer.afterFinal(OWNER, main.fbo, main.color, main.width, main.width);
        check(before.equals(state()), "late replay restores GL state: before=" + before + " after=" + state());
        glDisable(GL_FRAMEBUFFER_SRGB); glDisable(GL_SCISSOR_TEST);
        if (source.auxiliary != 0) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, source.fbo);
            glReadBuffer(GL_COLOR_ATTACHMENT1);
            float[] auxiliary = new float[4]; glReadPixels(0, 0, 1, 1, GL_RGBA, GL_FLOAT, auxiliary);
            near(auxiliary, new float[]{.21f, .32f, .43f, 1}, "unknown auxiliary MRT untouched");
            glReadBuffer(GL_COLOR_ATTACHMENT0);
        }
        check(glGetError() == GL_NO_ERROR, "frame has no GL error");
    }

    private static void drawSolidSurface() {
        glUseProgram(solid); glBindVertexArray(vao); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
        matrix(solid, "ProjMat", PROJECTION); matrix(solid, "ModelViewMat", new Matrix4f());
        glEnableVertexAttribArray(0); glBindBuffer(GL_ARRAY_BUFFER, positions); glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glDisable(GL_CULL_FACE); glDrawElements(GL_TRIANGLES, 12, GL_UNSIGNED_INT, 0L);
    }

    private static void drawProductionToon() {
        glBindVertexArray(vao); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
        toon.useMain();
        toon.setProjectionMatrix(buffer(PROJECTION)); toon.setModelViewMatrix(buffer(new Matrix4f()));
        toon.setLightIntensity(1); toon.setToonLevels(4); toon.setTransitionSoftness(0);
        toon.setShadowColor(.3f, .3f, .3f); toon.setLightDirection(.5f, .3f, 1);
        toon.setRimLight(5, 0); toon.setSpecular(96, 0); toon.setEnvironmentLighting(0, 0, 15);
        int p = toon.getMainProgram();
        glUniform4f(glGetUniformLocation(p, "MaterialColor"), .8f, .15f, .05f, 1);
        glUniform1i(glGetUniformLocation(p, "AlphaPass"), 1);
        glUniform1i(glGetUniformLocation(p, "SphereMode"), 0);
        for (int i = 0; i < 3; i++) { glEnableVertexAttribArray(i); glBindBuffer(GL_ARRAY_BUFFER, new int[]{positions, normals, uv}[i]);
            glVertexAttribPointer(i, i == 2 ? 2 : 3, GL_FLOAT, false, 0, 0); }
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, white); glBindSampler(0, 0); toon.setSampler0(0);
        RenderSystem.enableDepthTest(); RenderSystem.depthMask(true); RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE); glDrawElements(GL_TRIANGLES, 12, GL_UNSIGNED_INT, 0L);
        var meshes = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());
        meshes.putInt(8, 12).putFloat(12, 1).put(16, (byte) 1);
        com.shiroha.mmdskin.render.shader.EyeEmissionBuffer.unbind(toon.getMainProgram());
        OutlineRenderPass.draw(toon, new OutlineRenderPass.Inputs(positions, normals, uv, meshes, 1, 4,
                GL_UNSIGNED_INT, buffer(PROJECTION), buffer(new Matrix4f()), 1), (id, alpha) -> alpha, id -> white);
    }

    private static void setup() {
        vao = glGenVertexArrays(); glBindVertexArray(vao);
        positions = vbo(new float[]{-.7f,-.7f,-2, .7f,-.7f,-2, .7f,.7f,-2, -.7f,.7f,-2});
        normals = vbo(new float[]{-.6f,-.6f,.53f, .6f,-.6f,.53f, .6f,.6f,.53f, -.6f,.6f,.53f});
        uv = vbo(new float[]{0,0,1,0,1,1,0,1});
        indices = glGenBuffers(); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, new int[]{0,1,2,0,2,3,2,1,0,3,2,0}, GL_STATIC_DRAW);
        white = glGenTextures(); glBindTexture(GL_TEXTURE_2D, white);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4).putInt(-1).flip());
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        solid = ShaderCompiler.compileRenderProgram("#version 330 core\nlayout(location=0) in vec3 Position; uniform mat4 ProjMat, ModelViewMat; void main(){gl_Position=ProjMat*ModelViewMat*vec4(Position,1);}",
                "#version 330 core\nout vec4 Color; void main(){Color=vec4(.4,.4,.4,1);}", "probe native opaque");
        toon = new ToonShaderCpu(); check(toon.init(), "production Toon compile");
    }

    private static Target target(int size, int sharedDepth) {
        int color = glGenTextures(); glBindTexture(GL_TEXTURE_2D, color); glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, size, size);
        int depth = sharedDepth;
        if (depth == 0) { depth = glGenTextures(); glBindTexture(GL_TEXTURE_2D, depth); glTexStorage2D(GL_TEXTURE_2D, 1, GL_DEPTH_COMPONENT24, size, size); }
        int fbo = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
        int auxiliary = 0;
        if (sharedDepth != 0) {
            auxiliary = glGenTextures(); glBindTexture(GL_TEXTURE_2D, auxiliary);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, size, size);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, auxiliary, 0);
            glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1});
        } else glDrawBuffer(GL_COLOR_ATTACHMENT0);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "fixture FBO complete");
        return new Target(fbo, color, depth, size, auxiliary);
    }

    private static int vbo(float[] data) { int id=glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER,id); glBufferData(GL_ARRAY_BUFFER,data,GL_STATIC_DRAW);return id; }
    private static FloatBuffer buffer(Matrix4f m) { return m.get(ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer()); }
    private static void matrix(int program, String name, Matrix4f matrix) { glUniformMatrix4fv(glGetUniformLocation(program, name),false, buffer(matrix)); }
    private static float[] pixels(Target target) { glBindFramebuffer(GL_READ_FRAMEBUFFER,target.fbo); float[] data=new float[target.width*target.width*4]; glReadPixels(0,0,target.width,target.width,GL_RGBA,GL_FLOAT,data);return data; }
    private static float[] pixel(float[] image,int x,int y) { int size=(int)Math.sqrt(image.length/4); return Arrays.copyOfRange(image,(y*size+x)*4,(y*size+x)*4+4); }
    private static void near(float[] a,float[] b,String label) { for(int i=0;i<4;i++) check(Math.abs(a[i]-b[i])<.015f,label+" "+Arrays.toString(a)); }
    private static String state() {
        float[] eye=new float[4]; glGetVertexAttribfv(3,GL_CURRENT_VERTEX_ATTRIB,eye);
        int[] viewport=new int[4],scissor=new int[4]; glGetIntegerv(GL_VIEWPORT,viewport);glGetIntegerv(GL_SCISSOR_BOX,scissor);
        return glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)+":"+glGetInteger(GL_READ_FRAMEBUFFER_BINDING)+":"+glGetInteger(GL_CURRENT_PROGRAM)
                +":"+Arrays.toString(viewport)+":"+Arrays.toString(scissor)+":"+glIsEnabled(GL_SCISSOR_TEST)+":"+glIsEnabled(GL_FRAMEBUFFER_SRGB)
                +":"+glGetInteger(GL_DEPTH_FUNC)+":"+glGetBoolean(GL_DEPTH_WRITEMASK)+":"+glGetInteger(GL_VERTEX_ARRAY_BINDING)
                +":"+glGetInteger(GL_ACTIVE_TEXTURE)+":"+glGetInteger(GL_TEXTURE_BINDING_2D)+":"+glGetIntegeri(GL_SAMPLER_BINDING,0)
                +":"+Arrays.toString(eye)
                +":"+glGetInteger(GL_ARRAY_BUFFER_BINDING)+":"+glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING)
                +":"+glIsEnabled(GL_DEPTH_TEST)+":"+glIsEnabled(GL_CULL_FACE)+":"+glGetInteger(GL_DRAW_BUFFER0)+":"+glGetInteger(GL_DRAW_BUFFER1)
                +":"+glGetIntegeri(GL_BLEND_SRC_RGB,0)+":"+glGetIntegeri(GL_BLEND_DST_RGB,0)+":"+glGetIntegeri(GL_BLEND_EQUATION_ALPHA,0)
                +":"+glGetIntegeri(GL_BLEND_SRC_RGB,1)+":"+glGetIntegeri(GL_BLEND_DST_RGB,1)+":"+glGetIntegeri(GL_BLEND_EQUATION_ALPHA,1);
    }
    private static void check(boolean ok,String label) { if(!ok) throw new AssertionError(label); }
}
