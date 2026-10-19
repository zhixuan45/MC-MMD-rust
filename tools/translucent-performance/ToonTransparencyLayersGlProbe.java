package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

/** 两个相交透明面逐像素前后翻转的 Toon peel 回读。 */
public final class ToonTransparencyLayersGlProbe {
    public static ToonOutputProfile profileOverride;
    private static final int SIZE = 320;
    private static final float ALPHA = .5f;
    private static final float[][] XY = {{-.9f, -.85f}, {.9f, -.85f}, {0, .9f}};

    private record Image(ByteBuffer color, FloatBuffer depth) {}
    private record Geometry(ByteBuffer positions, ByteBuffer uvs, int vertexBuffer, int positionBuffer,
                            TranslucentDrawOrder.Inputs cpu) {}
    private record Target(int framebuffer, int colorTexture, int depthTexture) {}

    private static ByteBuffer bytes(int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    }

    private static void check(boolean value, String message) { BatchGlProbe.check(value, message); }

    private static void setupShader(ToonShaderCpu shader) {
        BatchGlProbe.setup(shader);
        shader.setEyeEmission(0);
        shader.setSpecular(64, 0);
        shader.setRimLight(2, 0);
    }

    private static Geometry geometry(int vao, int ebo) {
        return geometry(vao, ebo, 2, false);
    }

    private static Geometry geometry(int vao, int ebo, int layerCount) {
        return geometry(vao, ebo, layerCount, false);
    }

