package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import com.shiroha.mmdskin.texture.runtime.TextureAlphaCoverage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** 用独立远近顺序核对混合贴图、材质透明度和真正的不同几何。 */
public final class AlphaOrderGlProbe {
    private static final int SIZE = 64;
    private final ModelMaterial[] materials = new ModelMaterial[3];
    private final ByteBuffer runs = BatchGlProbe.buffer(60);
    private final ByteBuffer positions = BatchGlProbe.buffer(12 * 12);
    private final ByteBuffer uvs = BatchGlProbe.buffer(12 * 8);
    private final FloatBuffer view = BatchGlProbe.buffer(64).asFloatBuffer();
    private final FloatBuffer projection = BatchGlProbe.buffer(64).asFloatBuffer();
    private int vao, vbo, ebo, positionBuffer;

    private void init() {
        new Matrix4f().get(view);
        // 相机前方为负 Z；投影反转 Z，使远层通过正确的深度测试。
        new Matrix4f().scaling(1, 1, -1).get(projection);
        vao = GL46C.glGenVertexArrays(); GL46C.glBindVertexArray(vao);
        ByteBuffer vertices = BatchGlProbe.buffer(12 * 36);
        ByteBuffer indices = BatchGlProbe.buffer(18 * 4);
        for (int layer = 0; layer < 3; layer++) {
            float left = layer == 0 ? -.9f : layer == 1 ? -.5f : -.95f;
            float right = layer == 0 ? .5f : layer == 1 ? .9f : .95f;
            float z = -.2f - .3f * layer;
            float[][] corners = {{left, -.8f, 0, 0}, {right, -.8f, 1, 0},
                    {right, .8f, 1, 1}, {left, .8f, 0, 1}};
            for (float[] c : corners) {
                positions.putFloat(c[0]).putFloat(c[1]).putFloat(z);
                uvs.putFloat(c[2]).putFloat(c[3]);
                vertices.putFloat(c[0]).putFloat(c[1]).putFloat(z)
                        .putFloat(0).putFloat(0).putFloat(1)
                        .putFloat(c[2]).putFloat(c[3]).putFloat(0);
            }
            for (int v : new int[]{0, 1, 2, 0, 2, 3}) indices.putInt(layer * 4 + v);
            var material = materials[layer] = new ModelMaterial();
            material.hasAlpha = layer == 0; material.hasTranslucentPixels = layer == 0;
            ByteBuffer pixels = BatchGlProbe.buffer(12);
            for (int x = 0; x < 3; x++) pixels.put((byte)(layer == 0 ? 230 : 30))
                    .put((byte)(layer == 1 ? 230 : 30)).put((byte)(layer == 2 ? 230 : 30))
                    .put((byte)(layer == 0 ? new int[]{255, 128, 0}[x] : 255));
            pixels.flip(); material.alphaCoverage = TextureAlphaCoverage.fromRgba(pixels, 3, 1);
            material.tex = GL46C.glGenTextures(); RenderSystem.bindTexture(material.tex);
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, 3, 1, 0,
                    GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, pixels);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
            int b = layer * 20;
            runs.putInt(b, layer).putInt(b + 4, layer * 6).putInt(b + 8, 6)
                    .putFloat(b + 12, layer == 1 ? .9f : 1)
                    .put(b + 16, (byte)1).put(b + 17, (byte)1);
        }
        positions.flip(); uvs.flip(); vertices.flip(); indices.flip();
        vbo = GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, vbo);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, vertices, GL46C.GL_STATIC_DRAW);
        for (int i = 0; i < 4; i++) {
            GL46C.glEnableVertexAttribArray(i);
            GL46C.glVertexAttribPointer(i, i < 2 ? 3 : i == 2 ? 2 : 1, GL46C.GL_FLOAT,
                    false, 36, i == 0 ? 0 : i == 1 ? 12 : i == 2 ? 24 : 32);
        }
        ebo = GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, indices, GL46C.GL_STATIC_DRAW);
        positionBuffer = GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, positionBuffer);
        GL46C.glBufferData(GL46C.GL_COPY_WRITE_BUFFER, positions, GL46C.GL_STATIC_DRAW);
        GL46C.glViewport(0, 0, SIZE, SIZE);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL46C.GL_LEQUAL);
    }

    private void begin(ToonShaderCpu shader) {
        BatchGlProbe.setup(shader); shader.setProjectionMatrix(projection); shader.setEyeEmission(0);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        RenderSystem.depthMask(true); RenderSystem.disableCull();
        GL46C.glClearColor(.05f, .08f, .12f, 0);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
    }

    private record Image(ByteBuffer colors, FloatBuffer depth) {}

    private Image read() {
        ByteBuffer colors = BatchGlProbe.buffer(SIZE * SIZE * 4);
        FloatBuffer depth = BatchGlProbe.buffer(SIZE * SIZE * 4).asFloatBuffer();
        GL46C.glReadPixels(0, 0, SIZE, SIZE, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, colors);
        GL46C.glReadPixels(0, 0, SIZE, SIZE, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, depth);
        int error = GL46C.glGetError();
        BatchGlProbe.check(error == GL46C.GL_NO_ERROR, "GL error after alpha/order draw: 0x" + Integer.toHexString(error));
        return new Image(colors, depth);
    }

    private Image reference(ToonShaderCpu shader) {
        begin(shader);
        try (var binding = new MaterialShaderBinding()) {
            for (int pass = 0; pass < 3; pass++) {
                RenderSystem.depthMask(pass != 1);
                try (var seal = pass == 2 ? new MaterialDepthSeal() : null) {
                    // 显式指定远到近，不调用生产排序器或批量计划。
                    for (int id : pass == 1 ? new int[]{2, 1, 0} : new int[]{0, 1, 2}) {
                        float alpha = runs.getFloat(id * 20 + 12);
                        if (pass == 0 && alpha < .999f) continue;
                        if (pass > 0 && id == 2) continue;
                        var state = new MaterialRenderState(); state.resolve(materials[id], null, id, alpha);
                        binding.apply(state, pass == 2 ? 0 : pass + 1);
                        RenderSystem.bindTexture(materials[id].tex);
                        GL46C.glDrawElements(GL46C.GL_TRIANGLES, 6, GL46C.GL_UNSIGNED_INT, id * 24L);
                    }
                }
            }
        }
        return read();
    }

    private Image actual(ToonShaderCpu shader, boolean gpu, boolean lateFallback) {
        begin(shader);
        try (var order = new TranslucentDrawOrder()) {
            var input = gpu ? TranslucentDrawOrder.Inputs.gpu(positionBuffer, 12, view, projection,
                    1, false, uvs, 0, 0, false)
                    : TranslucentDrawOrder.Inputs.cpu(positions, 0, 12, view, projection, 1, false, uvs, 0, false);
            boolean[] changed = {false};
            SubMeshDrawHelper.draw(runs, 3, 4, GL46C.GL_UNSIGNED_INT, i -> materials[i].tex,
                    (i, a) -> a, materials, null, false, i -> {
                        shader.setEyeMaterialMask(0);
                        if (lateFallback && !changed[0]) {
                            BatchGlProbe.check(order.drawCount() == 0, "late fallback started with GPU preparation");
                            System.setProperty("mmdskin.translucentBatch", "false"); changed[0] = true;
                        }
                    }, order, input);
            BatchGlProbe.check(GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING) == ebo, "source EBO restored");
            if (gpu && !lateFallback) BatchGlProbe.check(order.drawCount() == 0, "GPU indirect path exercised");
            if (lateFallback) BatchGlProbe.check(changed[0], "late fallback callback exercised");
        } finally { System.clearProperty("mmdskin.translucentBatch"); }
        return read();
    }

    private int compare(String label, Image expected, Image actual) {
        int max = 0, changed = 0, depthChanged = 0;
        for (int i = 0; i < expected.colors.capacity(); i++) {
            int delta = Math.abs((expected.colors.get(i) & 255) - (actual.colors.get(i) & 255));
            max = Math.max(max, delta); if (delta > 1) changed++;
        }
        for (int i = 0; i < expected.depth.capacity(); i++)
            if (Math.abs(expected.depth.get(i) - actual.depth.get(i)) > 1e-6f) depthChanged++;
        boolean ok = max <= 1 && depthChanged == 0;
        System.out.println((ok ? "PASS " : "FAIL ") + label + ": maxColor=" + max
                + ", changedChannels=" + changed + ", changedDepth=" + depthChanged);
        return ok ? 0 : 1;
    }

    private void run() {
        RenderSystem.initRenderThread();
        System.setProperty("mmdskin.translucentBatch", "false");
        var compatibility = new ToonShaderCpu(); BatchGlProbe.check(compatibility.init(), "compatibility shader");
        System.clearProperty("mmdskin.translucentBatch");
        var batch = new ToonShaderCpu(); BatchGlProbe.check(batch.init(), "batch shader");
        init();
        try {
            Image expected = reference(compatibility);
            int failures = compare("CPU compatibility sorted EBO", expected, actual(compatibility, false, false));
            failures += compare("CPU batch sorted EBO/material ownership", expected, actual(batch, false, false));
            failures += compare("GPU indirect sorted EBO/material ownership", expected, actual(batch, true, false));
            failures += compare("GPU late fallback preserves sorting", expected, actual(batch, true, true));
            BatchGlProbe.check(failures == 0, failures + " alpha/order paths differ from manual far-to-near oracle");
            System.out.println("AlphaOrderGlProbe PASS: mixed 255/128/0 atlas, material alpha .9, distinct geometry, color/depth oracle");
        } finally {
            compatibility.cleanup(); batch.cleanup();
            for (var m : materials) GL46C.glDeleteTextures(m.tex);
            GL46C.glDeleteBuffers(new int[]{vbo, ebo, positionBuffer}); GL46C.glDeleteVertexArrays(vao);
        }
    }

    public static void main(String[] args) {
        BatchGlProbe.check(GLFW.glfwInit(), "GLFW"); GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4); GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_DEBUG_CONTEXT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "alpha/order regression", 0, 0);
        BatchGlProbe.check(window != 0, "GL window");
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            try (var debug = org.lwjgl.opengl.GLUtil.setupDebugMessageCallback()) {
                // 保留错误报告，关闭驱动的逐缓冲通知。
                GL46C.glDebugMessageControl(GL46C.GL_DONT_CARE, GL46C.GL_DONT_CARE,
                        GL46C.GL_DEBUG_SEVERITY_NOTIFICATION, (java.nio.IntBuffer)null, false);
                new AlphaOrderGlProbe().run();
            }
        }
        finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }
}
