package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.config.AbstractMmdSkinConfig;
import com.shiroha.mmdskin.config.ConfigData;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** Toon 禁用材质透明的生产绘制回读。 */
public final class ToonTransparencyGlProbe {
    private static final int SIZE = 64, MATERIALS = 4, SUBMESHES = 4;

    private record Geometry(ByteBuffer positions, ByteBuffer uvs, int vertexBuffer, int positionBuffer,
                            TranslucentDrawOrder.Inputs input) {}

    private static final class ProbeConfig extends AbstractMmdSkinConfig {
        ProbeConfig(ConfigData data) { super(data); }
        @Override public String getMobModelReplacement(String entityTypeId) { return ""; }
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    }

    private static void check(boolean condition, String message) {
        BatchGlProbe.check(condition, message);
    }

    private static int texture(int[] alpha) {
        int id = GL46C.glGenTextures();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
        ByteBuffer pixels = buffer(alpha.length * 4);
        for (int value : alpha) pixels.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) value);
        pixels.flip();
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, alpha.length, 1, 0,
                GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, pixels);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, GL46C.GL_CLAMP_TO_EDGE);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_T, GL46C.GL_CLAMP_TO_EDGE);
        return id;
    }

    private static Geometry geometry(int vao, int ebo) {
        GL46C.glBindVertexArray(vao);
        ByteBuffer vertices = buffer(SUBMESHES * 4 * 9 * Float.BYTES);
        ByteBuffer positions = buffer(SUBMESHES * 4 * 3 * Float.BYTES);
        ByteBuffer uvs = buffer(SUBMESHES * 4 * 2 * Float.BYTES);
        float[] depths = {-.9f, -.6f, 0.0f, .6f};
        float[][] xy = {{-.8f, -.8f}, {.8f, -.8f}, {.8f, .8f}, {-.8f, .8f}};
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (float z : depths) {
            for (int i = 0; i < 4; i++) {
                vertices.putFloat(xy[i][0]).putFloat(xy[i][1]).putFloat(z)
                        .putFloat(0).putFloat(0).putFloat(1).putFloat(uv[i][0]).putFloat(uv[i][1]).putFloat(0);
                positions.putFloat(xy[i][0]).putFloat(xy[i][1]).putFloat(z);
                uvs.putFloat(uv[i][0]).putFloat(uv[i][1]);
            }
        }
        vertices.flip(); positions.flip(); uvs.flip();
        int vbo = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, vbo);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, vertices, GL46C.GL_STATIC_DRAW);
        for (int i = 0; i < 4; i++) {
            GL46C.glEnableVertexAttribArray(i);
            GL46C.glVertexAttribPointer(i, i == 0 || i == 1 ? 3 : i == 2 ? 2 : 1,
                    GL46C.GL_FLOAT, false, 9 * Float.BYTES, i == 0 ? 0 : i == 1 ? 12 : i == 2 ? 24 : 32);
        }
        ByteBuffer indices = buffer(SUBMESHES * 6 * Integer.BYTES);
        for (int mesh = 0; mesh < SUBMESHES; mesh++) {
            int baseVertex = mesh * 4, baseIndex = mesh * 6;
            indices.putInt(baseVertex).putInt(baseVertex + 1).putInt(baseVertex + 2)
                    .putInt(baseVertex + 2).putInt(baseVertex + 3).putInt(baseVertex);
        }
        indices.flip();
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, indices, GL46C.GL_STATIC_DRAW);
        int positionBuffer = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, positionBuffer);
        GL46C.glBufferData(GL46C.GL_COPY_WRITE_BUFFER, positions, GL46C.GL_STATIC_DRAW);
        FloatBuffer identity = buffer(16 * Float.BYTES).asFloatBuffer();
        new Matrix4f().get(identity);
        return new Geometry(positions, uvs, vbo, positionBuffer,
                TranslucentDrawOrder.Inputs.cpu(positions, 0, SUBMESHES * 4, identity, null, 1,
                        false, uvs, 0, false));
    }

    private static TranslucentDrawOrder.Inputs input(Geometry geometry, boolean gpu) {
        if (!gpu) return geometry.input();
        FloatBuffer identity = buffer(16 * Float.BYTES).asFloatBuffer();
        new Matrix4f().get(identity);
        return TranslucentDrawOrder.Inputs.gpu(geometry.positionBuffer(), SUBMESHES * 4, identity,
                null, 1, false, geometry.uvs(), 0, 0, false);
    }

    private static ModelMaterial[] materials(int[] textures) {
        ModelMaterial[] result = new ModelMaterial[MATERIALS];
        float[][] colors = {{.8f, .65f, .1f}, {.9f, .08f, .04f}, {.05f, .85f, .12f}, {.05f, .12f, .9f}};
        for (int i = 0; i < result.length; i++) {
            ModelMaterial material = result[i] = new ModelMaterial();
            material.name = i == 1 ? "後髪" : "probe-" + i;
            material.tex = textures[i];
            material.hasAlpha = true;
            material.hasTranslucentPixels = i < 3;
            material.diffuse = new float[]{0, 0, 0, 1};
            material.ambient = colors[i];
            material.specular = new float[]{0, 0, 0};
        }
        return result;
    }

    private static ByteBuffer render(ToonShaderCpu shader, ConfigData config, Geometry geometry,
                                     int ebo, int[] textures, boolean gpu, int[] drawCounts) {
        BatchGlProbe.setup(shader);
        shader.setAlphaCutoff(config.toonAlphaCutoff);
        shader.setHairAlphaCutoff(config.toonHairAlphaCutoff);
        RenderSystem.depthMask(true);
        GL46C.glClearColor(.05f, .05f, .05f, .1f);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
        ModelMaterial[] materials = materials(textures);
        ByteBuffer runs = geometryRuns();
        int[] submitted = new int[MATERIALS];
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        RenderSystem.depthMask(false);
        try (TranslucentDrawOrder order = new TranslucentDrawOrder()) {
            SubMeshDrawHelper.draw(runs, SUBMESHES, 4, GL46C.GL_UNSIGNED_INT, id -> textures[id], (id, alpha) -> alpha,
                    materials, null, false, id -> submitted[id]++, order, input(geometry, gpu));
            check(GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING) == ebo, "EBO restored " + (gpu ? "GPU" : "CPU"));
            if (config.toonDisableTransparency) {
                check(order.indexBuffer() == 0 && order.candidateBuildCount() == 0,
                        "disabled transparency skips translucent preparation");
            }
        }
        check(!GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK), "depth mask restored " + (gpu ? "GPU" : "CPU"));
        check(submitted[0] == 0, "zero-alpha material skipped before draw callback");
        drawCounts[0] = submitted[1] + submitted[2] + submitted[3];
        RenderSystem.depthMask(true);
        ByteBuffer pixels = buffer(SIZE * SIZE * 4);
        GL46C.glReadPixels(0, 0, SIZE, SIZE, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, pixels);
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL error after Toon render");
        return pixels;
    }

    private static ByteBuffer geometryRuns() {
        ByteBuffer runs = buffer(SUBMESHES * 20);
        float[] alpha = {0, .4f, .4f, 1};
        for (int i = 0; i < SUBMESHES; i++) {
            int row = i * 20;
            runs.putInt(row, i).putInt(row + 4, i * 6).putInt(row + 8, 6).putFloat(row + 12, alpha[i])
                    .put(row + 16, (byte) 1).put(row + 17, (byte) 1);
        }
        return runs;
    }

    private static int channel(ByteBuffer image, int x, int y, int c) {
        return image.get((y * SIZE + x) * 4 + c) & 255;
    }

    private static void compare(ByteBuffer a, ByteBuffer b, String message) {
        int max = 0;
        for (int i = 0; i < a.capacity(); i++) max = Math.max(max, Math.abs((a.get(i) & 255) - (b.get(i) & 255)));
        check(max <= 1, message + " max channel difference=" + max);
    }

    private static void verifyUniforms(ToonShaderCpu shader, ConfigData config, boolean expected) {
        config.toonDisableTransparency = expected;
        shader.useMain();
        int main = GL46C.glGetUniformLocation(shader.getMainProgram(), "DisableTransparency");
        check(main >= 0 && GL46C.glGetUniformi(shader.getMainProgram(), main) == (expected ? 1 : 0), "main transparency uniform");
        shader.useOutline();
        int outline = GL46C.glGetUniformLocation(shader.getOutlineProgram(), "DisableTransparency");
        check(outline >= 0 && GL46C.glGetUniformi(shader.getOutlineProgram(), outline) == (expected ? 1 : 0),
                "outline transparency uniform");
    }

    public static void main(String[] args) {
        BatchGlProbe.check(GLFW.glfwInit(), "GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, 24);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "toon transparency probe", 0, 0);
        BatchGlProbe.check(window != 0, "GL window");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            run(false);
            run(true);
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void run(boolean batch) {
        System.setProperty("mmdskin.translucentBatch", Boolean.toString(batch));
        ConfigData config = new ConfigData();
        config.toonRenderingEnabled = true;
        ConfigManager.init(new ProbeConfig(config));
        ToonShaderCpu shader = new ToonShaderCpu();
        check(shader.init(), "production Toon shader");
        int vao = GL46C.glGenVertexArrays(), ebo = GL46C.glGenBuffers();
        GL46C.glBindVertexArray(vao);
        Geometry geometry = geometry(vao, ebo);
        int[] textures = {texture(new int[]{255}), texture(new int[]{0, 128, 255}),
                texture(new int[]{128}), texture(new int[]{255})};
        GL46C.glViewport(0, 0, SIZE, SIZE);
        GL46C.glEnable(GL46C.GL_DEPTH_TEST);
        GL46C.glDepthFunc(GL46C.GL_LEQUAL);
        GL46C.glEnable(GL46C.GL_BLEND);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
        GL46C.glBindSampler(0, 0);
        RenderSystem.disableCull(); RenderSystem.enableCull();
        RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
        int[] draws = {0};
        try {
            ByteBuffer[] off = new ByteBuffer[2], on = new ByteBuffer[2], restored = new ByteBuffer[2];
            for (int path = 0; path < 2; path++) {
                boolean gpu = path == 1;
                verifyUniforms(shader, config, false);
                off[path] = render(shader, config, geometry, ebo, textures, gpu, draws);
                verifyUniforms(shader, config, true);
                on[path] = render(shader, config, geometry, ebo, textures, gpu, draws);
                verifyUniforms(shader, config, false);
                restored[path] = render(shader, config, geometry, ebo, textures, gpu, draws);
                compare(off[path], restored[path], "switch off restores original " + (gpu ? "GPU" : "CPU") + " image");
                check(channel(on[path], 32, 32, 3) >= 254, "partial alpha becomes opaque " + (gpu ? "GPU" : "CPU"));
                check(channel(on[path], 15, 32, 3) >= 254, "transparent hole reveals opaque rear material");
                check(channel(on[path], 15, 32, 1) > channel(on[path], 15, 32, 0), "transparent hole preserves rear color");
                check(channel(on[path], 32, 32, 0) > channel(on[path], 32, 32, 2), "front opaque layer occludes rear layer");
                verifyCutoffs(shader, config, geometry, ebo, textures, gpu, draws, off[path]);
            }
            compare(off[0], off[1], "CPU/GPU alpha rendering parity");
            compare(on[0], on[1], "CPU/GPU disabled-transparency parity");
            System.out.println("PASS ToonTransparencyGlProbe: batch=" + batch + "; CPU/GPU inputs, independent hair/global cutoffs, opaque output, holes, depth/EBO state, uniforms; draws=" + draws[0]);
        } finally {
            shader.cleanup();
            GL46C.glDeleteTextures(textures);
            GL46C.glDeleteBuffers(geometry.vertexBuffer());
            GL46C.glDeleteBuffers(geometry.positionBuffer());
            GL46C.glDeleteBuffers(ebo);
            GL46C.glDeleteVertexArrays(vao);
            ConfigManager.init(null);
            System.clearProperty("mmdskin.translucentBatch");
        }
    }

    /** 分别裁掉前层发片和中层衣料，核对独立阈值及开关恢复。 */
    private static void verifyCutoffs(ToonShaderCpu shader, ConfigData config, Geometry geometry,
                                      int ebo, int[] textures, boolean gpu, int[] draws, ByteBuffer baseline) {
        config.toonHairAlphaCutoff = .5f;
        ByteBuffer noHair = render(shader, config, geometry, ebo, textures, gpu, draws);
        check(channel(noHair, 32, 32, 0) < channel(baseline, 32, 32, 0), "hair cutoff removes red front layer");
        check(channel(noHair, 32, 32, 1) > channel(noHair, 32, 32, 0), "hair cutoff preserves green cloth");
        config.toonHairAlphaCutoff = .001f;
        config.toonAlphaCutoff = .5f;
        ByteBuffer noCloth = render(shader, config, geometry, ebo, textures, gpu, draws);
        check(channel(noCloth, 32, 32, 1) < channel(baseline, 32, 32, 1), "global cutoff removes green cloth");
        check(channel(noCloth, 32, 32, 0) > channel(noHair, 32, 32, 0), "global cutoff preserves independent hair");
        config.toonHairAlphaCutoff = .5f;
        ByteBuffer rearOnly = render(shader, config, geometry, ebo, textures, gpu, draws);
        check(channel(rearOnly, 32, 32, 2) > channel(rearOnly, 32, 32, 0), "both cutoffs reveal blue rear");
        config.toonDisableTransparency = true;
        compare(rearOnly, render(shader, config, geometry, ebo, textures, gpu, draws), "cutoff runs before force opaque");
        config.toonDisableTransparency = false;
        config.toonAlphaCutoff = config.toonHairAlphaCutoff = .001f;
        compare(baseline, render(shader, config, geometry, ebo, textures, gpu, draws), "reset cutoffs restores image");
    }
}
