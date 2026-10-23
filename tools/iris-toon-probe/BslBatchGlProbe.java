import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.material.ModelMaterial;
import com.shiroha.mmdskin.render.material.SubMeshDrawHelper;
import com.shiroha.mmdskin.render.material.TranslucentDrawOrder;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import com.shiroha.mmdskin.render.shader.TranslucentBatchShader;
import com.shiroha.mmdskin.texture.runtime.TextureAlphaCoverage;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** 比较真实 BSL 主体程序的普通与合批颜色、透明和深度输出。 */
public final class BslBatchGlProbe {
    private static final int SIZE = 96, MATERIALS = 6;
    private static final Pattern BUFFERS = Pattern.compile("DRAWBUFFERS\\s*:\\s*([0-9]+)|RENDERTARGETS\\s*:\\s*([0-9]+(?:\\s*,\\s*[0-9]+)*)");
    private final ByteBuffer positions = buffer(MATERIALS * 4 * 12);
    private final ByteBuffer uvs = buffer(MATERIALS * 4 * 8);
    private final ByteBuffer runs = buffer(MATERIALS * 20);
    private final FloatBuffer matrix = buffer(64).asFloatBuffer();
    private final FloatBuffer projection = buffer(64).asFloatBuffer();
    private final ModelMaterial[] materials = new ModelMaterial[MATERIALS];
    private int vao, vbo, ebo;
    private int lastBatchWindows;

    private static ByteBuffer buffer(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        check(GLFW.glfwInit(), "GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "BSL Toon batch probe", 0, 0);
        check(window != 0, "GL 4.3 context");
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            RenderSystem.initRenderThread();
            new BslBatchGlProbe().run(Path.of(args[0]));
        } finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }

