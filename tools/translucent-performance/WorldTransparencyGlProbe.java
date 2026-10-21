package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyPipeline;
import com.shiroha.mmdskin.render.scene.RenderScene;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Actual GL draws through SubMeshDrawHelper and the same terrain capture/resolve API as both loaders. */
public final class WorldTransparencyGlProbe {
    private static final int SIZE = 64;
    private static int model, terrain, vao, ebo, white, mainFbo;
    private record Layer(float depth, float slope, float r, float g, float b, float alpha) {}
    private static final Layer RED = new Layer(-.15f, .7f, 1, 0, 0, .5f);
    private static final Layer YELLOW = new Layer(.2f, -.4f, 1, 1, 0, .25f);
    private static final Layer WATER = new Layer(0, 0, 0, 0, 1, .5f);
    private static final Layer GLASS = new Layer(.35f, 0, 0, 1, 0, .3f);

    private static ByteBuffer buffer(int size) { return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void uniform(int p, String name, float value) { GL46C.glUniform1f(GL46C.glGetUniformLocation(p, name), value); }

    public static void main(String[] args) {
        System.setProperty("mmdskin.worldTransparencyQueue", "true");
        check(GLFW.glfwInit(), "GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "world transparency", 0, 0);
        check(window != 0, "OpenGL 4.3 window");
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            GL46C.glDebugMessageCallback((source,type,id,severity,length,message,user) -> {
                if (type == GL46C.GL_DEBUG_TYPE_ERROR) new Exception(org.lwjgl.system.MemoryUtil.memUTF8(message, length)).printStackTrace();
            }, 0);
            GL46C.glEnable(GL46C.GL_DEBUG_OUTPUT_SYNCHRONOUS);
            GL46C.glEnable(GL46C.GL_DEBUG_OUTPUT);
            System.out.println("GL: " + GL46C.glGetString(GL46C.GL_VERSION));
            setup();
            crossing(false);
            crossing(true);
            opaqueAndHoles();
            fabulous();
            outsideWorld();
            resize();
            overflow();
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "final GL error");
            System.out.println("PASS WorldTransparencyGlProbe: crossing MMD/water/glass, reversed submissions, "
                    + "later opaque occlusion, holes, no main depth seal, Fabulous premultiplied color/depth, "
                    + "scene isolation, GL state/ranges, resize/release, overflow coverage");
        } finally {
            WorldTransparencyPipeline.release();
            GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate();
        }
    }

    private static int shader(int type, String source) {
        int id = GL46C.glCreateShader(type); GL46C.glShaderSource(id, source); GL46C.glCompileShader(id);
        check(GL46C.glGetShaderi(id, GL46C.GL_COMPILE_STATUS) != 0, GL46C.glGetShaderInfoLog(id)); return id;
    }

    private static int program(String version, String output) {
        int vs = shader(GL46C.GL_VERTEX_SHADER, "#version " + version + "\n" + """
                uniform float Depth;
                uniform float Slope;
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2) * 2.0 - 1.0;
                    gl_Position = vec4(p, Depth + Slope * p.x, 1.0);
                }
                """);
        String mrt = output.equals("toonOutput0")
                ? "layout(location=1) out vec4 toonOutput1; layout(location=2) out vec4 toonOutput2; "
                    + "layout(location=3) out vec4 toonOutput3;\n" : "";
        String mrtWrites = output.equals("toonOutput0")
                ? "toonOutput1=vec4(1.0); toonOutput2=vec4(1.0); toonOutput3=vec4(1.0);\n" : "";
        int fs = shader(GL46C.GL_FRAGMENT_SHADER, "#version " + version + "\n" + mrt + """
                uniform vec4 MaterialColor;
                uniform int AlphaPass;
                uniform int DisableTransparency;
                uniform int Hole;
                out vec4 OUTPUT;
                void main() {
                    if (Hole != 0 && gl_FragCoord.x < 32.0) discard;
                    vec4 color = MaterialColor;
                    if (color.a < 0.001) discard;
                    if (DisableTransparency != 0) color.a = 1.0;
                    if (AlphaPass == 1 && color.a < 0.999) discard;
                    if (AlphaPass == 2 && color.a >= 0.999) discard;
                    OUTPUT = color;
                    MRT_WRITES
                }
                """.replace("OUTPUT", output).replace("MRT_WRITES", mrtWrites));
        int p = GL46C.glCreateProgram(); GL46C.glAttachShader(p, vs); GL46C.glAttachShader(p, fs);
        GL46C.glLinkProgram(p); check(GL46C.glGetProgrami(p, GL46C.GL_LINK_STATUS) != 0, GL46C.glGetProgramInfoLog(p));
        GL46C.glDeleteShader(vs); GL46C.glDeleteShader(fs); return p;
    }

