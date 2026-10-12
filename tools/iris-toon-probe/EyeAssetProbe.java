package com.shiroha.mmdskin.render.shader;

import com.shiroha.mmdskin.render.material.EyeBoneMask;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 使用目标 PMX 导出数据，在生产 Toon shader 中验证真实眼骨 mask。 */
public final class EyeAssetProbe {
    private static final int SIZE = 512;
    private static final float EYE_STRENGTH = 1.6f;
    private static final float[] MODEL_VIEW = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, -17.84f, -1.70f, 1
    };
    private static final float[] PROJECTION = {
            1.05f, 0, 0, 0,
            0, 2.25f, 0, 0,
            0, 0, -1, 0,
            0, 0, -1.25f, 1
    };

    private EyeAssetProbe() {}

    public static void main(String[] args) throws Exception {
        Path data = args.length > 0 ? Path.of(args[0]) : Path.of("logs/iris-toon-unbound-20261011/eye-asset.bin");
        Path output = args.length > 1 ? Path.of(args[1]) : data.toAbsolutePath().getParent();
        if (!GLFW.glfwInit()) throw new IllegalStateException("无法初始化 EyeAssetProbe GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(16, 16, "Eye asset probe", 0, 0);
        if (window == 0) {
            GLFW.glfwTerminate();
            throw new IllegalStateException("无法创建 EyeAssetProbe 隐藏窗口");
        }
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        ToonShaderCpu shader = new ToonShaderCpu();
        try {
            if (!shader.init()) throw new AssertionError("生产 Toon shader 初始化失败");
            run(shader, data, output);
        } finally {
            shader.cleanup();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    /** 调用方也可在已创建的隐藏 OpenGL 上下文中直接运行。 */
    public static void run(ToonShaderCpu shader, Path dataPath, Path outputDirectory) throws Exception {
        Asset asset = readAsset(dataPath);
        BufferedImage sourceTexture = ImageIO.read(Path.of(asset.faceTexture).toFile());
        if (sourceTexture == null) throw new IOException("无法读取目标贴图: " + asset.faceTexture);
        Files.createDirectories(outputDirectory);

        int vertexCount = asset.vertices.size();
        int[] boneIndices = new int[vertexCount * 4];
        float[] boneWeights = new float[vertexCount * 4];
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];
        for (int i = 0; i < vertexCount; i++) {
            Vertex vertex = asset.vertices.get(i);
            System.arraycopy(vertex.boneIndices, 0, boneIndices, i * 4, 4);
            System.arraycopy(vertex.boneWeights, 0, boneWeights, i * 4, 4);
            System.arraycopy(vertex.position, 0, positions, i * 3, 3);
            System.arraycopy(vertex.normal, 0, normals, i * 3, 3);
            System.arraycopy(vertex.uv, 0, uvs, i * 2, 2);
        }

        float[] mask = EyeBoneMask.build(asset.boneNames, IntBuffer.wrap(boneIndices),
                FloatBuffer.wrap(boneWeights), vertexCount);
        verifyMask(asset, mask);
        List<DrawMaterial> materials = classifyTriangles(asset, mask);
        verifyTextureRegions(asset, materials);

        int vao = 0, positionBuffer = 0, normalBuffer = 0, uvBuffer = 0, maskBuffer = 0;
        int texture = 0, framebuffer = 0, depth = 0;
        int[] colorTargets = new int[4];
        try {
            vao = GL46C.glGenVertexArrays();
            GL46C.glBindVertexArray(vao);
            positionBuffer = attribute(shader.getPositionLocation(), 3, positions);
            normalBuffer = attribute(shader.getNormalLocation(), 3, normals);
            uvBuffer = attribute(shader.getUv0Location(), 2, uvs);
            int eyeMaskLocation = GL46C.glGetAttribLocation(shader.getMainProgram(), "EyeMask");
            check(eyeMaskLocation >= 0, "生产 Toon shader 未暴露 EyeMask 属性");
            maskBuffer = attribute(eyeMaskLocation, 1, mask);
            texture = uploadTexture(sourceTexture);
            framebuffer = GL46C.glGenFramebuffers();
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, framebuffer);
            for (int i = 0; i < colorTargets.length; i++) {
                colorTargets[i] = GL46C.glGenTextures();
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, colorTargets[i]);
                GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA32F, SIZE, SIZE, 0,
                        GL46C.GL_RGBA, GL46C.GL_FLOAT, 0L);
                GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER,
                        GL46C.GL_COLOR_ATTACHMENT0 + i, GL46C.GL_TEXTURE_2D, colorTargets[i], 0);
            }
            depth = GL46C.glGenRenderbuffers();
            GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, depth);
            GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER, GL46C.GL_DEPTH_COMPONENT24, SIZE, SIZE);
            GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                    GL46C.GL_RENDERBUFFER, depth);
            GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_COLOR_ATTACHMENT1,
                    GL46C.GL_COLOR_ATTACHMENT2, GL46C.GL_COLOR_ATTACHMENT3});
            check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE,
                    "真实眼部 mesh FBO 不完整");
            GL46C.glViewport(0, 0, SIZE, SIZE);
            GL46C.glDisable(GL46C.GL_BLEND);
            GL46C.glDisable(GL46C.GL_CULL_FACE);
            GL46C.glDisable(GL46C.GL_MULTISAMPLE);
            GL46C.glDisable(GL46C.GL_DITHER);
            GL46C.glEnable(GL46C.GL_DEPTH_TEST);
            GL46C.glDepthFunc(GL46C.GL_LEQUAL);
            GL46C.glDepthMask(true);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
            shader.useMain();
            configureShader(shader);

            float[] before = render(shader, materials, texture, -1, 0.0f);
            float[] after = render(shader, materials, texture, -1, EYE_STRENGTH);
            writePng(outputDirectory.resolve("eye-before.png"), before);
            writePng(outputDirectory.resolve("eye-after.png"), after);
            writeDeltaPng(outputDirectory.resolve("eye-delta.png"), before, after);

            float[] maskZeroBefore = render(shader, materials, texture, 0, 0.0f);
            float[] maskZeroAfter = render(shader, materials, texture, 0, EYE_STRENGTH);
            float[] maskOneBefore = render(shader, materials, texture, 1, 0.0f);
            float[] maskOneAfter = render(shader, materials, texture, 1, EYE_STRENGTH);
            int unchanged = compareImages(maskZeroBefore, maskZeroAfter, 0.00001f, true);
            int changed = compareImages(maskOneBefore, maskOneAfter, 0.0001f, false);
            check(unchanged > 0, "mask=0 网格没有可读回像素");
            check(changed > 0, "mask=1 网格的发光没有改变实际 fragment");

            List<Sample> samples = chooseSamples(asset, materials, sourceTexture);
            for (Sample sample : samples) {
                float[] sampleBefore = renderSample(shader, materials, texture, sample, 0.0f);
                float[] sampleAfter = renderSample(shader, materials, texture, sample, EYE_STRENGTH);
                verifySample(sample, sampleBefore, sampleAfter);
            }
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "真实眼瞳探针产生 OpenGL 错误");
            System.out.printf("PASS eye asset: vertices=%d, mask1=%d, mask0=%d, fragments(mask0 unchanged=%d, mask1 changed=%d)%n",
                    vertexCount, count(mask, 0.999f, true), count(mask, 0.000001f, false), unchanged, changed);
            for (Sample sample : samples) {
                System.out.printf("  sample=%s material=%d mask=%.1f uv=(%.4f,%.4f) baseline=(%.3f,%.3f,%.3f,%.3f) delta=(%.3f,%.3f,%.3f) alphaDelta=%.6f%n",
                        sample.kind, sample.materialId, (float) sample.maskClass, sample.u, sample.v,
                        sample.before[0], sample.before[1], sample.before[2], sample.before[3],
                        sample.difference[0], sample.difference[1], sample.difference[2], sample.difference[3]);
            }
            System.out.println("PNG: " + outputDirectory.toAbsolutePath());
        } finally {
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, 0);
            if (depth != 0) GL46C.glDeleteRenderbuffers(depth);
            GL46C.glDeleteTextures(colorTargets);
            if (framebuffer != 0) GL46C.glDeleteFramebuffers(framebuffer);
            if (texture != 0) GL46C.glDeleteTextures(texture);
            for (int id : new int[]{positionBuffer, normalBuffer, uvBuffer, maskBuffer}) {
                if (id != 0) GL46C.glDeleteBuffers(id);
            }
            if (vao != 0) GL46C.glDeleteVertexArrays(vao);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        }
    }

    private static void verifyMask(Asset asset, float[] mask) {
        int positive = count(mask, 0.000001f, true);
        int full = count(mask, 0.999f, true);
        check(positive == 446 && full == 446, "真实 PMX 眼骨 mask 顶点数应为 446，实际 positive=" + positive + " full=" + full);
        for (int i = 0; i < mask.length; i++) {
            check(mask[i] == 0.0f || mask[i] == 1.0f, "眼部 mask 存在部分权重，vertex=" + i + " value=" + mask[i]);
        }
        check(asset.boneNames.length == 577, "目标 PMX 骨骼数变化: " + asset.boneNames.length);
        check("左目戻".equals(asset.boneNames[109]) && "右目戻".equals(asset.boneNames[107]),
                "目标 PMX 左右眼返回骨索引变化");
    }

    private static List<DrawMaterial> classifyTriangles(Asset asset, float[] mask) {
        int[][] expected = {{1992, 0, 0}, {220, 0, 0}, {2194, 512, 0}, {0, 156, 0}};
        List<DrawMaterial> result = new ArrayList<>();
        for (int mi = 0; mi < asset.materials.size(); mi++) {
            Material material = asset.materials.get(mi);
            int[][] grouped = new int[3][];
            List<Integer> all = new ArrayList<>(), zero = new ArrayList<>(), one = new ArrayList<>(), mixed = new ArrayList<>();
            for (int i = 0; i < material.indices.length; i += 3) {
                int a = material.indices[i], b = material.indices[i + 1], c = material.indices[i + 2];
                all.add(a); all.add(b); all.add(c);
                float min = Math.min(mask[a], Math.min(mask[b], mask[c]));
                float max = Math.max(mask[a], Math.max(mask[b], mask[c]));
                List<Integer> destination = max < 0.000001f ? zero : min > 0.999f ? one : mixed;
                destination.add(a); destination.add(b); destination.add(c);
            }
            grouped[0] = toArray(zero); grouped[1] = toArray(one); grouped[2] = toArray(mixed);
            int[] actual = {grouped[0].length / 3, grouped[1].length / 3, grouped[2].length / 3};
            check(Arrays.equals(actual, expected[mi]), "材质 #" + material.id + " mask triangle 分组变化: " + Arrays.toString(actual));
            result.add(new DrawMaterial(material, toArray(all), grouped[0], grouped[1], grouped[2], mask));
        }
        return result;
    }

    private static void verifyTextureRegions(Asset asset, List<DrawMaterial> materials) {
        // 这些 UV 矩形只用于固定目标贴图的离线证据分区，不进入生产 mask。
        int greenEye = countRegion(materials.get(2).material, asset.vertices, materials.get(2), .76, 0, 1, .36, 1);
        int sclera = countRegion(materials.get(2).material, asset.vertices, materials.get(2), .48, .15, .70, .30, 0);
        int highlight = countRegion(materials.get(3).material, asset.vertices, materials.get(3), .48, .15, .70, .30, 1);
        int face = materials.get(0).material.indices.length / 3;
        int edge = materials.get(1).material.indices.length / 3;
        check(greenEye == 376, "绿眼纹理岛网格数变化: " + greenEye);
        check(sclera == 23, "目标眼白样本网格数变化: " + sclera);
        check(highlight == 112, "眼内高光网格数变化: " + highlight);
        check(face == 1992 && edge == 220, "目标脸部/眼缘三角形数变化");
    }

    private static int countRegion(Material material, List<Vertex> vertices, DrawMaterial draw,
                                   double minU, double minV, double maxU, double maxV, int expectedMask) {
        int count = 0;
        for (int i = 0; i < material.indices.length; i += 3) {
            int a = material.indices[i], b = material.indices[i + 1], c = material.indices[i + 2];
            Vertex[] tri = {vertices.get(a), vertices.get(b), vertices.get(c)};
            boolean inside = true;
            for (Vertex vertex : tri) {
                float u = vertex.sourceUv[0], v = vertex.sourceUv[1];
                inside &= u >= minU && u <= maxU && v >= minV && v <= maxV;
            }
            if (!inside) continue;
            boolean maskOne = draw.mask[a] > 0.999f && draw.mask[b] > 0.999f && draw.mask[c] > 0.999f;
            boolean maskZero = draw.mask[a] < 0.000001f && draw.mask[b] < 0.000001f && draw.mask[c] < 0.000001f;
            check(expectedMask == 1 ? maskOne : maskZero,
                    "贴图证据区域的眼骨 mask 不符合预期: material=" + material.id + " triangle=" + (i / 3));
            count++;
        }
        return count;
    }

    private static List<Sample> chooseSamples(Asset asset, List<DrawMaterial> materials, BufferedImage image) {
        List<Sample> samples = new ArrayList<>();
        samples.add(findSample(asset, materials, image, 2, 1, .76, 0, 1, .36, ColorKind.DARK, "dark-pupil"));
        samples.add(findSample(asset, materials, image, 2, 1, .76, 0, 1, .36, ColorKind.GRAY, "gray-iris"));
        samples.add(findSample(asset, materials, image, 3, 1, .48, .15, .70, .30, ColorKind.WHITE, "white-eye-highlight"));
        samples.add(findSample(asset, materials, image, 1, 0, .12, .33, .27, .48, ColorKind.WHITE, "white-eye-mask-zero"));
        samples.add(findSample(asset, materials, image, 0, 0, .08, .20, .40, .70, ColorKind.ANY, "face-mask-zero"));
        return samples;
    }

    private static Sample findSample(Asset asset, List<DrawMaterial> materials, BufferedImage image,
                                     int materialId, int maskClass, double minU, double minV,
                                     double maxU, double maxV, ColorKind kind, String label) {
        DrawMaterial draw = materials.get(materialId);
        int[] candidates = maskClass == 0 ? draw.zero : draw.eye;
        Sample best = null;
        double bestArea = -1;
        int width = image.getWidth(), height = image.getHeight();
        for (int i = 0; i < candidates.length; i += 3) {
            int[] tri = {candidates[i], candidates[i + 1], candidates[i + 2]};
            Vertex a = asset.vertices.get(tri[0]), b = asset.vertices.get(tri[1]), c = asset.vertices.get(tri[2]);
            if (!inside(a.sourceUv, minU, minV, maxU, maxV)
                    || !inside(b.sourceUv, minU, minV, maxU, maxV)
                    || !inside(c.sourceUv, minU, minV, maxU, maxV)) continue;
            double u = (a.sourceUv[0] + b.sourceUv[0] + c.sourceUv[0]) / 3.0;
            double v = (a.sourceUv[1] + b.sourceUv[1] + c.sourceUv[1]) / 3.0;
            int tx = Math.max(0, Math.min(width - 1, (int) Math.round(u * (width - 1))));
            int ty = Math.max(0, Math.min(height - 1, (int) Math.round(v * (height - 1))));
            int argb = image.getRGB(tx, ty);
            float[] rgba = rgba(argb);
            if (rgba[3] < .1f || !kind.matches(rgba)) continue;
            float[] pa = project(a.position), pb = project(b.position), pc = project(c.position);
            double area = Math.abs((pb[0] - pa[0]) * (pc[1] - pa[1]) - (pb[1] - pa[1]) * (pc[0] - pa[0]))
                    * SIZE * SIZE / 8.0;
            if (area <= bestArea) continue;
            int px = clampPixel((int) Math.floor((pa[0] + pb[0] + pc[0] + 3.0) * SIZE / 6.0));
            int py = clampPixel((int) Math.floor((pa[1] + pb[1] + pc[1] + 3.0) * SIZE / 6.0));
            bestArea = area;
            best = new Sample(label, materialId, tri, maskClass, u, v, rgba, px, py);
        }
        check(best != null && bestArea > 2.0, "找不到可读回的真实贴图样本: " + label);
        return best;
    }

    private static void verifySample(Sample sample, float[] before, float[] after) {
        float dr = after[0] - before[0], dg = after[1] - before[1], db = after[2] - before[2];
        float maxDelta = Math.max(dr, Math.max(dg, db));
        if (sample.maskClass == 0) {
            check(Math.abs(dr) < 0.00001f && Math.abs(dg) < 0.00001f && Math.abs(db) < 0.00001f,
                    sample.kind + " 的 mask=0 fragment 发光前后应不变");
        } else {
            float maxAlbedo = Math.max(sample.albedo[0], Math.max(sample.albedo[1], sample.albedo[2]));
            check(maxAlbedo > .01f && maxDelta > .01f,
                    sample.kind + " 的 mask=1 fragment 应按原贴图颜色参与发光");
        }
        check(Math.abs(before[3] - after[3]) < 0.00001f, sample.kind + " 发光不能改变 alpha");
        sample.difference = new float[]{dr, dg, db, after[3] - before[3]};
        sample.before = before;
    }

    private static float[] renderSample(ToonShaderCpu shader, List<DrawMaterial> materials,
                                        int texture, Sample sample, float emission) {
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, currentFramebuffer());
        clearTarget();
        setEmission(shader, texture, emission);
        DrawMaterial material = materials.get(sample.materialId);
        int element = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, element);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, sample.indices, GL46C.GL_STATIC_DRAW);
        GL46C.glDrawElements(GL46C.GL_TRIANGLES, 3, GL46C.GL_UNSIGNED_INT, 0L);
        float[] pixel = readPixel(sample.pixelX, sample.pixelY);
        GL46C.glDeleteBuffers(element);
        return pixel;
    }

    private static float[] render(ToonShaderCpu shader, List<DrawMaterial> materials,
                                  int texture, int maskClass, float emission) {
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, currentFramebuffer());
        clearTarget();
        setEmission(shader, texture, emission);
        for (DrawMaterial draw : materials) {
            int[] indices = maskClass < 0 ? draw.all : maskClass == 0 ? draw.zero : draw.eye;
            if (indices.length == 0) continue;
            int element = GL46C.glGenBuffers();
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, element);
            GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, indices, GL46C.GL_STATIC_DRAW);
            GL46C.glDrawElements(GL46C.GL_TRIANGLES, indices.length, GL46C.GL_UNSIGNED_INT, 0L);
            GL46C.glDeleteBuffers(element);
        }
        return readImage();
    }

    private static int currentFramebuffer() { return GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING); }

    private static void setEmission(ToonShaderCpu shader, int texture, float emission) {
        shader.useMain();
        configureShader(shader);
        shader.setEyeEmission(emission);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
    }

    private static void configureShader(ToonShaderCpu shader) {
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(shader.getMainProgram(), "ProjMat"), false, PROJECTION);
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(shader.getMainProgram(), "ModelViewMat"), false, MODEL_VIEW);
        shader.setSampler0(0);
        shader.setLightDirection(0, 0, 1);
        shader.setLightIntensity(.05f);
        shader.setToonLevels(2);
        shader.setShadowColor(.05f, .05f, .05f);
        shader.setSpecular(8, 0);
        shader.setRimLight(1, 0);
        shader.setAlphaCutoff(.1f);
        shader.setGlobalAlpha(1);
        shader.setEnvironmentLighting(0, null, 0, 0);
    }

    private static void clearTarget() {
        GL46C.glDepthMask(true);
        GL46C.glClearColor(0, 0, 0, 0);
        GL46C.glClearDepth(1.0);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
    }

    private static int attribute(int location, int components, float[] data) {
        check(location >= 0, "生产 Toon shader attribute 不存在");
        int buffer = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, buffer);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, data, GL46C.GL_STATIC_DRAW);
        GL46C.glEnableVertexAttribArray(location);
        GL46C.glVertexAttribPointer(location, components, GL46C.GL_FLOAT, false, 0, 0L);
        return buffer;
    }

    private static int uploadTexture(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        int id = 0;
        try {
            // 对齐 Rust texture loader 的垂直翻转；导出 UV 已按 runtime 再翻回。
            for (int y = height - 1; y >= 0; y--) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    pixels.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >> 24));
                }
            }
            pixels.flip();
            id = GL46C.glGenTextures();
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_ALIGNMENT, 1);
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA, width, height, 0,
                    GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, pixels);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_LINEAR);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_LINEAR);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, GL46C.GL_CLAMP_TO_EDGE);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_T, GL46C.GL_CLAMP_TO_EDGE);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAX_LEVEL, 0);
            return id;
        } catch (RuntimeException | Error e) {
            if (id != 0) GL46C.glDeleteTextures(id);
            throw e;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static float[] readImage() {
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
        GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        GL46C.glReadPixels(0, 0, SIZE, SIZE, GL46C.GL_RGBA, GL46C.GL_FLOAT, pixels);
        float[] result = new float[SIZE * SIZE * 4];
        pixels.get(result);
        return result;
    }

    private static float[] readPixel(int x, int y) {
        FloatBuffer pixel = BufferUtils.createFloatBuffer(4);
        GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        GL46C.glReadPixels(x, y, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, pixel);
        return new float[]{pixel.get(0), pixel.get(1), pixel.get(2), pixel.get(3)};
    }

    private static void writePng(Path path, float[] pixels) throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int source = ((SIZE - 1 - y) * SIZE + x) * 4;
                int r = toSrgb8(pixels[source]), g = toSrgb8(pixels[source + 1]), b = toSrgb8(pixels[source + 2]);
                int a = toByte(pixels[source + 3]);
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private static void writeDeltaPng(Path path, float[] before, float[] after) throws IOException {
        float[] delta = new float[before.length];
        for (int i = 0; i < before.length; i += 4) {
            delta[i] = Math.max(0, after[i] - before[i]);
            delta[i + 1] = Math.max(0, after[i + 1] - before[i + 1]);
            delta[i + 2] = Math.max(0, after[i + 2] - before[i + 2]);
            delta[i + 3] = after[i + 3];
        }
        writePng(path, delta);
    }

    private static int compareImages(float[] a, float[] b, float tolerance, boolean expectSame) {
        int changed = 0;
        for (int i = 0; i < a.length; i += 4) {
            float delta = Math.max(Math.abs(a[i] - b[i]), Math.max(Math.abs(a[i + 1] - b[i + 1]), Math.abs(a[i + 2] - b[i + 2])));
            if (delta > tolerance) changed++;
        }
        check(expectSame ? changed == 0 : changed > 0,
                expectSame ? "mask=0 全图 fragment 颜色变化像素数=" + changed : "mask=1 全图没有颜色变化");
        return expectSame ? a.length / 4 : changed;
    }

    private static float[] project(float[] position) {
        float viewY = position[1] - 17.84f;
        return new float[]{position[0] * 1.05f, viewY * 2.25f};
    }

    private static int clampPixel(int value) { return Math.max(0, Math.min(SIZE - 1, value)); }
    private static int toByte(float value) { return Math.max(0, Math.min(255, Math.round(value * 255))); }
    private static int toSrgb8(float value) {
        float linear = Math.max(0, Math.min(1, value));
        float srgb = linear <= 0.0031308f ? linear * 12.92f : 1.055f * (float) Math.pow(linear, 1.0 / 2.4) - .055f;
        return toByte(srgb);
    }

    private static float[] rgba(int argb) {
        return new float[]{((argb >> 16) & 255) / 255.0f, ((argb >> 8) & 255) / 255.0f,
                (argb & 255) / 255.0f, ((argb >>> 24) & 255) / 255.0f};
    }

    private static boolean inside(float[] uv, double minU, double minV, double maxU, double maxV) {
        return uv[0] >= minU && uv[0] <= maxU && uv[1] >= minV && uv[1] <= maxV;
    }

    private static int count(float[] values, float threshold, boolean atLeast) {
        int count = 0;
        for (float value : values) if (atLeast ? value >= threshold : value < threshold) count++;
        return count;
    }

    private static int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Asset readAsset(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            byte[] magic = in.readNBytes(8);
            check(Arrays.equals(magic, "MMD_EYE1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)), "眼部资产文件 magic 错误");
            check(in.readInt() == 1, "不支持的眼部资产文件版本");
            Asset asset = new Asset();
            asset.pmxPath = readString(in);
            asset.faceTexture = readString(in);
            asset.modelName = readString(in);
            asset.englishName = readString(in);
            asset.pmxVersion = in.readFloat();
            asset.sourceVertexCount = in.readInt();
            int vertexCount = in.readInt();
            int boneCount = in.readInt();
            check(vertexCount > 0 && vertexCount < 100000 && boneCount > 0 && boneCount < 10000, "眼部资产头信息异常");
            asset.boneNames = new String[boneCount];
            for (int i = 0; i < boneCount; i++) asset.boneNames[i] = readString(in);
            for (int i = 0; i < vertexCount; i++) {
                Vertex vertex = new Vertex();
                vertex.sourceIndex = in.readInt();
                vertex.sourcePosition = readFloats(in, 3);
                vertex.sourceNormal = readFloats(in, 3);
                vertex.sourceUv = readFloats(in, 2);
                vertex.position = readFloats(in, 3);
                vertex.normal = readFloats(in, 3);
                vertex.uv = readFloats(in, 2);
                vertex.boneIndices = new int[4];
                for (int j = 0; j < 4; j++) vertex.boneIndices[j] = in.readInt();
                vertex.boneWeights = readFloats(in, 4);
                asset.vertices.add(vertex);
            }
            int materialCount = in.readInt();
            check(materialCount == 4, "预期导出材质 0..3，实际=" + materialCount);
            for (int i = 0; i < materialCount; i++) {
                Material material = new Material();
                material.id = in.readInt();
                material.textureIndex = in.readInt();
                int indexCount = in.readInt();
                material.name = readString(in);
                material.texturePath = readString(in);
                check(indexCount >= 0 && indexCount % 3 == 0 && indexCount < 100000, "PMX 子网格索引数异常");
                material.indices = new int[indexCount];
                for (int j = 0; j < indexCount; j++) material.indices[j] = in.readInt();
                check(material.id == i, "材质顺序改变");
                asset.materials.add(material);
            }
            check(in.read() == -1, "资产文件有未解析尾部数据");
            return asset;
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        check(length >= 0 && length <= 1_000_000, "资产字符串长度异常");
        return new String(in.readNBytes(length), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static float[] readFloats(DataInputStream in, int count) throws IOException {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) values[i] = in.readFloat();
        return values;
    }

    private enum ColorKind {
        ANY { boolean matches(float[] rgba) { return true; } },
        DARK { boolean matches(float[] c) { return max(c) < .14f && min(c) > 0.005f; } },
        GRAY { boolean matches(float[] c) { return max(c) < .40f && max(c) - min(c) < .10f && max(c) > .10f; } },
        WHITE { boolean matches(float[] c) { return min(c) > .82f && max(c) - min(c) < .10f; } };
        abstract boolean matches(float[] rgba);
        static float max(float[] c) { return Math.max(c[0], Math.max(c[1], c[2])); }
        static float min(float[] c) { return Math.min(c[0], Math.min(c[1], c[2])); }
    }

    private static final class Asset {
        String pmxPath, faceTexture, modelName, englishName;
        float pmxVersion;
        int sourceVertexCount;
        String[] boneNames;
        final List<Vertex> vertices = new ArrayList<>();
        final List<Material> materials = new ArrayList<>();
    }

    private static final class Vertex {
        int sourceIndex;
        float[] sourcePosition, sourceNormal, sourceUv;
        float[] position, normal, uv, boneWeights;
        int[] boneIndices;
    }

    private static final class Material {
        int id, textureIndex;
        String name, texturePath;
        int[] indices;
    }

    private static final class DrawMaterial {
        final Material material;
        final int[] all, zero, eye, mixed;
        final float[] mask;
        DrawMaterial(Material material, int[] all, int[] zero, int[] eye, int[] mixed, float[] mask) {
            this.material = material; this.all = all; this.zero = zero; this.eye = eye; this.mixed = mixed; this.mask = mask;
        }
    }

    private static final class Sample {
        final String kind;
        final int materialId;
        final int[] indices;
        final int maskClass;
        final double u, v;
        final float[] albedo;
        final int pixelX, pixelY;
        float[] difference, before;
        Sample(String kind, int materialId, int[] indices, int maskClass, double u, double v,
               float[] albedo, int pixelX, int pixelY) {
            this.kind = kind; this.materialId = materialId; this.indices = indices; this.maskClass = maskClass;
            this.u = u; this.v = v; this.albedo = albedo; this.pixelX = pixelX; this.pixelY = pixelY;
        }
    }
}