    private void run(Path packPath) throws Exception {
        check(Files.isRegularFile(packPath), "missing BSL pack: " + packPath);
        Map<String, String> options = Probe.options(Path.of(packPath + ".txt"));
        try (ZipFile zip = new ZipFile(packPath.toFile())) {
            for (String dimension : List.of("world0", "world-1", "world1")) {
                ToonOutputProfile profile = profile(zip, options, dimension);
                check(profile.isSupported() && profile.id().startsWith("bsl:entities:"),
                        dimension + " BSL output contract: " + profile.reason());
                compareDimension(profile, dimension);
            }
        } finally { release(); }
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "final GL error");
        System.out.println("BslBatchGlProbe PASS: BSL 10.1.8 world0/world-1/world1, RGBA and depth matched");
    }

    private static ToonOutputProfile profile(ZipFile zip, Map<String, String> options, String dimension) throws Exception {
        return profile(zip, options, dimension, "gbuffers_entities");
    }

    private static ToonOutputProfile profile(ZipFile zip, Map<String, String> options, String dimension,
                                             String program) throws Exception {
        String base = "shaders/" + dimension + "/" + program + ".";
        if (zip.getEntry(base + "fsh") == null) base = "shaders/" + program + ".";
        String fragment = JcppProcessor.glslPreprocessSource(Probe.include(zip, base + "fsh", options), defines());
        String vertex = JcppProcessor.glslPreprocessSource(Probe.include(zip, base + "vsh", options), defines());
        Matcher matcher = BUFFERS.matcher(fragment);
        String directive = null;
        boolean targets = false;
        while (matcher.find()) { targets = matcher.group(1) == null; directive = targets ? matcher.group(2) : matcher.group(1); }
        check(directive != null, dimension + " missing DRAWBUFFERS");
        int[] buffers = targets ? java.util.Arrays.stream(directive.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray()
                : directive.chars().map(value -> value - '0').toArray();
        return ToonOutputProfile.resolve("BSL_v10.1.8", program, fragment, vertex, buffers);
    }

    private static List<StringPair> defines() {
        return List.of(new StringPair("MC_VERSION", "12101"), new StringPair("IS_IRIS", "1"),
                new StringPair("IRIS_VERSION", "10814"));
    }

    private void compareDimension(ToonOutputProfile profile, String dimension) {
        System.setProperty("mmdskin.translucentBatch", "false");
        ToonShaderCpu ordinary = new ToonShaderCpu();
        check(ordinary.selectOutputProfile(profile), dimension + " ordinary Toon compile");
        System.clearProperty("mmdskin.translucentBatch");
        ToonShaderCpu batch = new ToonShaderCpu();
        check(batch.selectOutputProfile(profile), dimension + " batch Toon compile");
        check(batch.getMainProgram() > 0, dimension + " Toon program");
        release();
        setupGeometry();
        Image expected = draw(ordinary);
        batch.useMain();
        check(TranslucentBatchShader.current() != null, dimension + " BSL-owned Toon batch registration");
        Image actual = draw(batch);
        check(lastBatchWindows > 0, dimension + " actual material-table batch was not submitted");
        compare(expected, actual, dimension);
        ordinary.cleanup(); batch.cleanup();
        System.out.println("PASS BSL " + dimension + " batch Toon: " + profile.id());
    }

    private Image draw(ToonShaderCpu shader) {
        shader.useMain();
        Matrix4f identity = new Matrix4f(); identity.get(matrix);
        shader.setProjectionMatrix(projection); shader.setModelViewMatrix(matrix);
        shader.setLightDirection(.2f, .5f, 1); shader.setLightIntensity(.8f);
        shader.setToonLevels(3); shader.setTransitionSoftness(.5f);
        shader.setShadowColor(.4f, .5f, .6f); shader.setSpecular(48, .2f);
        shader.setRimLight(2, .1f); shader.setAlphaCutoff(.001f); shader.setSampler0(0);
        shader.setEyeEmission(.3f);
        GL46C.glViewport(0, 0, SIZE, SIZE);
        GL46C.glEnable(GL46C.GL_BLEND);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
        GL46C.glEnable(GL46C.GL_DEPTH_TEST); GL46C.glDepthFunc(GL46C.GL_LEQUAL);
        GL46C.glDisable(GL46C.GL_CULL_FACE); GL46C.glDepthMask(true);
        GL46C.glClearColor(.08f, .12f, .18f, .1f);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glBindVertexArray(vao); GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        FloatBuffer view = buffer(64).asFloatBuffer(); new Matrix4f().get(view);
        try (TranslucentDrawOrder order = new TranslucentDrawOrder()) {
            var geometry = TranslucentDrawOrder.Inputs.cpu(positions, 0, MATERIALS * 4, view, null, 1,
                    false, uvs, 0, false);
            SubMeshDrawHelper.draw(runs, MATERIALS, 4, GL46C.GL_UNSIGNED_INT,
                    id -> materials[id].tex, (id, alpha) -> alpha, materials, null, false,
                    id -> shader.setEyeMaterialMask(materials[id].eyeEmissionMaskMode()), order, geometry);
            lastBatchWindows = order.batchWindowCount();
        }
        ByteBuffer rgba = buffer(SIZE * SIZE * 4);
        FloatBuffer depth = buffer(SIZE * SIZE * 4).asFloatBuffer();
        GL46C.glReadPixels(0, 0, SIZE, SIZE, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, rgba);
        GL46C.glReadPixels(0, 0, SIZE, SIZE, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, depth);
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "render GL error");
        return new Image(rgba, depth);
    }

    private void setupGeometry() {
        positions.clear(); uvs.clear(); runs.clear();
        new Matrix4f().get(matrix);
        new Matrix4f().scaling(1, 1, -1).get(projection);
        vao = GL46C.glGenVertexArrays(); GL46C.glBindVertexArray(vao);
        ByteBuffer vertices = buffer(MATERIALS * 4 * 36), indices = buffer(MATERIALS * 6 * 4);
        for (int material = 0; material < MATERIALS; material++) {
            float inset = material % 2 == 0 ? 0 : .15f;
            float z = -.2f - material * .1f;
            float[][] corners = {{-.9f + inset, -.8f + inset, 0, 0}, {.9f - inset, -.8f + inset, 1, 0},
                    {.9f - inset, .8f - inset, 1, 1}, {-.9f + inset, .8f - inset, 0, 1}};
            int firstVertex = material * 4;
            for (float[] corner : corners) {
                float x = corner[0], y = corner[1], u = corner[2], v = corner[3];
                vertices.putFloat(x).putFloat(y).putFloat(z).putFloat(0).putFloat(0).putFloat(1)
                        .putFloat(u).putFloat(v).putFloat(material == 0 ? 1 : 0);
                positions.putFloat(x).putFloat(y).putFloat(z); uvs.putFloat(u).putFloat(v);
            }
            for (int index : new int[]{0, 1, 2, 0, 2, 3}) indices.putInt(firstVertex + index);
            ModelMaterial mat = materials[material] = new ModelMaterial();
            mat.name = material == 0 ? "eye" : "body"; mat.hasAlpha = true; mat.hasTranslucentPixels = true;
            mat.diffuse = new float[]{.65f + material * .04f, .8f, .9f, 1};
            mat.specular = new float[]{.12f, .18f, .24f}; mat.specularPower = 32;
            ByteBuffer image = buffer(8 * 8 * 4);
            for (int pixel = 0; pixel < 64; pixel++) image.put((byte)(40 + material * 29))
                    .put((byte)(70 + material * 23)).put((byte)(210 - material * 17))
                    .put((byte)(pixel % 4 == 0 ? 0 : pixel % 3 == 0 ? 132 : 255));
            image.flip(); mat.alphaCoverage = TextureAlphaCoverage.fromRgba(image, 8, 8);
            mat.tex = GL46C.glGenTextures(); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, mat.tex);
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, 8, 8, 0,
                    GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, image);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
            int base = material * 20;
            runs.putInt(base, material).putInt(base + 4, material * 6).putInt(base + 8, 6)
                    .putFloat(base + 12, material == 0 ? .82f : .66f)
                    .put(base + 16, (byte)1).put(base + 17, (byte)(material % 3 == 0 ? 1 : 0));
        }
        vertices.flip(); indices.flip(); positions.flip(); uvs.flip();
        vbo = GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, vbo);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, vertices, GL46C.GL_STATIC_DRAW);
        for (int i = 0; i < 4; i++) {
            GL46C.glEnableVertexAttribArray(i);
            GL46C.glVertexAttribPointer(i, i == 0 || i == 1 ? 3 : i == 2 ? 2 : 1,
                    GL46C.GL_FLOAT, false, 36, i == 0 ? 0 : i == 1 ? 12 : i == 2 ? 24 : 32);
        }
        ebo = GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, indices, GL46C.GL_STATIC_DRAW);
    }

    private static void compare(Image expected, Image actual, String dimension) {
        int max = 0, changed = 0, depthChanged = 0;
        for (int i = 0; i < expected.rgba.capacity(); i++) {
            int delta = Math.abs((expected.rgba.get(i) & 255) - (actual.rgba.get(i) & 255));
            max = Math.max(max, delta); if (delta > 1) changed++;
        }
        for (int i = 0; i < expected.depth.capacity(); i++)
            if (Math.abs(expected.depth.get(i) - actual.depth.get(i)) > 1e-6f) depthChanged++;
        check(max <= 1 && depthChanged == 0, dimension + " RGBA/depth mismatch: max=" + max
                + ", changed=" + changed + ", depth=" + depthChanged);
    }

    private void release() {
        for (ModelMaterial material : materials) if (material != null && material.tex > 0) GL46C.glDeleteTextures(material.tex);
        if (vbo > 0) GL46C.glDeleteBuffers(vbo);
        if (ebo > 0) GL46C.glDeleteBuffers(ebo);
        if (vao > 0) GL46C.glDeleteVertexArrays(vao);
    }

    private record Image(ByteBuffer rgba, FloatBuffer depth) { }
}
