import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridge;
import com.shiroha.mmdskin.bridge.runtime.NativeTextureBridge;
import com.shiroha.mmdskin.render.material.MaterialTextureLoader;
import com.shiroha.mmdskin.render.material.MaterialRenderState;
import com.shiroha.mmdskin.render.material.ModelMaterial;
import com.shiroha.mmdskin.render.material.SphereTextureBinding;
import com.shiroha.mmdskin.render.material.SubMeshDrawHelper;
import com.shiroha.mmdskin.render.material.TranslucentDrawOrder;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import com.shiroha.mmdskin.texture.runtime.TextureRepository;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

/** 隐藏 GLFW 上下文中的生产 Toon 球面贴图回归。 */
public final class Probe {
    private static final int SIZE = 64;
    private static final Path OUT = Path.of("build/sphere-texture-probe");
    private static ToonShaderCpu shader;
    private static int[] fboTextures = new int[4];
    private static int fbo, depth, vao, ebo, positionBuffer, normalBuffer, uvBuffer;
    private static int diffuseBlack, diffuseColor, sphereColor, sphereBlack;
    private static int meshIndexCount;
    private static int[] meshIndices;
    private static int drawCalls;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        check(GLFW.glfwInit(), "GLFW 初始化");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "sphere-texture-probe", 0, 0);
        check(window != 0, "隐藏 GLFW 窗口");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            System.out.println("GL=" + GL46C.glGetString(GL46C.GL_VERSION));
            setup();
            initShader();
            testSphereModes();
            testSphereLighting();
            testMorphFactors();
            testBindingRestore();
            testExceptionalRestore();
            testTranslucentBatchKey();
            if (args.length >= 2 && args[0].equals("-PmxPath")) testPmx(Path.of(args[1]));
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "OpenGL 错误");
            System.out.println("PASS Toon SPA/SPH/关闭、alpha、morph、纹理状态恢复、半透明批次");
        } finally {
            cleanup();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void setup() {
        vao = GL46C.glGenVertexArrays();
        GL46C.glBindVertexArray(vao);
        ebo = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        Geometry geometry = sphereGeometry(32, 16);
        positionBuffer = attribute(0, 3, geometry.positions());
        normalBuffer = attribute(1, 3, geometry.normals());
        uvBuffer = attribute(2, 2, geometry.uvs());
        meshIndices = geometry.indices();
        meshIndexCount = meshIndices.length;
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, meshIndices, GL46C.GL_STATIC_DRAW);
        GL46C.glVertexAttrib1f(3, 0);

        fbo = GL46C.glGenFramebuffers();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        int[] buffers = new int[4];
        for (int i = 0; i < 4; i++) {
            fboTextures[i] = GL46C.glGenTextures();
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, fboTextures[i]);
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, SIZE, SIZE, 0,
                    GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, 0L);
            GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0 + i,
                    GL46C.GL_TEXTURE_2D, fboTextures[i], 0);
            buffers[i] = GL46C.GL_COLOR_ATTACHMENT0 + i;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL46C.glDrawBuffers(stack.ints(buffers));
        }
        depth = GL46C.glGenRenderbuffers();
        GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, depth);
        GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER, GL46C.GL_DEPTH_COMPONENT24, SIZE, SIZE);
        GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                GL46C.GL_RENDERBUFFER, depth);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "MRT FBO");
        GL46C.glViewport(0, 0, SIZE, SIZE);
        GL46C.glDisable(GL46C.GL_CULL_FACE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL46C.GL_LEQUAL);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);

        diffuseBlack = texture(new byte[]{0, 0, 0, (byte) 255});
        diffuseColor = texture(new byte[]{(byte) 180, (byte) 120, (byte) 80, (byte) 128});
        sphereColor = texture(new byte[]{(byte) 80, (byte) 160, (byte) 240, 0});
        sphereBlack = texture(new byte[]{0, 0, 0, (byte) 255});
    }

    private record Geometry(float[] positions, float[] normals, float[] uvs, int[] indices) {}

    private static Geometry sphereGeometry(int slices, int rings) {
        List<Float> positions = new ArrayList<>(), normals = new ArrayList<>(), uvs = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        addSphereVertex(positions, normals, uvs, 0, 0);
        for (int ring = 1; ring <= rings; ring++) {
            float radius = ring / (float) rings;
            for (int slice = 0; slice < slices; slice++) {
                double angle = 2 * Math.PI * slice / slices;
                addSphereVertex(positions, normals, uvs,
                        radius * (float) Math.cos(angle), radius * (float) Math.sin(angle));
            }
        }
        for (int slice = 0; slice < slices; slice++) {
            indices.add(0); indices.add(1 + slice); indices.add(1 + (slice + 1) % slices);
        }
        for (int ring = 1; ring < rings; ring++) {
            int inner = 1 + (ring - 1) * slices, outer = 1 + ring * slices;
            for (int slice = 0; slice < slices; slice++) {
                int next = (slice + 1) % slices;
                int a = inner + slice, b = inner + next, c = outer + slice, d = outer + next;
                indices.add(a); indices.add(c); indices.add(b);
                indices.add(b); indices.add(c); indices.add(d);
            }
        }
        return new Geometry(toArray(positions), toArray(normals), toArray(uvs),
                indices.stream().mapToInt(Integer::intValue).toArray());
    }

    private static void addSphereVertex(List<Float> p, List<Float> n, List<Float> uv, float x, float y) {
        float z = (float) Math.sqrt(Math.max(0, 1 - x * x - y * y));
        p.add(x * .78f); p.add(y * .78f); p.add((1 - z) * .08f);
        n.add(x); n.add(y); n.add(z);
        uv.add((x + 1) * .5f); uv.add((y + 1) * .5f);
    }

    private static float[] toArray(List<Float> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }

    private static int attribute(int index, int components, float[] values) {
        int buffer = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, buffer);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, values, GL46C.GL_STATIC_DRAW);
        GL46C.glEnableVertexAttribArray(index);
        GL46C.glVertexAttribPointer(index, components, GL46C.GL_FLOAT, false, 0, 0L);
        return buffer;
    }

    private static void initShader() {
        shader = new ToonShaderCpu();
        check(shader.init(), "生产 ToonShaderCpu 编译");
        shader.useMain();
        shader.setSampler0(0);
        shader.setLightDirection(0, 0, 1);
        shader.setLightIntensity(1);
        shader.setToonLevels(2);
        shader.setTransitionSoftness(0);
        shader.setRimLight(2, 0);
        shader.setSpecular(1, 0);
        shader.setShadowColor(1, 1, 1);
        shader.setAlphaCutoff(0.001f);
        shader.setGlobalAlpha(1);
        FloatBuffer identity = ByteBuffer.allocateDirect(16 * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        new Matrix4f().get(identity);
        shader.setProjectionMatrix(identity);
        shader.setModelViewMatrix(identity);
        uniform1i("AlphaPass", 0);
        uniform1i("SphereTexture", 2);
        uniform4f("SphereMul", 1, 1, 1, 1);
        uniform4f("SphereAdd", 0, 0, 0, 0);
        uniform3f("MaterialSpecular", 0, 0, 0);
        uniform1f("MaterialSpecularPower", 1);
        GL46C.glDisable(GL46C.GL_BLEND);
    }

    private static void testSphereModes() throws Exception {
        ModelMaterial material = material(diffuseBlack, sphereColor, 2);
        material.sphereMode = 0;
        float[] baseline = draw(material, null, 1);
        check(maxRgb(baseline) < 0.03f, "黑色主贴图基线应接近黑色: " + arr(baseline));
        BufferedImage baseImage = readImage();
        material.sphereMode = 2;
        float[] spa = draw(material, null, 1);
        BufferedImage spaImage = readImage();
        check(spa[2] > 0.25f && spa[1] > 0.15f, "SPA 加法应显示有色球面层: " + arr(spa));
        near(spa[3], 1, 0.02f, "SPA alpha=0 仍不得改变不透明表面 alpha");

        material.sphereMode = 1;
        material.tex = diffuseColor;
        material.hasAlpha = true;
        material.hasTranslucentPixels = true;
        material.sphereMode = 0;
        float[] beforeMultiply = draw(material, null, 1);
        BufferedImage beforeImage = readImage();
        material.sphereMode = 1;
        float[] sph = draw(material, null, 1);
        BufferedImage sphImage = readImage();
        near(sph[0] / beforeMultiply[0], 80 / 255f, 0.025f, "SPH R 乘法");
        near(sph[1] / beforeMultiply[1], 160 / 255f, 0.025f, "SPH G 乘法");
        near(sph[2] / beforeMultiply[2], 240 / 255f, 0.025f, "SPH B 乘法");
        near(sph[3], beforeMultiply[3], 0.02f, "SPH 保持 diffuse alpha");

        material.sphereMode = 0;
        float[] disabled = draw(material, null, 1);
        expectArray(disabled, beforeMultiply, 0.02f, "关闭恢复原始表面");
        writePanels(baseImage, spaImage, beforeImage, sphImage, readImage());
        System.out.println("PASS 关闭/SPA/SPH/alpha; SPA=" + arr(spa) + " SPH=" + arr(sph));
    }

    private static void testSphereLighting() {
        ModelMaterial material = material(diffuseBlack, sphereColor, 2);
        float[] bright = draw(material, null, 1);
        try {
            shader.setLightIntensity(.2f);
            float[] dim = draw(material, null, 1);
            for (int i = 0; i < 3; i++) {
                near(dim[i], bright[i] * .2f, .015f, "SPA 随环境照明衰减通道 " + i);
            }
            near(dim[3], bright[3], .01f, "照明不改变 SPA alpha");
            shader.setLightIntensity(0);
            check(maxRgb(draw(material, null, 1)) < .01f, "SPA 在无照明时不得自行发亮");

            // 方块光仍保留球面层，并使用相同的暖色照明。
            shader.setLightIntensity(.25f);
            uniform1i("ToonGenericEnvironment", 1);
            GL46C.glUniform2f(GL46C.glGetUniformLocation(shader.getMainProgram(), "ToonLightmap"),
                    248 / 256f, 8 / 256f);
            float[] torch = draw(material, null, 1);
            float[] tint = {1, .82f, .65f};
            for (int i = 0; i < 3; i++) {
                near(torch[i], bright[i] * .25f * tint[i], .015f, "SPA 方块光色调通道 " + i);
            }
        } finally {
            uniform1i("ToonGenericEnvironment", 0);
            shader.setLightIntensity(1);
        }
        System.out.println("PASS SPA 暗处衰减、无照明、方块光色调与 alpha");
    }

    private static void testMorphFactors() {
        ModelMaterial material = material(diffuseBlack, sphereBlack, 2);
        ByteBuffer morph = ByteBuffer.allocateDirect(56 * Float.BYTES).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 56; i++) morph.putFloat(i * Float.BYTES, i < 28 ? 1 : 0);
        morph.putFloat(20 * Float.BYTES, .5f); // sphereMul.r
        morph.putFloat(21 * Float.BYTES, .25f);
        morph.putFloat(22 * Float.BYTES, .75f);
        morph.putFloat(23 * Float.BYTES, 1);
        morph.putFloat(48 * Float.BYTES, .1f); // sphereAdd.rgb
        morph.putFloat(49 * Float.BYTES, .2f);
        morph.putFloat(50 * Float.BYTES, .3f);
        morph.putFloat(51 * Float.BYTES, .5f);
        MaterialRenderState state = new MaterialRenderState();
        state.resolve(material, morph, 0, 1);
        near(state.sphereMul[0], .5f, .001f, "morph 偏移20乘色");
        near(state.sphereAdd[2], .3f, .001f, "morph 偏移50加色");
        float[] color = draw(material, morph, 1);
        check(color[0] > 0.08f && color[1] > 0.15f && color[2] > 0.25f,
                "SPA morph 因子进入生产 shader: " + arr(color));
        System.out.println("PASS MaterialRenderState 的 20..23/48..51 sphere morph 因子");
    }

    private static void testBindingRestore() {
        int program = shader.getMainProgram();
        int maxUnits = GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_IMAGE_UNITS);
        int selected = maxUnits - 2;
        int priorActive = GL46C.GL_TEXTURE0 + 5;
        int sentinel = texture(new byte[]{9, 8, 7, 6});
        int sampler = GL46C.glGenSamplers();
        GL46C.glSamplerParameteri(sampler, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + selected);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, sentinel);
        GL46C.glBindSampler(selected, sampler);
        GL46C.glActiveTexture(priorActive);
        uniform1i("Sampler0", maxUnits - 1); // 模拟占用最高纹理单元的外部 sampler。
        uniform1i("SphereTexture", 2);
        try (SphereTextureBinding binding = new SphereTextureBinding(false)) {
            check(GL46C.glGetUniformi(program, GL46C.glGetUniformLocation(program, "SphereTexture")) == selected,
                    "球面 sampler 避开最高已占用单元");
            check(GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE) == priorActive, "构造时恢复 active texture");
            binding.apply(material(diffuseBlack, sphereColor, 2), new MaterialRenderState());
            check(GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, selected) == 0, "球面绘制临时清除外部 sampler");
        }
        check(GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE) == priorActive, "关闭恢复 active texture");
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + selected);
        check(GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D) == sentinel, "关闭恢复纹理绑定");
        check(GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, selected) == sampler, "关闭恢复 sampler 绑定");
        check(GL46C.glGetUniformi(program, GL46C.glGetUniformLocation(program, "SphereTexture")) == 2,
                "关闭恢复 sampler uniform");
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        uniform1i("Sampler0", 0);
        GL46C.glBindSampler(selected, 0);
        GL46C.glDeleteSamplers(sampler);
        GL46C.glDeleteTextures(sentinel);
        System.out.println("PASS 最高活动 sampler 占用时避让并恢复纹理/sampler/uniform/active unit");
    }

    private static void testExceptionalRestore() {
        int selected = GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_IMAGE_UNITS) - 1;
        int sentinel = texture(new byte[]{4, 3, 2, 1});
        int sampler = GL46C.glGenSamplers();
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + selected);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, sentinel);
        GL46C.glBindSampler(selected, sampler);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        uniform1i("SphereTexture", 2);
        ModelMaterial material = material(diffuseColor, sphereColor, 2);
        material.hasAlpha = true;
        ByteBuffer submeshes = oneSubmesh(0, 0, 3, .5f);
        boolean threw = false;
        try {
            SubMeshDrawHelper.draw(submeshes, 1, 4, GL46C.GL_UNSIGNED_INT, i -> material.tex,
                    (i, alpha) -> alpha, new ModelMaterial[]{material}, null, false,
                    i -> { throw new IllegalStateException("probe injected"); });
        } catch (IllegalStateException expected) {
            threw = expected.getMessage().equals("probe injected");
        }
        check(threw, "异常路径执行到 beforeDraw");
        check(GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE) == GL46C.GL_TEXTURE0, "helper 异常后 active unit");
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + selected);
        check(GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D) == sentinel, "异常后恢复球面纹理绑定");
        check(GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, selected) == sampler, "异常后恢复球面 sampler");
        check(GL46C.glGetUniformi(shader.getMainProgram(), GL46C.glGetUniformLocation(shader.getMainProgram(),
                "SphereTexture")) == 2, "异常后恢复 sphere sampler uniform");
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindSampler(selected, 0);
        GL46C.glDeleteSamplers(sampler);
        GL46C.glDeleteTextures(sentinel);
        System.out.println("PASS SubMeshDrawHelper 异常退出恢复球面 GL 状态");
    }

    private static void testTranslucentBatchKey() {
        ModelMaterial a = material(diffuseColor, sphereColor, 2);
        ModelMaterial b = material(diffuseColor, sphereBlack, 1);
        a.hasAlpha = b.hasAlpha = true;
        ByteBuffer data = ByteBuffer.allocateDirect(40).order(ByteOrder.nativeOrder());
        data.position(0);
        putSubmesh(data, 0, 0, 3, .5f);
        data.position(20);
        putSubmesh(data, 1, 3, 3, .5f);
        ByteBuffer positions = floats(-.5f, 0, 0, .5f, 0, 0, 0, .5f, 0,
                -.5f, 0, .2f, .5f, 0, .2f, 0, .5f, .2f);
        ByteBuffer indices = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 6; i++) indices.putInt(i * 4, i);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, indices, GL46C.GL_STATIC_DRAW);
        try (TranslucentDrawOrder order = new TranslucentDrawOrder()) {
            FloatBuffer matrix = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
            new Matrix4f().get(matrix);
            var inputs = TranslucentDrawOrder.Inputs.cpu(positions, positionBuffer, 6, matrix, null,
                    1, false, null, 0, false);
            boolean prepared = order.prepare(inputs, data, 2, 4, new ModelMaterial[]{a, b}, null,
                    (id, alpha) -> alpha);
            check(prepared && order.drawCount() == 2, "不同 sphere 材质保留两个透明排序批次: " + order.drawCount());
            int sortedDrawCount = order.drawCount();
            drawCalls = 0;
            SubMeshDrawHelper.draw(data, 2, 4, GL46C.GL_UNSIGNED_INT, id -> diffuseColor,
                    (id, alpha) -> alpha, new ModelMaterial[]{a, b}, null, false,
                    id -> drawCalls++, order, inputs);
            check(drawCalls >= 2, "透明 sphere 材质分别进入生产绘制");
            System.out.println("PASS 透明排序 drawCount=" + sortedDrawCount + ", production callbacks=" + drawCalls);
        }
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, meshIndices, GL46C.GL_STATIC_DRAW);
    }

    private static float[] draw(ModelMaterial material, ByteBuffer morphs, float alpha) {
        RenderSystem.enableBlend();
        clear();
        ByteBuffer data = oneSubmesh(0, 0, meshIndexCount, alpha);
        SubMeshDrawHelper.draw(data, 1, 4, GL46C.GL_UNSIGNED_INT, id -> material.tex,
                (id, base) -> base, new ModelMaterial[]{material}, morphs, false, id -> {});
        GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        FloatBuffer pixel = ByteBuffer.allocateDirect(4 * Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        GL46C.glReadPixels(SIZE / 2, SIZE / 2, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, pixel);
        return new float[]{pixel.get(0), pixel.get(1), pixel.get(2), pixel.get(3)};
    }

    private static BufferedImage readImage() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
        GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        GL46C.glReadPixels(0, 0, SIZE, SIZE, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int i = (y * SIZE + x) * 4;
            int rgba = (pixels.get(i + 3) & 255) << 24 | (pixels.get(i) & 255) << 16
                    | (pixels.get(i + 1) & 255) << 8 | (pixels.get(i + 2) & 255);
            image.setRGB(x, SIZE - 1 - y, rgba);
        }
        return image;
    }

    private static void testPmx(Path pmx) throws Exception {
        check(Files.isRegularFile(pmx), "PMX 文件存在: " + pmx);
        NativeFunc nativeFunc = NativeFunc.GetInst();
        long model = nativeFunc.LoadModelPMX(pmx.toAbsolutePath().toString(),
                pmx.toAbsolutePath().getParent().toString(), 1);
        check(model != 0, "JNI 加载 PMX");
        try {
            int count = Math.toIntExact(nativeFunc.GetMaterialCount(model));
            int pearl = -1;
            for (int i = 0; i < count; i++) {
                if ("珍珠".equals(nativeFunc.GetMaterialName(model, i))) pearl = i;
            }
            check(pearl >= 0, "PMX 存在‘珍珠’材质");
            int mode = nativeFunc.GetMaterialSpTextureMode(model, pearl);
            String spherePath = nativeFunc.GetMaterialSpTex(model, pearl);
            check(mode == 2, "珍珠材质为 SPA 模式 2，实际=" + mode);
            check(spherePath != null && spherePath.toLowerCase().endsWith("珍珠.png"),
                    "珍珠 SPA 路径: " + spherePath);
            Path imagePath = resolveAsset(pmx, spherePath);
            check(Files.isRegularFile(imagePath), "球面 PNG 文件存在: " + imagePath);
            check(resolveAsset(pmx, nativeFunc.GetMaterialTex(model, pearl)) != null,
                    "PMX 珍珠材质 diffuse 路径已读取");
            TextureRepository.Init();
            TextureRepository.configureRuntimeCollaborators(new NativeTextureBridge());
            List<String> keys = new ArrayList<>();
            try {
                ModelMaterial material = MaterialTextureLoader.loadMaterial(
                        new NativeRuntimeBridge(), model, pearl, keys);
                check(material.sphereMode == 2 && material.sphereTex > 0,
                        "MaterialTextureLoader 将 PMX SPA 路径加载为 sphereTex");
                check(material.sphereTexturePath.equals(spherePath), "生产材质保留球面资源路径");
                Path diffusePath = resolveAsset(pmx, material.texturePath);
                check(Files.isRegularFile(diffusePath), "生产材质 diffuse 资源存在: " + diffusePath);
                // 合成球壳没有 PMX 网格 UV，使用不透明黑底隔离真实 SPA 层。
                material.tex = diffuseBlack;
                material.hasAlpha = false;
                material.hasTranslucentPixels = false;
                material.sphereMode = 0;
                float[] before = draw(material, null, 1);
                BufferedImage beforeImage = readImage();
                material.sphereMode = 2;
                float[] after = draw(material, null, 1);
                BufferedImage afterImage = readImage();
                ImageIO.write(afterImage, "png", OUT.resolve("pearl-material-after.png").toFile());
                System.out.println("PMX pearl material: mode=" + material.sphereMode + " tex=" + material.tex
                        + " sphereTex=" + material.sphereTex + " spherePath=" + material.sphereTexturePath
                        + " diffusePath=" + material.texturePath + " before=" + arr(before) + " after=" + arr(after));
                check(maxRgb(after) > 0.03f, "真实材质 PNG 在 Toon 生产着色器中有 sphere 颜色: " + arr(after));
                near(after[3], before[3], 0.015f, "真实 SPA PNG 不改变材质 alpha");
                writePanels(beforeImage, afterImage);
                System.out.println("PASS 珍珠 mode=2 path=" + spherePath + " diffuse=" + diffusePath
                        + " before=" + arr(before) + " after=" + arr(after));
            } finally {
                TextureRepository.releaseAll(keys);
                TextureRepository.Cleanup();
            }
        } finally {
            nativeFunc.DeleteModel(model);
        }
    }

    private static Path resolveAsset(Path pmx, String path) {
        Path absolute = Path.of(path);
        if (absolute.isAbsolute() && Files.isRegularFile(absolute)) return absolute;
        Path relative = pmx.toAbsolutePath().getParent().resolve(path).normalize();
        if (Files.isRegularFile(relative)) return relative;
        String basename = Path.of(path.replace('\\', '/')).getFileName().toString();
        try (var paths = Files.walk(pmx.toAbsolutePath().getParent(), 5)) {
            return paths.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().equalsIgnoreCase(basename))
                    .findFirst().orElse(relative);
        } catch (Exception ignored) {
            return relative;
        }
    }

    private static ModelMaterial material(int diffuse, int sphere, int mode) {
        ModelMaterial material = new ModelMaterial();
        material.tex = diffuse;
        material.sphereTex = sphere;
        material.sphereMode = mode;
        material.diffuse = new float[]{1, 1, 1, 1};
        material.ambient = new float[]{1, 1, 1};
        return material;
    }

    private static ByteBuffer oneSubmesh(int id, int first, int count, float alpha) {
        ByteBuffer data = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());
        putSubmesh(data, id, first, count, alpha);
        return data;
    }

    private static void putSubmesh(ByteBuffer data, int id, int first, int count, float alpha) {
        int base = data.position();
        data.putInt(base, id).putInt(base + 4, first).putInt(base + 8, count)
                .putFloat(base + 12, alpha).put(base + 16, (byte) 1).put(base + 17, (byte) 1);
    }

    private static ByteBuffer floats(float... values) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(values.length * Float.BYTES).order(ByteOrder.nativeOrder());
        for (int i = 0; i < values.length; i++) bytes.putFloat(i * Float.BYTES, values[i]);
        return bytes;
    }

    private static int texture(byte[] rgba) {
        int texture = GL46C.glGenTextures();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
        ByteBuffer pixels = ByteBuffer.allocateDirect(4).put(rgba);
        pixels.flip();
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, 1, 1, 0,
                GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, pixels);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        return texture;
    }

    private static void clear() {
        RenderSystem.depthMask(true);
        GL46C.glClearColor(0, 0, 0, 0);
        GL46C.glClearDepth(1);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glBindVertexArray(vao);
        shader.useMain();
    }

    private static void writePanels(BufferedImage... panels) throws Exception {
        BufferedImage result = new BufferedImage(SIZE * panels.length, SIZE, BufferedImage.TYPE_INT_ARGB);
        var graphics = result.createGraphics();
        for (int i = 0; i < panels.length; i++) graphics.drawImage(panels[i], i * SIZE, 0, null);
        graphics.dispose();
        ImageIO.write(result, "png", OUT.resolve("sphere-ab.png").toFile());
    }

    private static float maxRgb(float[] color) { return Math.max(color[0], Math.max(color[1], color[2])); }
    private static String arr(float[] value) {
        return String.format("[%.3f,%.3f,%.3f,%.3f]", value[0], value[1], value[2], value[3]);
    }
    private static void expectArray(float[] a, float[] b, float delta, String label) {
        for (int i = 0; i < 4; i++) near(a[i], b[i], delta, label + " channel " + i);
    }
    private static void near(float actual, float expected, float delta, String label) {
        check(Math.abs(actual - expected) <= delta, label + ": " + actual + " != " + expected);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static int location(String name) { return GL46C.glGetUniformLocation(shader.getMainProgram(), name); }
    private static void uniform1i(String name, int value) { int l = location(name); if (l >= 0) GL46C.glUniform1i(l, value); }
    private static void uniform1f(String name, float value) { int l = location(name); if (l >= 0) GL46C.glUniform1f(l, value); }
    private static void uniform3f(String name, float x, float y, float z) {
        int l = location(name); if (l >= 0) GL46C.glUniform3f(l, x, y, z);
    }
    private static void uniform4f(String name, float x, float y, float z, float w) {
        int l = location(name); if (l >= 0) GL46C.glUniform4f(l, x, y, z, w);
    }

    private static void cleanup() {
        if (shader != null) shader.cleanup();
        if (fbo != 0) GL46C.glDeleteFramebuffers(fbo);
        if (depth != 0) GL46C.glDeleteRenderbuffers(depth);
        GL46C.glDeleteTextures(fboTextures);
        GL46C.glDeleteTextures(new int[]{diffuseBlack, diffuseColor, sphereColor, sphereBlack});
        GL46C.glDeleteBuffers(new int[]{ebo, positionBuffer, normalBuffer, uvBuffer});
        if (vao != 0) GL46C.glDeleteVertexArrays(vao);
    }
}
