package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.render.shader.SkinningComputeShader;
import org.joml.Matrix4f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** Pixel/depth oracle for synthetic and real BSL shadows, including production GPU skinning. */
public final class ShadowAlphaGlProbe {
    private static final int W = 256, H = 256;
    private static final float[] MATERIAL_ALPHA = {1f, .9f, .5f, 0f};
    private static final float[] DYNAMIC_SCALE = {1f, .5f};
    private static final float[] CUTOFFS = {.1f, .98f};
    private static final int RUNS = MATERIAL_ALPHA.length * DYNAMIC_SCALE.length;

    private int program, vao, vbo, ebo, texture, colorTarget, depthTarget, fbo;
    private ByteBuffer originalVertices;
    private final int[] gpu = new int[11];
    private final SkinningComputeShader skinning = new SkinningComputeShader();
    private boolean opaque, iris, gpuDraw;
    private float uvShift;
    private static String bslFragment;
    private boolean bsl;
    private ByteBuffer submeshes, indices;
    private ModelMaterial[] materials;
    private float dynamicScale;

    private static ByteBuffer bytes(int size) { return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static int shader(int type, String source) {
        int id = GL46C.glCreateShader(type);
        GL46C.glShaderSource(id, source);
        GL46C.glCompileShader(id);
        if (GL46C.glGetShaderi(id, GL46C.GL_COMPILE_STATUS) == 0)
            throw new AssertionError("shader compile: " + GL46C.glGetShaderInfoLog(id));
        return id;
    }

    private static int program(boolean iris) {
        int vs = shader(GL46C.GL_VERTEX_SHADER, """
                #version 430 core
                layout(location=0) in vec3 Position;
                layout(location=1) in vec2 UV;
                layout(location=2) in vec4 iris_Color;
                out vec2 uv;
                out vec4 vertexColor;
                void main(){ uv=UV; vertexColor=iris_Color; gl_Position=vec4(Position,1.0); }
                """);
        String fragment = """
                #version 430 core
                uniform sampler2D DiffuseSampler;
                uniform vec4 MaterialColor;
                uniform vec4 ColorModulator;
                uniform float AlphaCutoff;
                in vec2 uv;
                in vec4 vertexColor;
                layout(location=0) out vec4 fragColor;
                void main(){
                    vec4 texel=texture(DiffuseSampler,uv);
                    float alpha=texel.a*MaterialColor.a;
                    if(alpha<AlphaCutoff) discard;
                    fragColor=vec4(texel.rgb*MaterialColor.rgb,alpha);
                }
                """;
        if (iris) fragment = fragment.replace("uniform vec4 MaterialColor;", "")
                .replace("MaterialColor", "(vertexColor * ColorModulator)");
        int fs = shader(GL46C.GL_FRAGMENT_SHADER, fragment);
        int id = GL46C.glCreateProgram();
        GL46C.glAttachShader(id, vs); GL46C.glAttachShader(id, fs); GL46C.glLinkProgram(id);
        GL46C.glDeleteShader(vs); GL46C.glDeleteShader(fs);
        if (GL46C.glGetProgrami(id, GL46C.GL_LINK_STATUS) == 0)
            throw new AssertionError("program link: " + GL46C.glGetProgramInfoLog(id));
        GL46C.glUseProgram(id);
        GL46C.glUniform1i(GL46C.glGetUniformLocation(id, "DiffuseSampler"), 0);
        GL46C.glUniform4f(GL46C.glGetUniformLocation(id, "ColorModulator"), 1, 1, 1, .7f);
        return id;
    }

    private int bslProgram() {
        int vs = shader(GL46C.GL_VERTEX_SHADER, """
                #version 120
                attribute vec3 Position;
                attribute vec2 UV;
                attribute vec4 iris_Color;
                varying vec2 texCoord;
                varying vec4 color;
                varying float mat;
                varying vec3 worldPos;
                void main(){ texCoord=UV; color=iris_Color; mat=0.0; worldPos=Position; gl_Position=vec4(Position,1.0); }
                """);
        int fs = shader(GL46C.GL_FRAGMENT_SHADER, bslFragment);
        int id = GL46C.glCreateProgram();
        GL46C.glAttachShader(id, vs); GL46C.glAttachShader(id, fs);
        GL46C.glBindAttribLocation(id, 0, "Position"); GL46C.glBindAttribLocation(id, 1, "UV");
        GL46C.glBindAttribLocation(id, 2, "iris_Color"); GL46C.glLinkProgram(id);
        check(GL46C.glGetProgrami(id, GL46C.GL_LINK_STATUS) != 0, "BSL link: " + GL46C.glGetProgramInfoLog(id));
        GL46C.glDeleteShader(vs); GL46C.glDeleteShader(fs); GL46C.glUseProgram(id);
        GL46C.glUniform1i(GL46C.glGetUniformLocation(id, "tex"), 0);
        GL46C.glUniform1i(GL46C.glGetUniformLocation(id, "noisetex"), 1);
        return id;
    }

    private void init() {
        program = bsl ? bslProgram() : program(iris);
        vao = GL46C.glGenVertexArrays(); GL46C.glBindVertexArray(vao);
        // Eight rows cover alpha 1/.9/.5/0, each in single-sided and double-sided mode.
        // Each row contains both CCW and CW quads, and each quad samples opaque/partial/zero texels.
        int rows = RUNS;
        int quadsPerRow = 6;
        ByteBuffer vertices = bytes(rows * quadsPerRow * 4 * 5 * Float.BYTES);
        indices = bytes(rows * quadsPerRow * 6 * Integer.BYTES);
        submeshes = bytes(rows * 20);
        int vertex = 0, index = 0;
        for (int row = 0; row < rows; row++) {
            int materialId = row / DYNAMIC_SCALE.length;
            boolean bothFaces = row % DYNAMIC_SCALE.length != 0;
            float y0 = -1f + 2f * row / rows, y1 = -1f + 2f * (row + 1) / rows;
            for (int side = 0; side < 2; side++) {
                float x0 = side == 0 ? -.96f : .04f, x1 = side == 0 ? -.04f : .96f;
                for (int texel = 0; texel < 3; texel++) {
                    float left = x0 + (x1 - x0) * texel / 3f;
                    float right = x0 + (x1 - x0) * (texel + 1) / 3f;
                    float u = (texel + .5f) / 3f;
                    float[][] corners = {{left,y0,u,.5f},{right,y0,u,.5f},{right,y1,u,.5f},{left,y1,u,.5f}};
                    int[] order = side == 0 ? new int[]{0,1,2,0,2,3} : new int[]{0,2,1,0,3,2};
                    for (float[] c : corners) vertices.putFloat(c[0]).putFloat(c[1]).putFloat(0).putFloat(c[2]).putFloat(c[3]);
                    for (int v : order) indices.putInt(vertex + v);
                    vertex += 4; index += 6;
                }
            }
            int base = row * 20;
            submeshes.putInt(base, materialId).putInt(base + 4, row * quadsPerRow * 6)
                    .putInt(base + 8, quadsPerRow * 6).putFloat(base + 12, MATERIAL_ALPHA[materialId])
                    .put(base + 16, (byte) 1).put(base + 17, (byte) (bothFaces ? 1 : 0));
        }
        vertices.flip(); indices.flip();
        originalVertices = vertices.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        vbo = GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, vbo);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, vertices, GL46C.GL_STATIC_DRAW);
        GL46C.glEnableVertexAttribArray(0); GL46C.glVertexAttribPointer(0, 3, GL46C.GL_FLOAT, false, 20, 0);
        GL46C.glEnableVertexAttribArray(1); GL46C.glVertexAttribPointer(1, 2, GL46C.GL_FLOAT, false, 20, 12);
        ebo = GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, indices, GL46C.GL_STATIC_DRAW);

        texture = GL46C.glGenTextures(); GL46C.glActiveTexture(GL46C.GL_TEXTURE0); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
        ByteBuffer texels = bytes(12);
        texels.put((byte)255).put((byte)255).put((byte)255).put((byte)255);
        texels.put((byte)255).put((byte)255).put((byte)255).put((byte)128);
        texels.put((byte)255).put((byte)255).put((byte)255).put((byte)0).flip();
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, 3, 1, 0, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, texels);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);

        materials = new ModelMaterial[MATERIAL_ALPHA.length];
        for (int i = 0; i < materials.length; i++) {
            materials[i] = new ModelMaterial(); materials[i].tex = texture;
            materials[i].hasAlpha = true; materials[i].hasTranslucentPixels = true;
        }
        createFramebuffer();
        GL46C.glViewport(0, 0, W, H); GL46C.glEnable(GL46C.GL_DEPTH_TEST); GL46C.glDepthFunc(GL46C.GL_LEQUAL);
        GL46C.glDisable(GL46C.GL_BLEND); GL46C.glEnable(GL46C.GL_CULL_FACE); GL46C.glCullFace(GL46C.GL_BACK);
        RenderSystem.activeTexture(GL46C.GL_TEXTURE0); RenderSystem.bindTexture(texture);
        initSkinning();
    }

    private int buffer(ByteBuffer data) {
        int id = GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, id);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, data, GL46C.GL_DYNAMIC_DRAW); return id;
    }

    private void initSkinning() {
        check(skinning.init(), "production skinning compute init");
        int count = originalVertices.capacity() / 20;
        ByteBuffer positions = bytes(count * 12), normals = bytes(count * 12);
        ByteBuffer bones = bytes(count * 16), weights = bytes(count * 16), uvs = bytes(count * 8);
        ByteBuffer offsets = bytes(count * 8);
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < 3; c++) positions.putFloat(originalVertices.getFloat(i * 20 + c * 4));
            normals.putFloat(0).putFloat(0).putFloat(1);
            bones.putInt(0).putInt(-1).putInt(-1).putInt(-1);
            weights.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
            uvs.putFloat(originalVertices.getFloat(i * 20 + 12)).putFloat(.5f);
            offsets.putFloat(1f / 3f).putFloat(0);
        }
        gpu[0] = buffer(positions.flip()); gpu[1] = buffer(normals.flip());
        gpu[2] = buffer(bones.flip()); gpu[3] = buffer(weights.flip()); gpu[4] = buffer(uvs.flip());
        gpu[5] = buffer(bytes(64)); gpu[6] = buffer(offsets.flip()); gpu[7] = buffer(bytes(4));
        int[] outputs = SkinningComputeShader.createOutputBuffers(count);
        gpu[8] = outputs[0]; gpu[9] = outputs[1]; gpu[10] = SkinningComputeShader.createSkinnedUvBuffer(count);
    }

    private void pose(float x, float uvWeight) {
        uvShift = uvWeight / 3f;
        FloatBuffer matrix = bytes(64).asFloatBuffer(); new Matrix4f().translation(x, 0, -.2f).get(matrix);
        skinning.uploadBoneMatrices(gpu[5], matrix, 1);
        skinning.updateUvMorphWeights(gpu[7], bytes(4).putFloat(0, uvWeight).asFloatBuffer());
        skinning.dispatch(new SkinningComputeShader.DispatchParams(gpu[0], gpu[1], gpu[2], gpu[3], gpu[4],
                gpu[8], gpu[9], gpu[10], gpu[5], 0, 0, 0, gpu[6], gpu[7], 1, originalVertices.capacity() / 20));
        ByteBuffer cpu = bytes(originalVertices.capacity());
        for (int i = 0; i < originalVertices.capacity(); i += 20) {
            cpu.putFloat(originalVertices.getFloat(i) + x).putFloat(originalVertices.getFloat(i + 4)).putFloat(-.2f)
                    .putFloat(originalVertices.getFloat(i + 12) + uvShift).putFloat(.5f);
        }
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, vbo); GL46C.glBufferSubData(GL46C.GL_ARRAY_BUFFER, 0, cpu.flip());
    }

    private void bindGeometry(boolean compute) {
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, compute ? gpu[8] : vbo);
        GL46C.glVertexAttribPointer(0, 3, GL46C.GL_FLOAT, false, compute ? 12 : 20, 0);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, compute ? gpu[10] : vbo);
        GL46C.glVertexAttribPointer(1, 2, GL46C.GL_FLOAT, false, compute ? 8 : 20, compute ? 0 : 12);
    }

    private void createFramebuffer() {
        fbo = GL46C.glGenFramebuffers(); GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        colorTarget = GL46C.glGenTextures(); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, colorTarget);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, W, H, 0, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, (ByteBuffer)null);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, colorTarget, 0);
        depthTarget = GL46C.glGenTextures(); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, depthTarget);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_DEPTH_COMPONENT24, W, H, 0, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_UNSIGNED_INT, (ByteBuffer)null);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_TEXTURE_2D, depthTarget, 0);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "shadow FBO complete");
    }

    private void begin(float cutoff) {
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo); GL46C.glViewport(0, 0, W, H);
        GL46C.glUseProgram(program); GL46C.glUniform1f(GL46C.glGetUniformLocation(program, "AlphaCutoff"), cutoff);
        GL46C.glBindVertexArray(vao); GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
        RenderSystem.bindTexture(texture); RenderSystem.depthMask(true); RenderSystem.enableCull();
        GL46C.glColorMask(true, true, true, true);
        GL46C.glClearColor(.03f, .07f, .11f, 0); GL46C.glClearDepth(1);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
    }

    private float alpha(int id, float base) { return base * dynamicScale; }

    private void drawReference(float cutoff) {
        begin(cutoff); GL46C.glUseProgram(program);
        bindGeometry(false);
        GL46C.glUniform4f(GL46C.glGetUniformLocation(program, "ColorModulator"), 1, 1, 1, 1);
        if (opaque) GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_SWIZZLE_A, GL46C.GL_ONE);
        try {
            for (int row = 0; row < RUNS; row++) {
                int materialId = submeshes.getInt(row * 20);
                float base = submeshes.getFloat(row * 20 + 12);
                MaterialRenderState state = new MaterialRenderState();
                state.resolve(materials[materialId], null, materialId, alpha(materialId, base));
                if (state.alpha < .001f) continue;
                float a = opaque ? 1 : state.alpha;
                if (iris) GL46C.glVertexAttrib4f(2, state.color[0], state.color[1], state.color[2], a);
                else GL46C.glUniform4f(GL46C.glGetUniformLocation(program, "MaterialColor"), state.color[0], state.color[1], state.color[2], a);
                if (submeshes.get(row * 20 + 17) != 0) RenderSystem.disableCull(); else RenderSystem.enableCull();
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
                GL46C.glDrawElements(GL46C.GL_TRIANGLES, submeshes.getInt(row * 20 + 8), GL46C.GL_UNSIGNED_INT,
                        (long)submeshes.getInt(row * 20 + 4) * Integer.BYTES);
            }
        } finally { GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_SWIZZLE_A, GL46C.GL_ALPHA); }
    }

    private void drawProduction(float cutoff) {
        begin(cutoff);
        bindGeometry(gpuDraw);
        GL46C.glUniform4f(GL46C.glGetUniformLocation(program, "ColorModulator"), 1, 1, 1, .7f);
        for (int row = 0; row < RUNS; row++) {
            int materialId = submeshes.getInt(row * 20);
            // Separate calls make the dynamic alpha resolver vary exactly as it does per frame.
            ByteBuffer one = bytes(20);
            ByteBuffer source = submeshes.duplicate().position(row * 20).limit(row * 20 + 20);
            one.put(source).flip();
            SubMeshDrawHelper.draw(one, 1, Integer.BYTES, GL46C.GL_UNSIGNED_INT, i -> texture,
                    (i, base) -> alpha(i, base), materials, null, true, ignored -> {});
        }
        check(GL46C.glGetTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_SWIZZLE_A) == GL46C.GL_ALPHA, "texture alpha restored");
    }

    private record Pixels(ByteBuffer rgba, FloatBuffer depth) {}
    private Pixels read() {
        ByteBuffer rgba = bytes(W * H * 4); FloatBuffer depth = bytes(W * H * Float.BYTES).asFloatBuffer();
        GL46C.glReadPixels(0, 0, W, H, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, rgba);
        GL46C.glReadPixels(0, 0, W, H, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, depth);
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL error after shadow draw");
        return new Pixels(rgba, depth);
    }

    private boolean compare(String label, Pixels reference, Pixels actual) {
        int changedColor = 0, changedDepth = 0, maxColor = 0;
        for (int i = 0; i < reference.rgba.capacity(); i++) {
            int delta = Math.abs((reference.rgba.get(i) & 255) - (actual.rgba.get(i) & 255));
            maxColor = Math.max(maxColor, delta); if (delta > 1) changedColor++;
        }
        for (int i = 0; i < reference.depth.capacity(); i++)
            if (Math.abs(reference.depth.get(i) - actual.depth.get(i)) > 1e-6f) changedDepth++;
        boolean pass = changedColor == 0 && changedDepth == 0;
        System.out.printf("%s %s maxColor=%d changedColor=%d changedDepth=%d%n",
                pass ? "PASS" : "FAIL", label, maxColor, changedColor, changedDepth);
        return pass;
    }

    private void run() {
        init(); boolean allPass = true;
        for (boolean force : new boolean[]{false, true}) {
            opaque = force;
            ConfigManager.init(new ConfigManager.IConfigProvider() {
                @Override public boolean isShadowOpaque() { return opaque; }
                @Override public boolean isOpenGLLightingEnabled() { return true; }
                @Override public int getModelPoolMaxCount() { return 20; }
                @Override public boolean isMMDShaderEnabled() { return false; }
                @Override public String getMobModelReplacement(String id) { return ""; }
            });
            for (int pose = 0; pose < 2; pose++) {
                pose(pose == 0 ? 0 : .08f, pose);
                for (float cutoff : CUTOFFS) {
                    Pixels reference = null;
                    for (int dynamic = 0; dynamic < DYNAMIC_SCALE.length; dynamic++) {
                        dynamicScale = DYNAMIC_SCALE[dynamic];
                        drawReference(cutoff); reference = read();
                        if (pose == 0) {
                            // An explicit oracle assertion keeps an all-empty reference from passing.
                            int y = H / RUNS / 2;
                            int solid = (int)((-.96f + .92f / 6 + 1) * W / 2);
                            int hole = (int)((-.96f + .92f * 5 / 6 + 1) * W / 2);
                            check(reference.depth.get(y * W + solid) < 1 || (!opaque && !bsl && dynamicScale < cutoff), "opaque texel coverage");
                            check((reference.depth.get(y * W + hole) < 1) == opaque, "zero alpha hole coverage");
                            check(reference.depth.get((H * 6 / RUNS + y) * W + solid) == 1, "hidden material excluded");
                        }
                        for (boolean compute : new boolean[]{false, true}) {
                            gpuDraw = compute;
                            drawProduction(cutoff);
                            allPass &= compare("bsl=" + bsl + " iris=" + iris + " gpu=" + compute + " opaque=" + opaque + " pose=" + pose
                            + " cutoff=" + cutoff + " dynamicScale=" + dynamicScale, reference, read());
                        }
                    }
                }
            }
        }
        checkExceptionAndWorldIsolation();
        ConfigManager.init(null);
        check(GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING) == ebo, "helper restored EBO");
        check(GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK), "helper restored depth write mask");
        check(GL46C.glIsEnabled(GL46C.GL_CULL_FACE), "helper restored default cull state");
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "final GL error");
        GL46C.glDeleteProgram(program); GL46C.glDeleteBuffers(vbo); GL46C.glDeleteBuffers(ebo); GL46C.glDeleteVertexArrays(vao);
        GL46C.glDeleteTextures(texture); GL46C.glDeleteTextures(colorTarget); GL46C.glDeleteTextures(depthTarget); GL46C.glDeleteFramebuffers(fbo);
        for (int id : gpu) GL46C.glDeleteBuffers(id);
        skinning.cleanup();
        if (!allPass) throw new AssertionError("production shadow-alpha rendering differs from the standard cutout reference");
        System.out.println("ShadowAlphaGlProbe PASS: " + (bsl ? "real BSL fragment with Iris alpha test" : "synthetic pixel alpha")
                + "; CPU/GPU production color/depth match");
    }

    private void checkExceptionAndWorldIsolation() {
        begin(.1f); bindGeometry(true);
        RenderSystem.depthMask(false);
        RuntimeException sentinel = new RuntimeException("probe callback");
        try {
            SubMeshDrawHelper.draw(submeshes, RUNS, 4, GL46C.GL_UNSIGNED_INT, i -> texture,
                    this::alpha, materials, null, true, ignored -> { throw sentinel; });
            throw new AssertionError("expected exception");
        } catch (RuntimeException e) { check(e == sentinel, "exception preserved"); }
        check(!GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK), "exception restored depth mask");
        check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM) == program, "exception restored program");
        check(GL46C.glGetTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_SWIZZLE_A) == GL46C.GL_ALPHA, "exception restored alpha swizzle");
        RenderSystem.depthMask(true);
        if (bsl) return;
        SubMeshDrawHelper.draw(submeshes, RUNS, 4, GL46C.GL_UNSIGNED_INT, i -> texture,
                this::alpha, materials, null, false, id -> {
                    check(GL46C.glGetTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_SWIZZLE_A) == GL46C.GL_ALPHA,
                            "opaque-shadow setting leaves world texture alpha intact");
                    float[] color = new float[4];
                    if (iris) GL46C.glGetVertexAttribfv(2, GL46C.GL_CURRENT_VERTEX_ATTRIB, color);
                    else GL46C.glGetUniformfv(program, GL46C.glGetUniformLocation(program, "MaterialColor"), color);
                    check(Math.abs(color[3] - MATERIAL_ALPHA[id] * dynamicScale) < 1e-6f,
                            "opaque-shadow setting leaves world material alpha intact");
                });
        System.out.println("PASS exception restoration and world alpha isolation");
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) bslFragment = BslShadowFragment.load(args[0]);
        check(GLFW.glfwInit(), "GLFW init"); GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4); GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE);
        long window = GLFW.glfwCreateWindow(W, H, "shadow alpha probe", 0, 0); check(window != 0, "GL window");
        try { GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            RenderSystem.initRenderThread();
            for (boolean iris : new boolean[]{false, true}) {
                ShadowAlphaGlProbe probe = new ShadowAlphaGlProbe(); probe.iris = iris; probe.run();
            }
            if (bslFragment != null) {
                ShadowAlphaGlProbe probe = new ShadowAlphaGlProbe(); probe.iris = true; probe.bsl = true; probe.run();
            }
        }
        finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }
}
