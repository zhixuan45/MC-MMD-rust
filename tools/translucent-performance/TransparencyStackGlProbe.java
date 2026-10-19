package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.pipeline.RenderFrameClock;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import java.nio.*;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** 独立按已知逐像素深度计算 source-over；运行生产颜色/分层/余量查询。 */
public final class TransparencyStackGlProbe {
    private static final int SIZE = 32;
    private static ByteBuffer bytes(int n) { return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder()); }
    private static void check(boolean ok, String message) { BatchGlProbe.check(ok, message); }

    private static void setup(ToonShaderCpu shader) {
        BatchGlProbe.setup(shader);
        shader.setSpecular(64, 0); shader.setRimLight(2, 0); shader.setEyeEmission(0);
    }

    private static float[] pixel(int format, int count) {
        float[] value = new float[count];
        GL46C.glReadPixels(SIZE / 2, SIZE / 2, 1, 1, format, GL46C.GL_FLOAT, value);
        return value;
    }

    private static void clear() {
        RenderSystem.depthMask(true);
        GL46C.glClearColor(0, 0, 0, 0); GL46C.glClearDepth(1);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
    }

    private static void scenario(ToonShaderCpu shader, int format, float[] depths, int limit,
                                 boolean gpu, boolean reverse, float alpha, boolean expectOverflow) {
        ToonTransparencyLayers.cleanup();
        System.setProperty("mmdskin.toonTransparencyLayerCount", Integer.toString(limit));
        System.setProperty("mmdskin.toonTransparencyDiagnostics", "true");
        System.setProperty("mmdskin.toonTransparencyLayers", "true");
        int count = depths.length;
        int vao = GL46C.glGenVertexArrays(), vbo = GL46C.glGenBuffers(), ebo = GL46C.glGenBuffers();
        int positions = GL46C.glGenBuffers(), fbo = GL46C.glGenFramebuffers();
        int color = GL46C.glGenTextures(), depth = GL46C.glGenRenderbuffers();
        int[] textures = new int[count];
        try {
            GL46C.glBindVertexArray(vao);
            ByteBuffer vertices = bytes(count * 3 * 36), xyz = bytes(count * 3 * 12), uv = bytes(count * 3 * 8);
            IntBuffer indices = bytes(count * 3 * 4).asIntBuffer();
            for (int i = 0; i < count; i++) for (float[] xy : new float[][]{{-1,-1}, {3,-1}, {-1,3}}) {
                vertices.putFloat(xy[0]).putFloat(xy[1]).putFloat(depths[i])
                        .putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(0);
                xyz.putFloat(xy[0]).putFloat(xy[1]).putFloat(depths[i]); uv.putFloat(0).putFloat(0);
                indices.put(indices.position());
            }
            vertices.flip(); xyz.flip(); uv.flip(); indices.flip();
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, vbo);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, vertices, GL46C.GL_STATIC_DRAW);
            for (int i = 0; i < 4; i++) {
                GL46C.glEnableVertexAttribArray(i);
                GL46C.glVertexAttribPointer(i, i < 2 ? 3 : i == 2 ? 2 : 1, GL46C.GL_FLOAT,
                        false, 36, i == 0 ? 0 : i == 1 ? 12 : i == 2 ? 24 : 32);
            }
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
            GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, indices, GL46C.GL_STATIC_DRAW);
            GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, positions);
            GL46C.glBufferData(GL46C.GL_COPY_WRITE_BUFFER, xyz, GL46C.GL_STATIC_DRAW);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, color);
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA32F, SIZE, SIZE, 0, GL46C.GL_RGBA, GL46C.GL_FLOAT, 0L);
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
            GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, color, 0);
            GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, depth);
            GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER, format, SIZE, SIZE);
            GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_RENDERBUFFER, depth);
            GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0); GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
            check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "stack FBO");
            GL46C.glViewport(0, 0, SIZE, SIZE);
            ModelMaterial[] materials = new ModelMaterial[count];
            float[][] source = new float[count][];
            for (int i = 0; i < count; i++) {
                textures[i] = GL46C.glGenTextures(); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, textures[i]);
                ByteBuffer texel = bytes(4).put((byte) (i % 3 == 0 ? 255 : 30))
                        .put((byte) (i % 3 == 1 ? 255 : 40)).put((byte) (i % 3 == 2 ? 255 : 50)).put((byte) 255);
                texel.flip();
                GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, 1, 1, 0, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, texel);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
                ModelMaterial m = materials[i] = new ModelMaterial();
                m.name = "stack" + i; m.tex = textures[i]; m.hasAlpha = m.hasTranslucentPixels = true;
                m.diffuse = new float[]{1,1,1,1}; m.specular = new float[]{0,0,0};
                setup(shader); clear(); GL46C.glDisable(GL46C.GL_BLEND); GL46C.glDisable(GL46C.GL_DEPTH_TEST);
                GL46C.glDisable(GL46C.GL_CULL_FACE);
                try (MaterialShaderBinding binding = new MaterialShaderBinding()) {
                    MaterialRenderState state = new MaterialRenderState(); state.resolve(m, null, i, 1);
                    binding.apply(state, 0);
                    GL46C.glDrawElements(GL46C.GL_TRIANGLES, 3, GL46C.GL_UNSIGNED_INT, i * 12L);
                }
                source[i] = pixel(GL46C.GL_RGBA, 4);
            }
            ByteBuffer runs = bytes(count * 20);
            for (int row = 0; row < count; row++) {
                int i = reverse ? count - row - 1 : row, offset = row * 20;
                runs.putInt(offset, i).putInt(offset + 4, i * 3).putInt(offset + 8, 3)
                        .putFloat(offset + 12, alpha).put(offset + 16, (byte) 1).put(offset + 17, (byte) 1);
            }
            setup(shader); clear(); GL46C.glEnable(GL46C.GL_DEPTH_TEST); GL46C.glDepthFunc(GL46C.GL_LEQUAL);
            GL46C.glEnable(GL46C.GL_BLEND);
            GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA, GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
            FloatBuffer matrix = bytes(64).asFloatBuffer(); new Matrix4f().get(matrix);
            var input = gpu ? TranslucentDrawOrder.Inputs.gpu(positions, count * 3, matrix, matrix, 1, false, uv, 0, 0, false)
                    : TranslucentDrawOrder.Inputs.cpu(xyz, 0, count * 3, matrix, matrix, 1, false, uv, 0, false);
            RenderFrameClock.begin();
            long frame = RenderFrameClock.current();
            try (TranslucentDrawOrder order = new TranslucentDrawOrder()) {
                SubMeshDrawHelper.draw(runs, count, 4, GL46C.GL_UNSIGNED_INT, i -> materials[i].tex, (i,a) -> a,
                        materials, null, false, i -> shader.setEyeMaterialMask(0), order, input);
            }
            float[] actual = pixel(GL46C.GL_RGBA, 4), expected = new float[4];
            List<Integer> order = new ArrayList<>();
            for (int row = 0; row < count; row++) order.add(reverse ? count - row - 1 : row);
            // 独立按实际窗口深度排序；同深度按源 submesh/三角形顺序混合。
            order.sort((a,b) -> Double.compare(referenceDepth(depths[b], format), referenceDepth(depths[a], format)));
            if (alpha >= .001f) for (int i : order) {
                for (int c = 0; c < 3; c++) expected[c] = source[i][c] * alpha + expected[c] * (1 - alpha);
                expected[3] = alpha + expected[3] * (1 - alpha);
            }
            String tag = "format=" + format + " count=" + count + " limit=" + limit + " gpu=" + gpu + " reverse=" + reverse + " alpha=" + alpha;
            System.out.println(tag + " actual=" + Arrays.toString(actual) + " expected=" + Arrays.toString(expected));
            for (int c = 0; c < 4; c++) check(Math.abs(actual[c] - expected[c]) < .015f, "independent stack oracle channel=" + c + " " + tag);
            if (alpha >= .001f && alpha < .999f && !Boolean.getBoolean("mmdskin.probeBaseline")) {
                ToonTransparencyDiagnostics.poll();
                var overflow = ToonTransparencyDiagnostics.latestOverflow();
                check(overflow != null && overflow.frame() == frame && overflow.remainingFragments() == expectOverflow,
                        "tail query measures deeper fragments from corresponding frame: " + tag);
            }
            check(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == fbo, "restore stack FBO");
            check(GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING) == ebo, "restore source EBO");
            check(GL46C.glGetQueryi(GL46C.GL_ANY_SAMPLES_PASSED, GL46C.GL_CURRENT_QUERY) == 0, "tail query closed");
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "stack GL error");
        } finally {
            ToonTransparencyLayers.cleanup();
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, 0);
            GL46C.glDeleteTextures(textures); GL46C.glDeleteTextures(color);
            GL46C.glDeleteRenderbuffers(depth); GL46C.glDeleteFramebuffers(fbo);
            GL46C.glDeleteBuffers(new int[]{vbo, ebo, positions}); GL46C.glDeleteVertexArrays(vao);
        }
    }

    private static double referenceDepth(float z, int format) {
        float windowDepth = z * .5f + .5f;
        return windowDepth; // R32F 层深度独立于宿主深度格式。
    }

    public static void main(String[] args) {
        check(GLFW.glfwInit(), "GLFW"); GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4); GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "stack oracle", 0, 0); check(window != 0, "GL window");
        GLFW.glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
        ToonShaderCpu shader = new ToonShaderCpu(); check(shader.init(), "production Toon shader");
        try {
            System.out.println("GL=" + GL46C.glGetString(GL46C.GL_RENDERER));
            for (int format : new int[]{GL46C.GL_DEPTH_COMPONENT24, GL46C.GL_DEPTH_COMPONENT32F})
                for (boolean gpu : new boolean[]{false, true}) for (boolean reverse : new boolean[]{false, true}) {
                    if (args.length > 0 && args[0].equals("--close")) {
                        for (float center : new float[]{-.98f, -.5f, 0, .5f, .98f})
                            for (float offset : new float[]{.00000012f, .00000024f, .00000036f})
                                scenario(shader, format, new float[]{center + offset, center}, 4, gpu, reverse, .5f, false);
                        continue;
                    }
                    for (int count : new int[]{2, 4, 6, 8, 16, 17}) {
                        float[] depths = new float[count];
                        for (int i = 0; i < count; i++) depths[i] = .85f - 1.7f * i / count;
                        scenario(shader, format, depths, Math.min(count, 16), gpu, reverse, .5f, count > 16);
                    }
                    for (float alpha : new float[]{0, 11/255f, 128/255f, 254/255f, 1})
                        scenario(shader, format, new float[]{-.5f, .5f}, 4, gpu, reverse, alpha, false);
                    scenario(shader, format, new float[]{0, 0}, 4, gpu, reverse, .5f, false);
                    scenario(shader, format, new float[]{-.9999999f, 1}, 4, gpu, reverse, .5f, false);
                }
            System.out.println("PASS independent transparency stack oracle");
        } finally {
            shader.cleanup();
            for (String key : new String[]{"toonTransparencyLayerCount", "toonTransparencyLayers", "toonTransparencyDiagnostics"})
                System.clearProperty("mmdskin." + key);
            GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate();
        }
    }
}