    private static int target() {
        int fbo = GL46C.glGenFramebuffers(); GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        int color = GL46C.glGenTextures(); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, color);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, SIZE, SIZE, 0,
                GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, color, 0);
        int depth = GL46C.glGenRenderbuffers(); GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, depth);
        GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER, GL46C.GL_DEPTH_COMPONENT24, SIZE, SIZE);
        GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_RENDERBUFFER, depth);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "test FBO");
        return fbo;
    }

    private static void setup() {
        model = program("330 core", "toonOutput0"); terrain = program("150", "fragColor");
        vao = GL46C.glGenVertexArrays(); GL46C.glBindVertexArray(vao);
        ebo = GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, new int[]{0, 1, 2}, GL46C.GL_STATIC_DRAW);
        white = GL46C.glGenTextures(); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, white);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, 1, 1, 0,
                GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, buffer(4).putInt(0, -1));
        mainFbo = target();
    }

    private static void clear() {
        WorldTransparencyPipeline.endFrame();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, mainFbo);
        GL46C.glViewport(0, 0, SIZE, SIZE);
        RenderSystem.depthMask(true); GL46C.glDepthMask(true);
        GL46C.glColorMask(true, true, true, true); GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        GL46C.glClearDepth(1); GL46C.glClearColor(.1f, .1f, .1f, 1);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
        RenderSystem.enableDepthTest(); GL46C.glEnable(GL46C.GL_DEPTH_TEST); GL46C.glDepthFunc(GL46C.GL_LEQUAL);
        RenderSystem.enableBlend(); GL46C.glEnable(GL46C.GL_BLEND);
        RenderSystem.defaultBlendFunc();
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA,GL46C.GL_ONE_MINUS_SRC_ALPHA,GL46C.GL_ONE,GL46C.GL_ZERO);
        GL46C.glBindVertexArray(vao); GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        RenderSystem.activeTexture(GL46C.GL_TEXTURE0); GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindSampler(0, 0);
    }

    private static void drawModel(Layer layer, boolean hole) {
        GL46C.glUseProgram(model);
        GL46C.glUniform1i(GL46C.glGetUniformLocation(model, "Hole"), hole ? 1 : 0);
        ModelMaterial material = new ModelMaterial();
        material.name = "probe"; material.diffuse = new float[]{0, 0, 0, 1};
        material.ambient = new float[]{layer.r, layer.g, layer.b}; material.specular = new float[3];
        material.hasAlpha = material.hasTranslucentPixels = layer.alpha < .999f;
        ByteBuffer mesh = buffer(20).putInt(0, 0).putInt(4, 0).putInt(8, 3).putFloat(12, layer.alpha)
                .put(16, (byte) 1).put(17, (byte) 1);
        int[] calls = {0};
        try (var scene = WorldTransparencyPipeline.modelScene(RenderScene.WORLD);
             var order = new TranslucentDrawOrder()) {
            SubMeshDrawHelper.draw(mesh, 1, 4, GL46C.GL_UNSIGNED_INT, id -> white, (id, a) -> a,
                    new ModelMaterial[]{material}, null, false, id -> {
                        calls[0]++;
                        uniform(model, "Depth", layer.depth); uniform(model, "Slope", layer.slope);
                    }, order, null);
            check(order.indexBuffer() == 0, "shared path does not build model-only sorted indices");
        }
        check(calls[0] == 1, "one draw, no translucent depth seal: " + calls[0]);
        check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM) == model, "model program restored");
    }

    private static void drawTerrain(Layer layer) {
        GL46C.glUseProgram(terrain);
        uniform(terrain, "Depth", layer.depth); uniform(terrain, "Slope", layer.slope);
        GL46C.glUniform4f(GL46C.glGetUniformLocation(terrain, "MaterialColor"), layer.r, layer.g, layer.b, layer.alpha);
        GL46C.glUniform1i(GL46C.glGetUniformLocation(terrain, "AlphaPass"), 0);
        check(WorldTransparencyPipeline.captureTerrain(() -> {
            check(!GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK), "shared translucent terrain uses depthMask(false)");
            GL46C.glDrawElements(GL46C.GL_TRIANGLES, 3, GL46C.GL_UNSIGNED_INT, 0);
        }), "terrain enters shared queue");
        check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM) == terrain, "terrain program restored");
    }

    private static void crossing(boolean reverse) {
        clear(); WorldTransparencyPipeline.beginFrame(terrain, false);
        drawModel(reverse ? YELLOW : RED, false); drawModel(reverse ? RED : YELLOW, false);
        check(Math.abs(depth(32) - 1) < 1e-6, "MMD translucency leaves main depth unchanged");
        WorldTransparencyPipeline.beginTerrain(true);
        drawTerrain(reverse ? GLASS : WATER); drawTerrain(reverse ? WATER : GLASS);
        finishWithStateCheck();
        for (int x : new int[]{8, 24, 40, 56}) {
            float[] expected = expected(List.of(RED, YELLOW, WATER, GLASS), x, 1, new float[]{.1f,.1f,.1f});
            pixel(x, expected, "crossing glass/water/MMD reverse=" + reverse);
            check(Math.abs(depth(x) - 1) < 1e-6, "world translucency leaves main depth unchanged");
        }
    }

    private static void finishWithStateCheck() {
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL error before state checks");
        GL46C.glEnable(GL46C.GL_SCISSOR_TEST); GL46C.glScissor(4, 5, 30, 31);
        GL46C.glColorMask(true, false, true, false);
        GL46C.glBlendEquationSeparate(GL46C.GL_FUNC_REVERSE_SUBTRACT, GL46C.GL_MAX);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE, GL46C.GL_ZERO, GL46C.GL_ONE);
        GL46C.glDepthMask(true); GL46C.glDepthFunc(GL46C.GL_GREATER);
        int reserved = GL46C.glGetInteger(GL46C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) - 2;
        int prior = GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, prior);
        int alignment = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, alignment + 64L, GL46C.GL_DYNAMIC_DRAW);
        GL46C.glBindBufferRange(GL46C.GL_SHADER_STORAGE_BUFFER, reserved, prior, alignment, 64);
        int sampler = GL46C.glGenSamplers(); GL46C.glBindSampler(0, sampler);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE3);
        int p = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        int error = GL46C.glGetError();
        check(error == GL46C.GL_NO_ERROR, "GL error setting up state checks: " + error);
        WorldTransparencyPipeline.endTerrain();
        check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM) == p, "resolve program restored");
        check(GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE) == GL46C.GL_TEXTURE3, "active texture restored");
        check(GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, 0) == sampler, "sampler restored");
        check(GL46C.glGetIntegeri(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, reserved) == prior, "SSBO restored");
        check(GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_START, reserved) == alignment, "SSBO range restored");
        check(GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING) == prior, "generic SSBO restored");
        check(GL46C.glGetInteger(GL46C.GL_DEPTH_FUNC) == GL46C.GL_GREATER && GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK), "depth state restored");
        check(GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST), "scissor restored");
        check(GL46C.glGetInteger(GL46C.GL_BLEND_EQUATION_RGB) == GL46C.GL_FUNC_REVERSE_SUBTRACT, "blend equation restored");
        ByteBuffer mask = buffer(4); GL46C.glGetBooleanv(GL46C.GL_COLOR_WRITEMASK, mask);
        check(mask.get(0) != 0 && mask.get(1) == 0 && mask.get(2) != 0 && mask.get(3) == 0, "color mask restored");
        GL46C.glDeleteBuffers(prior); GL46C.glDeleteSamplers(sampler);
        GL46C.glDisable(GL46C.GL_SCISSOR_TEST); GL46C.glColorMask(true,true,true,true);
        GL46C.glBlendEquationSeparate(GL46C.GL_FUNC_ADD,GL46C.GL_FUNC_ADD);
    }

    private static void opaqueAndHoles() {
        clear(); WorldTransparencyPipeline.beginFrame(terrain, false);
        Layer red = new Layer(.4f,0,1,0,0,.5f), blue = new Layer(-.4f,0,0,0,1,1);
        drawModel(red, true); drawModel(blue, true); // opaque entity is submitted AFTER translucent MMD
        WorldTransparencyPipeline.beginTerrain(true); drawTerrain(WATER); WorldTransparencyPipeline.endTerrain();
        pixel(16, new float[]{.05f,.05f,.55f}, "alpha hole exposes water");
        pixel(48, new float[]{0,0,1}, "final opaque entity rejects earlier MMD and water");
        check(Math.abs(depth(48) - .3f) < 1e-5, "opaque depth preserved");
    }

    private static void fabulous() {
        clear(); WorldTransparencyPipeline.beginFrame(terrain, true); drawModel(RED, false);
        int translucent = target(); GL46C.glClearColor(0,0,0,0);
        GL46C.glDepthMask(true); GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
        WorldTransparencyPipeline.beginTerrain(true); drawTerrain(GLASS); WorldTransparencyPipeline.endTerrain();
        float[] expected = expected(List.of(RED,GLASS),16,1,new float[3]);
        pixel(16, expected, "Fabulous premultiplied transparency");
        check(Math.abs(depth(16) - ((RED.depth + RED.slope * ((16.5f / SIZE)*2-1))*.5f+.5f)) < 1e-5,
                "Fabulous receives nearest transparent depth");
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER,mainFbo);
        check(depth(16) == 1, "Fabulous leaves main opaque depth intact");
    }

    private static void outsideWorld() {
        clear(); WorldTransparencyPipeline.beginFrame(terrain, false); GL46C.glUseProgram(model);
        try (var ignored = WorldTransparencyPipeline.modelScene(RenderScene.INVENTORY)) {
            check(!WorldTransparencyPipeline.prepareModel(), "inventory isolated");
        }
        WorldTransparencyPipeline.endFrame();
        try (var ignored = WorldTransparencyPipeline.modelScene(RenderScene.WORLD)) {
            check(!WorldTransparencyPipeline.prepareModel(), "no capture outside renderLevel");
        }
        WorldTransparencyPipeline.beginFrame(terrain, false);
        WorldTransparencyPipeline.beginTerrain(true); WorldTransparencyPipeline.endTerrain();
        pixel(16,new float[]{.1f,.1f,.1f},"no MMD leaves world unchanged");
    }

    private static void resize() {
        clear(); WorldTransparencyPipeline.beginFrame(terrain,false);
        GL46C.glViewport(8,8,32,32); drawModel(RED,false);
        WorldTransparencyPipeline.beginTerrain(true); drawTerrain(WATER); WorldTransparencyPipeline.endTerrain();
        pixel(48,new float[]{.1f,.1f,.1f},"viewport offset does not touch outside pixels");
        WorldTransparencyPipeline.release(); crossing(false);
    }

    private static void overflow() {
        clear(); WorldTransparencyPipeline.beginFrame(terrain,false);
        Layer faint = new Layer(0,0,1,0,0,.01f); drawModel(faint,false);
        WorldTransparencyPipeline.beginTerrain(true);
        for (int i=0;i<24;i++) drawTerrain(faint);
        WorldTransparencyPipeline.endTerrain();
        float alpha=1-(float)Math.pow(.99,25);
        pixel(16,new float[]{alpha+(1-alpha)*.1f,(1-alpha)*.1f,(1-alpha)*.1f},"overflow preserves coverage");
    }

    private static float[] expected(List<Layer> layers, int x, float depth, float[] background) {
        float nx=(x+.5f)/SIZE*2-1;
        List<Layer> order = new ArrayList<>(layers);
        order.sort(Comparator.comparingDouble(l -> -(l.depth+l.slope*nx)));
        float[] rgb=background.clone();
        for (Layer l:order) {
            if ((l.depth+l.slope*nx)*.5f+.5f > depth) continue;
            float[] c={l.r,l.g,l.b}; for(int i=0;i<3;i++) rgb[i]=c[i]*l.alpha+rgb[i]*(1-l.alpha);
        }
        return rgb;
    }

    private static void pixel(int x,float[] expected,String message) {
        ByteBuffer data=buffer(4); GL46C.glReadPixels(x,32,1,1,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,data);
        for(int i=0;i<3;i++) check(Math.abs(Byte.toUnsignedInt(data.get(i))/255f-expected[i])<.012f,
                message+" x="+x+" channel="+i+" actual="+Byte.toUnsignedInt(data.get(i))/255f+" expected="+expected[i]);
    }

    private static float depth(int x) {
        var data=buffer(4).asFloatBuffer(); GL46C.glReadPixels(x,32,1,1,GL46C.GL_DEPTH_COMPONENT,GL46C.GL_FLOAT,data);
        return data.get(0);
    }
}