    private static Geometry geometry(int vao, int ebo, int layerCount, boolean coverageCase) {
        int vertexCount = layerCount * 3;
        ByteBuffer vertices = bytes(vertexCount * 9 * Float.BYTES), positions = bytes(vertexCount * 3 * Float.BYTES);
        ByteBuffer uvs = bytes(vertexCount * 2 * Float.BYTES), indices = bytes(vertexCount * Integer.BYTES);
        for (int layer = 0; layer < layerCount; layer++) {
            for (int i = 0; i < 3; i++) {
                float z = coverageCase ? layer == 1 ? (i == 0 ? -.55f : i == 1 ? .55f : 0) : 0
                        : layer == 2 ? .8f : (i == 0 ? -.55f : i == 1 ? .55f : 0) * (layer == 0 ? 1 : -1);
                vertices.putFloat(XY[i][0]).putFloat(XY[i][1]).putFloat(z)
                        .putFloat(0).putFloat(0).putFloat(1).putFloat(i == 1 ? 1 : 0).putFloat(i == 2 ? 1 : 0).putFloat(0);
                positions.putFloat(XY[i][0]).putFloat(XY[i][1]).putFloat(z);
                uvs.putFloat(i == 1 ? 1 : 0).putFloat(i == 2 ? 1 : 0);
                indices.putInt(layer * 3 + i);
            }
        }
        vertices.flip(); positions.flip(); uvs.flip(); indices.flip();
        GL46C.glBindVertexArray(vao);
        int vbo = GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, vbo);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, vertices, GL46C.GL_STATIC_DRAW);
        for (int i = 0; i < 4; i++) {
            GL46C.glEnableVertexAttribArray(i);
            GL46C.glVertexAttribPointer(i, i < 2 ? 3 : i == 2 ? 2 : 1, GL46C.GL_FLOAT,
                    false, 36, i == 0 ? 0 : i == 1 ? 12 : i == 2 ? 24 : 32);
        }
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, indices, GL46C.GL_STATIC_DRAW);
        int positionBuffer = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, positionBuffer);
        GL46C.glBufferData(GL46C.GL_COPY_WRITE_BUFFER, positions, GL46C.GL_STATIC_DRAW);
        FloatBuffer view = bytes(64).asFloatBuffer(), projection = bytes(64).asFloatBuffer();
        new Matrix4f().get(view); new Matrix4f().get(projection);
        return new Geometry(positions, uvs, vbo, positionBuffer,
                TranslucentDrawOrder.Inputs.cpu(positions, 0, vertexCount, view, projection, layerCount, false, uvs, 0, false));
    }

    private static Target target() {
        int fbo = GL46C.glGenFramebuffers(), color = GL46C.glGenTextures(), depth = GL46C.glGenTextures();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, color);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, SIZE, SIZE, 0,
                GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, depth);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_DEPTH_COMPONENT24, SIZE, SIZE, 0,
                GL46C.GL_DEPTH_COMPONENT, GL46C.GL_UNSIGNED_INT, (ByteBuffer) null);
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0,
                GL46C.GL_TEXTURE_2D, color, 0);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                GL46C.GL_TEXTURE_2D, depth, 0);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0); GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "RGBA8/D24 FBO");
        return new Target(fbo, color, depth);
    }

    private static int texture(int red, int green, int blue, int alpha) {
        int id = GL46C.glGenTextures(); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
        ByteBuffer data = bytes(4).put((byte) red).put((byte) green).put((byte) blue).put((byte) alpha); data.flip();
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, 1, 1, 0,
                GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, data);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        return id;
    }

    private static ModelMaterial[] materials(int red, int green) { return materials(red, green, 0); }

    private static ModelMaterial[] materials(int red, int green, int blue) {
        ModelMaterial[] materials = new ModelMaterial[blue == 0 ? 2 : 3];
        for (int i = 0; i < materials.length; i++) {
            ModelMaterial material = materials[i] = new ModelMaterial();
            material.name = i == 0 ? "red-layer" : i == 1 ? "green-layer" : "blue-tail";
            material.tex = i == 0 ? red : i == 1 ? green : blue;
            material.hasAlpha = true; material.hasTranslucentPixels = true;
            material.diffuse = new float[]{1, 1, 1, 1}; material.ambient = new float[]{.5f, .5f, .5f};
            material.specular = new float[]{0, 0, 0};
        }
        return materials;
    }

    private static ModelMaterial[] coverageMaterials(int red, int green, int blue) {
        ModelMaterial[] result = materials(red, green, blue);
        result[2].hasAlpha = false;
        result[2].hasTranslucentPixels = false;
        return result;
    }

    private static ByteBuffer runs(boolean reverse) {
        ByteBuffer data = bytes(40);
        int first = reverse ? 1 : 0, second = 1 - first;
        data.putInt(0, first).putInt(4, first * 3).putInt(8, 3).putFloat(12, ALPHA).put(16, (byte) 1).put(17, (byte) 1);
        data.putInt(20, second).putInt(24, second * 3).putInt(28, 3).putFloat(32, ALPHA).put(36, (byte) 1).put(37, (byte) 1);
        return data;
    }

    private static ByteBuffer threeRuns() {
        ByteBuffer data = bytes(60);
        for (int i = 0; i < 3; i++)
            data.putInt(i * 20, i).putInt(i * 20 + 4, i * 3).putInt(i * 20 + 8, 3)
                    .putFloat(i * 20 + 12, ALPHA).put(i * 20 + 16, (byte) 1).put(i * 20 + 17, (byte) 1);
        return data;
    }

    private static ByteBuffer coverageRuns() {
        ByteBuffer data = threeRuns();
        data.putFloat(52, 1.0f);
        return data;
    }

    private static TranslucentDrawOrder.Inputs input(Geometry geometry, boolean gpu) {
        if (!gpu) return geometry.cpu();
        FloatBuffer view = bytes(64).asFloatBuffer(), projection = bytes(64).asFloatBuffer();
        new Matrix4f().get(view); new Matrix4f().get(projection);
        return TranslucentDrawOrder.Inputs.gpu(geometry.positionBuffer(), geometry.cpu().vertexCount(), view, projection,
                geometry.cpu().geometryRevision(),
                false, geometry.uvs(), 0, 0, false);
    }

    private static void clear(Target target) {
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, target.framebuffer());
        GL46C.glViewport(0, 0, SIZE, SIZE);
        GL46C.glClearColor(0, 0, 0, 0); RenderSystem.depthMask(true);
        GL46C.glClearDepth(1); GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
    }

    private static Image read() {
        ByteBuffer color = bytes(SIZE * SIZE * 4);
        FloatBuffer depth = bytes(SIZE * SIZE * 4).asFloatBuffer();
        GL46C.glReadPixels(0, 0, SIZE, SIZE, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, color);
        GL46C.glReadPixels(0, 0, SIZE, SIZE, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, depth);
        return new Image(color, depth);
    }

    private static void assertState(int fbo, int depthUnit, int coverageUnit, int hostTexture, int hostSampler,
                                    int depthFunc, boolean depthMask, boolean depthTest) {
        check(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == fbo, "draw FBO restored");
        check(GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING) == fbo, "read FBO restored");
        check(GL46C.glGetInteger(GL46C.GL_DEPTH_FUNC) == depthFunc, "depth function restored");
        check(GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK) == depthMask, "depth mask restored");
        check(GL46C.glIsEnabled(GL46C.GL_DEPTH_TEST) == depthTest, "depth test restored");
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + depthUnit);
        check(GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D) == hostTexture, "peel texture unit binding restored");
        check(GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, depthUnit) == hostSampler, "peel sampler binding restored");
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + coverageUnit);
        check(GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D) == hostTexture, "coverage texture unit binding restored");
        check(GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, coverageUnit) == hostSampler, "coverage sampler binding restored");
        GL46C.glActiveTexture(active);
    }

    private static int[] unitsForPeel(int program) {
        int depthLocation = GL46C.glGetUniformLocation(program, "ToonTransparencyDepth");
        int coverageLocation = GL46C.glGetUniformLocation(program, "ToonTransparencyCoverage");
        int[] active = {GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_IMAGE_UNITS)};
        boolean[] occupied = new boolean[active[0]];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1); var type = stack.mallocInt(1);
            for (int i = 0; i < GL46C.glGetProgrami(program, GL46C.GL_ACTIVE_UNIFORMS); i++) {
                String name = GL46C.glGetActiveUniform(program, i, size, type);
                if (type.get(0) != GL46C.GL_SAMPLER_2D || name == null) continue;
                for (int n = 0; n < Math.max(1, size.get(0)); n++) {
                    String entry = size.get(0) > 1 ? name.replace("[0]", "[" + n + "]") : name;
                    int location = GL46C.glGetUniformLocation(program, entry);
                    if (location < 0 || location == depthLocation || location == coverageLocation) continue;
                    int unit = GL46C.glGetUniformi(program, location);
                    if (unit >= 0 && unit < occupied.length) occupied[unit] = true;
                }
            }
        }
        int first = -1, second = -1;
        for (int i = occupied.length - 1; i >= 0; i--) if (!occupied[i]) {
            if (first < 0) first = i; else { second = i; break; }
        }
        check(second >= 0, "two free texture units for peel depth and coverage");
        return new int[]{first, second};
    }

    private static int[] opaqueSourceColor(ToonShaderCpu shader, ModelMaterial[] materials,
                                           int ebo, int id, int x, int y) {
        setupShader(shader);
        int program = shader.getMainProgram();
        int batch = GL46C.glGetUniformLocation(program, "MmdBatchEnabled");
        if (batch >= 0) GL46C.glUniform1i(batch, 0);
        clear(new Target(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING), 0, 0));
        GL46C.glDisable(GL46C.GL_BLEND); GL46C.glDisable(GL46C.GL_DEPTH_TEST);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        try (MaterialShaderBinding binding = new MaterialShaderBinding()) {
            MaterialRenderState state = new MaterialRenderState(); state.resolve(materials[id], null, id, 1);
            binding.apply(state, 0); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, materials[id].tex);
            GL46C.glDrawElements(GL46C.GL_TRIANGLES, 3, GL46C.GL_UNSIGNED_INT, id * 12L);
        }
        ByteBuffer image = bytes(SIZE * SIZE * 4);
        GL46C.glReadPixels(0, 0, SIZE, SIZE, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, image);
        int base = (y * SIZE + x) * 4;
        return new int[]{image.get(base) & 255, image.get(base + 1) & 255, image.get(base + 2) & 255};
    }

    private static int compareOracle(Image actual, int[] red, int[] green, boolean requireMatch) {
        int mismatches = 0, maxError = 0, checked = 0;
        for (int py = 8; py < SIZE - 8; py++) for (int px = 8; px < SIZE - 8; px++) {
            float x = 2.0f * (px + .5f) / SIZE - 1, y = 2.0f * (py + .5f) / SIZE - 1;
            float[] w = barycentric(x, y);
            if (w == null || Math.min(w[0], Math.min(w[1], w[2])) < .08f) continue;
            float redDepth = -.55f * w[0] + .55f * w[1];
            boolean redNear = redDepth < -redDepth;
            int[] near = redNear ? red : green, far = redNear ? green : red;
            int base = (py * SIZE + px) * 4;
            for (int c = 0; c < 3; c++) {
                int expected = Math.round(near[c] * .5f + far[c] * .25f);
                int error = Math.abs((actual.color().get(base + c) & 255) - expected);
                maxError = Math.max(maxError, error);
                if (error > 3) mismatches++;
            }
            int alphaError = Math.abs((actual.color().get(base + 3) & 255) - Math.round(255 * .75f));
            maxError = Math.max(maxError, alphaError);
            if (alphaError > 3) mismatches++;
            checked++;
        }
        System.out.println((requireMatch ? "oracle " : "legacy counterexample ") + "pixels=" + checked
                + ", mismatched channels=" + mismatches + ", max error=" + maxError);
        return mismatches;
    }

    private static int compareThreeLayerOracle(Image actual, int[] red, int[] green, int[] blue) {
        int mismatches = 0, checked = 0;
        for (int py = 8; py < SIZE - 8; py++) for (int px = 8; px < SIZE - 8; px++) {
            float x = 2.0f * (px + .5f) / SIZE - 1, y = 2.0f * (py + .5f) / SIZE - 1;
            float[] w = barycentric(x, y);
            if (w == null || Math.min(w[0], Math.min(w[1], w[2])) < .08f) continue;
            boolean redNear = (-.55f * w[0] + .55f * w[1]) < 0;
            int[] near = redNear ? red : green, middle = redNear ? green : red;
            int base = (py * SIZE + px) * 4;
            for (int c = 0; c < 3; c++)
                if (Math.abs((actual.color().get(base + c) & 255)
                        - Math.round(near[c] * .5f + middle[c] * .25f + blue[c] * .125f)) > 3) mismatches++;
            if (Math.abs((actual.color().get(base + 3) & 255) - Math.round(255 * .875f)) > 3) mismatches++;
            checked++;
        }
        System.out.println("two-layer tail oracle pixels=" + checked + ", mismatched channels=" + mismatches);
        return mismatches;
    }

    private static int compareCoverageOracle(Image actual, int[] red, int[] green, int[] blue) {
        int mismatches = 0, checkedFront = 0, checkedBack = 0;
        for (int py = 8; py < SIZE - 8; py++) for (int px = 8; px < SIZE - 8; px++) {
            float x = 2.0f * (px + .5f) / SIZE - 1, y = 2.0f * (py + .5f) / SIZE - 1;
            float[] w = barycentric(x, y);
            if (w == null || Math.min(w[0], Math.min(w[1], w[2])) < .08f || Math.abs(x) < .06f) continue;
            boolean greenFront = x < 0;
            int base = (py * SIZE + px) * 4;
            for (int c = 0; c < 3; c++) {
                int expected = greenFront ? Math.round(green[c] * .5f + red[c] * .25f + blue[c] * .25f)
                        : Math.round(red[c] * .5f + blue[c] * .5f);
                if (Math.abs((actual.color().get(base + c) & 255) - expected) > 3) mismatches++;
            }
            if ((actual.color().get(base + 3) & 255) < 252) mismatches++;
            if (greenFront) checkedFront++; else checkedBack++;
        }
        System.out.println("coverage/co-planar oracle front=" + checkedFront + ", back=" + checkedBack
                + ", mismatched channels=" + mismatches);
        check(checkedFront > 0 && checkedBack > 0, "coverage oracle samples both sides of the crossing");
        return mismatches;
    }

    private static float[] barycentric(float x, float y) {
        float[] a = XY[0], b = XY[1], c = XY[2];
        float d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
        float u = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / d;
        float v = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / d;
        float w = 1 - u - v;
        return u >= 0 && v >= 0 && w >= 0 ? new float[]{u, v, w} : null;
    }

    private static int draw(ToonShaderCpu shader, Target target, Geometry geometry, ModelMaterial[] materials,
                            int ebo, boolean gpu, boolean reverse, boolean layersEnabled,
                            int[] red, int[] green, int depthUnit, int coverageUnit,
                            int hostTexture, int hostSampler) {
        setupShader(shader);
        clear(target);
        GL46C.glEnable(GL46C.GL_DEPTH_TEST); GL46C.glDepthFunc(GL46C.GL_LEQUAL); GL46C.glDepthMask(true);
        GL46C.glDisable(GL46C.GL_CULL_FACE); GL46C.glEnable(GL46C.GL_BLEND);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
        int program = shader.getMainProgram();
        int batch = GL46C.glGetUniformLocation(program, "MmdBatchEnabled");
        if (batch >= 0) GL46C.glUniform1i(batch, 0);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        ByteBuffer submeshes = runs(reverse);
        int sourceEbo = GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        long start = System.nanoTime();
        int candidateCount;
        try (TranslucentDrawOrder order = new TranslucentDrawOrder()) {
            var input = input(geometry, gpu);
            SubMeshDrawHelper.draw(submeshes, 2, 4, GL46C.GL_UNSIGNED_INT, i -> materials[i].tex,
                    (i, alpha) -> alpha, materials, null, false, i -> shader.setEyeMaterialMask(0), order, input);
            candidateCount = order.candidateTriangleCount();
            check(candidateCount == 2, "both distinct material triangles entered translucent preparation");
            check(GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING) == sourceEbo, "source EBO restored");
        }
        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        assertState(target.framebuffer(), depthUnit, coverageUnit, hostTexture, hostSampler,
                GL46C.GL_LEQUAL, true, true);
        Image result = read();
        int errors = GL46C.glGetError();
        check(errors == GL46C.GL_NO_ERROR, "GL error after peel render: 0x" + Integer.toHexString(errors));
        System.out.printf("draw %s reverse=%s input=%s candidates=%d CPU-submit=%.3f ms%n",
                System.getProperty("mmdskin.toonTransparencyLayers"), reverse, gpu ? "GPU" : "CPU", candidateCount, elapsedMs);
        return compareOracle(result, red, green, layersEnabled);
    }

    private static int drawThreeLayerTail(ToonShaderCpu shader, Target target, Geometry geometry,
                                          ModelMaterial[] materials, int vao, int ebo, int depthUnit, int coverageUnit,
                                          int hostTexture, int hostSampler, int[] red, int[] green, int[] blue) {
        System.setProperty("mmdskin.toonTransparencyLayers", "true");
        System.setProperty("mmdskin.toonTransparencyLayerCount", "2");
        setupShader(shader); clear(target);
        GL46C.glEnable(GL46C.GL_DEPTH_TEST); GL46C.glDepthFunc(GL46C.GL_LEQUAL); GL46C.glDepthMask(true);
        GL46C.glDisable(GL46C.GL_CULL_FACE); GL46C.glEnable(GL46C.GL_BLEND);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
        GL46C.glBindVertexArray(vao);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        try (TranslucentDrawOrder order = new TranslucentDrawOrder()) {
            SubMeshDrawHelper.draw(threeRuns(), 3, 4, GL46C.GL_UNSIGNED_INT, i -> materials[i].tex,
                    (i, alpha) -> alpha, materials, null, false, i -> shader.setEyeMaterialMask(0), order,
                    input(geometry, true));
            check(order.candidateTriangleCount() == 3, "third blue material enters two-layer peel path");
        }
        assertState(target.framebuffer(), depthUnit, coverageUnit, hostTexture, hostSampler,
                GL46C.GL_LEQUAL, true, true);
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL error after two-layer tail render");
        return compareThreeLayerOracle(read(), red, green, blue);
    }

    private static int drawCoverageCase(ToonShaderCpu shader, Target target, Geometry geometry,
                                        ModelMaterial[] materials, int vao, int ebo, int depthUnit,
                                        int coverageUnit, int hostTexture, int hostSampler,
                                        int[] red, int[] green, int[] blue) {
        System.setProperty("mmdskin.toonTransparencyLayers", "true");
        System.setProperty("mmdskin.toonTransparencyLayerCount", "2");
        setupShader(shader); clear(target);
        GL46C.glEnable(GL46C.GL_DEPTH_TEST); GL46C.glDepthFunc(GL46C.GL_LEQUAL); GL46C.glDepthMask(true);
        GL46C.glDisable(GL46C.GL_CULL_FACE); GL46C.glEnable(GL46C.GL_BLEND);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
        GL46C.glBindVertexArray(vao); GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        try (TranslucentDrawOrder order = new TranslucentDrawOrder()) {
            SubMeshDrawHelper.draw(coverageRuns(), 3, 4, GL46C.GL_UNSIGNED_INT, i -> materials[i].tex,
                    (i, alpha) -> alpha, materials, null, false, i -> shader.setEyeMaterialMask(0), order,
                    input(geometry, true));
            check(order.candidateTriangleCount() == 2, "only red and green enter transparent peel");
        }
        assertState(target.framebuffer(), depthUnit, coverageUnit, hostTexture, hostSampler,
                GL46C.GL_LEQUAL, true, true);
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL error after coverage/co-planar render");
        return compareCoverageOracle(read(), red, green, blue);
    }

    private static void verifyExceptionCleanup(ToonShaderCpu shader, Target target, Geometry geometry,
                                               ModelMaterial[] materials, int ebo, int depthUnit, int coverageUnit,
                                               int hostTexture, int hostSampler) {
        int program = shader.getMainProgram();
        int mode = GL46C.glGetUniformLocation(program, "ToonTransparencyMode");
        int depth = GL46C.glGetUniformLocation(program, "ToonTransparencyDepth");
        int coverage = GL46C.glGetUniformLocation(program, "ToonTransparencyCoverage");
        int previous = GL46C.glGetUniformLocation(program, "ToonTransparencyHasPrevious");
        GL46C.glUniform1i(mode, 7); GL46C.glUniform1i(depth, depthUnit);
        GL46C.glUniform1i(coverage, coverageUnit); GL46C.glUniform1i(previous, 1);
        boolean[] threwDuringPeel = {false};
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        try (TranslucentDrawOrder order = new TranslucentDrawOrder()) {
            try {
                SubMeshDrawHelper.draw(runs(false), 2, 4, GL46C.GL_UNSIGNED_INT, i -> materials[i].tex,
                        (i, alpha) -> alpha, materials, null, false, i -> {
                            if (!threwDuringPeel[0] && GL46C.glGetQueryi(GL46C.GL_ANY_SAMPLES_PASSED,
                                    GL46C.GL_CURRENT_QUERY) != 0) {
                                threwDuringPeel[0] = true;
                                throw new IllegalStateException("probe exception inside peel draw callback");
                            }
                        }, order, input(geometry, false));
                throw new AssertionError("injected peel exception was not propagated");
            } catch (IllegalStateException expected) {
                check("probe exception inside peel draw callback".equals(expected.getMessage()),
                        "expected injected exception from first peel material");
            }
        }
        check(threwDuringPeel[0], "failure injected while peel query was active");
        for (int queryTarget : new int[]{GL46C.GL_ANY_SAMPLES_PASSED, GL46C.GL_SAMPLES_PASSED,
                GL46C.GL_ANY_SAMPLES_PASSED_CONSERVATIVE})
            check(GL46C.glGetQueryi(queryTarget, GL46C.GL_CURRENT_QUERY) == 0, "occlusion query closed after exception");
        check(GL46C.glGetUniformi(program, mode) == 7 && GL46C.glGetUniformi(program, depth) == depthUnit
                && GL46C.glGetUniformi(program, coverage) == coverageUnit
                && GL46C.glGetUniformi(program, previous) == 1, "peel uniforms restored after exception");
        assertState(target.framebuffer(), depthUnit, coverageUnit, hostTexture, hostSampler,
                GL46C.GL_LEQUAL, true, true);
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL error after injected peel exception");
    }

    private static long benchmarkDraw(ToonShaderCpu shader, Target target, Geometry geometry,
                                     ModelMaterial[] materials, int ebo, boolean layers, int layerCount,
                                     TranslucentDrawOrder order, TranslucentDrawOrder.Inputs inputs,
                                     ByteBuffer submeshes) {
        System.setProperty("mmdskin.toonTransparencyLayers", Boolean.toString(layers));
        System.setProperty("mmdskin.toonTransparencyLayerCount", Integer.toString(layerCount));
        clear(target);
        setupShader(shader);
        GL46C.glEnable(GL46C.GL_DEPTH_TEST); GL46C.glDepthFunc(GL46C.GL_LEQUAL); GL46C.glDepthMask(true);
        GL46C.glEnable(GL46C.GL_BLEND);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        long start = System.nanoTime();
        SubMeshDrawHelper.draw(submeshes, 2, 4, GL46C.GL_UNSIGNED_INT, i -> materials[i].tex,
                (i, alpha) -> alpha, materials, null, false, i -> shader.setEyeMaterialMask(0), order, inputs);
        return System.nanoTime() - start;
    }

    // 可独立在旧 ToonTransparencyLayers 上执行，用于修复前后回归。
    private static void capacityProbe(ToonShaderCpu shader) {
        ToonTransparencyLayers.cleanup();
        int fbo = GL46C.glGenFramebuffers(), depth = GL46C.glGenRenderbuffers();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, depth);
        GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_RENDERBUFFER, depth);
        GL46C.glDrawBuffer(GL46C.GL_NONE); GL46C.glReadBuffer(GL46C.GL_NONE);
        setupShader(shader);
        GL46C.glDepthFunc(GL46C.GL_LEQUAL);
        System.setProperty("mmdskin.toonTransparencyLayers", "true");
        try {
            int[][] requests = {{1500, 8}, {2200, 4}, {2200, 16}, {320, 16}};
            for (int[] request : requests) {
                int size = request[0], count = request[1];
                GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER, GL46C.GL_DEPTH_COMPONENT24, size, size);
                GL46C.glViewport(0, 0, size, size);
                System.setProperty("mmdskin.toonTransparencyLayerCount", Integer.toString(count));
                check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "capacity source FBO");
                GL46C.glBlendEquationSeparatei(0, GL46C.GL_FUNC_ADD, GL46C.GL_FUNC_REVERSE_SUBTRACT);
                try (ToonTransparencyLayers layers = ToonTransparencyLayers.begin()) {
                    int expected = (int) Math.min(count, 128L * 1024 * 1024 / (size * (long) size * 5));
                    check(layers != null, "current request fits budget after historical allocation: " + size + "x" + size + " layers=" + count);
                    check(layers.layers() == expected, "budget-reduced layer count");
                    check(ToonTransparencyLayers.allocatedBytes() <= 128L * 1024 * 1024, "shared pool budget");
                    System.out.println("PASS capacity " + size + "x" + size + " requested=" + count
                            + " actual=" + layers.layers() + " bytes=" + ToonTransparencyLayers.allocatedBytes());
                }
            }
            for (String invalid : new String[]{"0", "-1", "17", "invalid"}) {
                System.setProperty("mmdskin.toonTransparencyLayerCount", invalid);
                check(ToonTransparencyLayers.begin() == null, "invalid or disabled count falls back");
                var snapshot = ToonTransparencyDiagnostics.latest();
                check(!snapshot.enabled() && snapshot.actualLayers() == 0, "fallback diagnostics expose disabled state");
                check(snapshot.reason() == (invalid.equals("0") ? ToonTransparencyDiagnostics.Reason.DISABLED
                        : ToonTransparencyDiagnostics.Reason.INVALID_COUNT), "explicit count reason code");
            }
            check(GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_RGB, 0) == GL46C.GL_FUNC_ADD
                    && GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_ALPHA, 0) == GL46C.GL_FUNC_REVERSE_SUBTRACT,
                    "independent blend equations restored");
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "capacity GL state");
        } finally {
            ToonTransparencyLayers.cleanup();
            System.clearProperty("mmdskin.toonTransparencyLayerCount");
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, 0);
            GL46C.glDeleteRenderbuffers(depth); GL46C.glDeleteFramebuffers(fbo);
            GL46C.glBlendEquation(GL46C.GL_FUNC_ADD);
        }
    }

    private static void benchmark(ToonShaderCpu shader, Target target, Geometry geometry,
                                  ModelMaterial[] materials, int ebo) {
        final int warmups = 3, iterations = 24;
        int query = GL46C.glGenQueries();
        try {
            ToonTransparencyLayers.cleanup();
            for (int count : Boolean.getBoolean("mmdskin.probeBaseline")
                    ? new int[]{0, 1, 2, 4, 8} : new int[]{0, 1, 2, 4, 8, 16}) {
                boolean layers = count != 0;
                int layerCount = layers ? count : 4;
                ByteBuffer submeshes = runs(false);
                TranslucentDrawOrder.Inputs inputs = input(geometry, true); // 静态几何 revision，测热缓存。
                long cpuNanos = 0;
                try (TranslucentDrawOrder order = new TranslucentDrawOrder()) {
                    for (int i = 0; i < warmups; i++)
                        benchmarkDraw(shader, target, geometry, materials, ebo, layers, layerCount, order, inputs, submeshes);
                    GL46C.glBeginQuery(GL46C.GL_TIME_ELAPSED, query);
                    for (int i = 0; i < iterations; i++)
                        cpuNanos += benchmarkDraw(shader, target, geometry, materials, ebo, layers, layerCount, order, inputs, submeshes);
                    GL46C.glEndQuery(GL46C.GL_TIME_ELAPSED);
                    long gpuNanos = GL46C.glGetQueryObjectui64(query, GL46C.GL_QUERY_RESULT);
                    System.out.printf("GPU timer 320x320, %s, layers=%d, static-hot draws=%d, CPU-submit=%.3f ms/draw, GPU=%.3f ms/draw, pool=%d bytes (no readback; GPU timer includes submission gaps)%n",
                            layers ? "peel" : "center-sort", layerCount, iterations,
                            cpuNanos / 1_000_000.0 / iterations, gpuNanos / 1_000_000.0 / iterations,
                            ToonTransparencyLayers.allocatedBytes());
                }
            }
        } finally {
            GL46C.glDeleteQueries(query);
            System.clearProperty("mmdskin.toonTransparencyLayerCount");
        }
    }

    public static void main(String[] args) {
        BatchGlProbe.check(GLFW.glfwInit(), "GLFW"); GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4); GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, 24); GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_DEBUG_CONTEXT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "toon transparency layers", 0, 0);
        BatchGlProbe.check(window != 0, "GL window");
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            try (var debug = org.lwjgl.opengl.GLUtil.setupDebugMessageCallback()) {
                GL46C.glDebugMessageControl(GL46C.GL_DONT_CARE, GL46C.GL_DONT_CARE,
                        GL46C.GL_DEBUG_SEVERITY_NOTIFICATION, (java.nio.IntBuffer) null, false);
                if (args.length > 0 && args[0].equals("--capacity-only")) {
                    RenderSystem.initRenderThread();
                    ToonShaderCpu shader = new ToonShaderCpu(); check(shader.init(), "Toon shader");
                    try { capacityProbe(shader); } finally { shader.cleanup(); }
                } else run();
            }
        } finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }

    private static void run() {
        RenderSystem.initRenderThread();
        ToonShaderCpu shader = new ToonShaderCpu(); check(shader.init(), "production Toon shader");
        if (profileOverride != null) check(shader.selectOutputProfile(profileOverride), "select requested Toon output profile");
        int vao = GL46C.glGenVertexArrays(), ebo = GL46C.glGenBuffers();
        Geometry geometry = geometry(vao, ebo);
        int tailVao = GL46C.glGenVertexArrays(), tailEbo = GL46C.glGenBuffers();
        Geometry tailGeometry = geometry(tailVao, tailEbo, 3);
        int coverageVao = GL46C.glGenVertexArrays(), coverageEbo = GL46C.glGenBuffers();
        Geometry coverageGeometry = geometry(coverageVao, coverageEbo, 3, true);
        int redTexture = texture(255, 0, 0, 255), greenTexture = texture(0, 255, 0, 255);
        ModelMaterial[] materials = materials(redTexture, greenTexture);
        int blueTexture = texture(0, 0, 255, 255);
        ModelMaterial[] tailMaterials = materials(redTexture, greenTexture, blueTexture);
        ModelMaterial[] coverageMaterials = coverageMaterials(redTexture, greenTexture, blueTexture);
        Target target = target();
        int hostTexture = texture(11, 22, 33, 44), hostSampler = GL46C.glGenSamplers();
        GL46C.glSamplerParameteri(hostSampler, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glSamplerParameteri(hostSampler, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        GL46C.glViewport(0, 0, SIZE, SIZE);
        setupShader(shader); shader.setSampler0(0);
        try (SphereTextureBinding ignored = new SphereTextureBinding(false)) { }
        int[] peelUnits = unitsForPeel(shader.getMainProgram());
        int depthUnit = peelUnits[0], coverageUnit = peelUnits[1];
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + depthUnit);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, hostTexture); GL46C.glBindSampler(depthUnit, hostSampler);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + coverageUnit);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, hostTexture); GL46C.glBindSampler(coverageUnit, hostSampler);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, target.framebuffer());
        GL46C.glDisable(GL46C.GL_STENCIL_TEST); GL46C.glEnable(GL46C.GL_DEPTH_TEST);
        GL46C.glDepthFunc(GL46C.GL_LEQUAL); GL46C.glDepthMask(true);
        GL46C.glDisable(GL46C.GL_CULL_FACE); GL46C.glEnable(GL46C.GL_BLEND);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
        int redX = SIZE * 3 / 8, greenX = SIZE * 5 / 8, sampleY = SIZE / 2;
        int[] red = opaqueSourceColor(shader, materials, ebo, 0, redX, sampleY);
        int[] green = opaqueSourceColor(shader, materials, ebo, 1, greenX, sampleY);
        GL46C.glBindVertexArray(tailVao);
        int[] blue = opaqueSourceColor(shader, tailMaterials, tailEbo, 2, SIZE / 2, sampleY);
        GL46C.glBindVertexArray(vao);
        ToonTransparencyLayers cleanup = null;
        try {
            for (boolean enabled : new boolean[]{false, true}) {
                System.setProperty("mmdskin.toonTransparencyLayers", Boolean.toString(enabled));
                clear(target);
                setupShader(shader);
                if (enabled) {
                    cleanup = ToonTransparencyLayers.begin();
                    check(cleanup != null, "peel enabled for depth-attached offscreen FBO");
                    cleanup.close(); cleanup = null;
                } else {
                    check(ToonTransparencyLayers.begin() == null, "peel property disables layers");
                }
                if (enabled) {
                    verifyExceptionCleanup(shader, target, geometry, materials, ebo, depthUnit, coverageUnit,
                            hostTexture, hostSampler);
                    check(draw(shader, target, geometry, materials, ebo, false, false, true,
                            red, green, depthUnit, coverageUnit, hostTexture, hostSampler) == 0,
                            "normal draw still matches oracle after injected exception");
                }
                for (boolean gpu : new boolean[]{false, true}) {
                    int redFirstMismatches = draw(shader, target, geometry, materials, ebo,
                            gpu, false, enabled, red, green, depthUnit, coverageUnit, hostTexture, hostSampler);
                    int greenFirstMismatches = draw(shader, target, geometry, materials, ebo,
                            gpu, true, enabled, red, green, depthUnit, coverageUnit, hostTexture, hostSampler);
                    if (enabled) {
                        check(redFirstMismatches == 0 && greenFirstMismatches == 0,
                                "peel output matches independent per-pixel over oracle");
                    } else {
                        check(redFirstMismatches > 0 && greenFirstMismatches > 0,
                                "ordinary global ordering fails crossing-depth oracle in both submission orders");
                    }
                }
            }
            check(drawThreeLayerTail(shader, target, tailGeometry, tailMaterials, tailVao, tailEbo,
                    depthUnit, coverageUnit, hostTexture, hostSampler, red, green, blue) == 0,
                    "two peeled layers preserve independently ordered blue tail");
            check(drawCoverageCase(shader, target, coverageGeometry, coverageMaterials, coverageVao, coverageEbo,
                    depthUnit, coverageUnit, hostTexture, hostSampler, red, green, blue) == 0,
                    "coverage masks preserve co-planar red/blue and reject green behind opaque blue");
            GL46C.glBindVertexArray(vao);
            benchmark(shader, target, geometry, materials, ebo);
            System.out.println("ToonTransparencyLayersGlProbe PASS: crossing red/green, coverage/co-planar oracle, injected-exception cleanup, two-layer blue tail, CPU/GPU inputs, profile override, per-pixel over oracle, FBO/sampler/depth restoration");
        } finally {
            if (cleanup != null) cleanup.close();
            ToonTransparencyLayers.cleanup(); shader.cleanup();
            GL46C.glDeleteTextures(new int[]{redTexture, greenTexture, blueTexture, hostTexture,
                    target.colorTexture(), target.depthTexture()});
            GL46C.glDeleteSamplers(hostSampler);
            GL46C.glDeleteFramebuffers(target.framebuffer());
            GL46C.glDeleteBuffers(new int[]{ebo, geometry.vertexBuffer(), geometry.positionBuffer(),
                    tailEbo, tailGeometry.vertexBuffer(), tailGeometry.positionBuffer(),
                    coverageEbo, coverageGeometry.vertexBuffer(), coverageGeometry.positionBuffer()});
            GL46C.glDeleteVertexArrays(new int[]{vao, tailVao, coverageVao});
            System.clearProperty("mmdskin.toonTransparencyLayers");
            System.clearProperty("mmdskin.toonTransparencyLayerCount");
        }
    }
}
