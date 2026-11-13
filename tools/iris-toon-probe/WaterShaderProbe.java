import com.shiroha.mmdskin.compat.iris.IrisWaterShaderPatcher;
import com.shiroha.mmdskin.compat.iris.IrisToonCompat;
import com.shiroha.mmdskin.render.material.ModelMaterial;
import com.shiroha.mmdskin.render.material.SubMeshDrawHelper;
import com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyPipeline;
import com.shiroha.mmdskin.render.scene.RenderScene;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTestFunction;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Validate the 1.21 Iris transform against actual Complementary/BSL water programs. */
public final class WaterShaderProbe {
    private static final Pattern BUFFERS = Pattern.compile(
            "DRAWBUFFERS\\s*:\\s*([0-9]+)|RENDERTARGETS\\s*:\\s*([0-9]+(?:\\s*,\\s*[0-9]+)*)");
    private static final Pattern INCLUDE = Pattern.compile(
            "(?m)^\\s*#include\\s+\"([^\"]+)\"[^\\r\\n]*");
    private static final Pattern DEFINE = Pattern.compile(
            "(?m)^(\\s*)(//\\s*)?#define\\s+(\\w+)([^\\r\\n]*)");

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "usage: WaterShaderProbe <pack.zip> <mcVersion> <irisVersion> required");
        }
        if (!args[3].equals("required")) {
            throw new IllegalArgumentException("WaterShaderProbe adapter policy must be required");
        }
        String minecraftVersion = args[1];
        String irisVersion = args[2];
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = GLFW.glfwCreateWindow(64, 64, "Iris 1.21 water", 0, 0);
        if (window == 0) throw new AssertionError("GL 4.3");
        try (ZipFile zip = new ZipFile(args[0])) {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            Method patchSodium = findPatchSodium();
            verifyWaterStateEntryPoints();
            System.out.println("PASS Iris 1.21 water signatures: patchSodiumArgs="
                    + patchSodium.getParameterCount() + ", SodiumShader=setupState/resetState");

            List<StringPair> defines = List.of(new StringPair("MC_VERSION", minecraftVersion),
                    new StringPair("IS_IRIS", "1"), new StringPair("IRIS_VERSION", irisVersion));
            Map<String, String> options = options(Path.of(args[0] + ".txt"));
            Path output = Path.of("build/iris-toon-probe");
            Files.createDirectories(output);
            System.out.println("GL=" + GL46C.glGetString(GL46C.GL_VERSION)
                    + " / " + GL46C.glGetString(GL46C.GL_RENDERER));
            for (WaterCase test : cases(zip, options)) {
                String dimension = test.dimension();
                String base = "shaders/" + dimension + "/gbuffers_water.";
                String vertex = JcppProcessor.glslPreprocessSource(
                        include(zip, base + "vsh", test.options()), defines);
                String fragment = JcppProcessor.glslPreprocessSource(
                        include(zip, base + "fsh", test.options()), defines);
                Map<PatchShaderType, String> nativeSources = transform(
                        patchSodium, "gbuffers_water", vertex, fragment);
                Files.writeString(output.resolve(packStem(args[0]) + "-" + test.label() + ".frag.glsl"),
                        nativeSources.get(PatchShaderType.FRAGMENT));

                int[] buffers = drawBuffers(fragment);
                String layout = java.util.Arrays.stream(buffers).mapToObj(Integer::toString)
                        .collect(java.util.stream.Collectors.joining());
                if (test.expectedLayout() != null && !test.expectedLayout().equals(layout)) {
                    throw new AssertionError(test.label() + " expected layout=" + test.expectedLayout()
                            + ", actual=" + layout);
                }
                var contract = IrisWaterShaderPatcher.diagnose("gbuffers_water", fragment);
                if (!contract.accepted()) {
                    throw new AssertionError(Path.of(args[0]).getFileName()
                            + " real water adapter rejected the active contract: " + contract.reason());
                }
                Map<PatchShaderType, String> adapted = IrisWaterShaderPatcher.patch(
                        "gbuffers_water", fragment, nativeSources);
                if (adapted == nativeSources) {
                    throw new AssertionError(Path.of(args[0]).getFileName()
                            + " real water adapter did not copy transformed sources");
                }
                int linkedProgram = link(adapted.get(PatchShaderType.VERTEX),
                        adapted.get(PatchShaderType.FRAGMENT));
                try {
                    if (GL46C.glGetUniformLocation(linkedProgram, "MmdWaterCapture") < 0) {
                        throw new AssertionError(Path.of(args[0]).getFileName()
                                + " water capture was optimized away");
                    }
                } finally {
                    GL46C.glDeleteProgram(linkedProgram);
                }
                System.out.println("PASS native water transform/link " + test.label()
                        + " buffers=" + java.util.Arrays.toString(buffers));
                RealWaterProbe.run(adapted.get(PatchShaderType.FRAGMENT), buffers,
                        Path.of(args[0]).getFileName().toString(),
                        contract.family().equals("Complementary early") ? 31000
                                : contract.family().equals("Complementary modern") ? 32000 : 1);
            }
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private record WaterCase(String dimension, String label, Map<String, String> options,
                             String expectedLayout) {}

    private static List<WaterCase> cases(ZipFile zip, Map<String, String> active) throws Exception {
        List<WaterCase> cases = new ArrayList<>();
        for (String dimension : List.of("world0", "world-1", "world1"))
            cases.add(new WaterCase(dimension, dimension, active, null));
        String waterSource = "";
        var waterEntry = zip.getEntry("shaders/program/gbuffers_water.glsl");
        if (waterEntry != null) {
            try (var stream = zip.getInputStream(waterEntry)) {
                waterSource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (waterSource.contains("DRAWBUFFERS:031") && waterSource.contains("DoFog(color.rgb,")) {
            for (String layout : List.of("03", "031", "038", "0318")) {
                Map<String, String> options = new HashMap<>(active);
                options.put("WATER_QUALITY", layout.contains("1") ? "3" : "2");
                options.put("MCBL_MAIN_DEFINE", layout.contains("8") ? "1" : "0");
                cases.add(new WaterCase("world0", "world0-layout-" + layout, options, layout));
            }
        } else if (waterSource.contains("DRAWBUFFERS:0369")) {
            for (String layout : List.of("036", "03648", "0369", "036489")) {
                Map<String, String> options = new HashMap<>(active);
                options.put("DETAIL_QUALITY", layout.contains("48") ? "3" : "2");
                options.put("BETTER_LABPBR", layout.contains("48") ? "true" : "false");
                options.put("RP_MODE", layout.contains("48") ? "3" : "0");
                options.put("COLORED_LIGHTING", "0");
                options.put("MCBL_MAIN_DEFINE", layout.endsWith("9") ? "1" : "0");
                cases.add(new WaterCase("world0", "world0-layout-" + layout, options, layout));
            }
        } else if (waterSource.contains("DRAWBUFFERS:036")) {
            for (String layout : List.of("03", "036", "0348", "03648")) {
                Map<String, String> options = new HashMap<>(active);
                options.put("DETAIL_QUALITY", layout.contains("6") ? "3" : "2");
                options.put("WORLD_SPACE_REFLECTIONS", layout.contains("48") ? "1" : "-1");
                options.put("WATER_REFLECT_QUALITY", layout.equals("0348") ? "0" : "2");
                cases.add(new WaterCase("world0", "world0-layout-" + layout, options, layout));
            }
        }
        return cases;
    }

    private static Method findPatchSodium() {
        for (Method method : TransformPatcher.class.getDeclaredMethods()) {
            if (method.getName().equals("patchSodium") && method.getParameterCount() == 8) return method;
        }
        throw new AssertionError("Iris 1.21 TransformPatcher.patchSodium 8-argument signature not found");
    }

    private static void verifyWaterStateEntryPoints() throws Exception {
        Class<?> shader = Class.forName("net.irisshaders.iris.pipeline.programs.SodiumShader");
        shader.getDeclaredMethod("setupState");
        shader.getDeclaredMethod("resetState");
    }

    @SuppressWarnings("unchecked")
    private static Map<PatchShaderType, String> transform(Method method, String name, String vertex,
                                                          String fragment) throws Exception {
        Object[] arguments = {name, vertex, null, null, null, fragment,
                new AlphaTest(AlphaTestFunction.GREATER, .001f), new Object2ObjectOpenHashMap<>()};
        return (Map<PatchShaderType, String>) method.invoke(null, arguments);
    }

    private static int[] drawBuffers(String source) {
        Matcher matcher = BUFFERS.matcher(source);
        String directive = null;
        boolean renderTargets = false;
        while (matcher.find()) {
            renderTargets = matcher.group(1) == null;
            directive = renderTargets ? matcher.group(2) : matcher.group(1);
        }
        if (directive == null) {
            throw new AssertionError("gbuffers_water missing DRAWBUFFERS/RENDERTARGETS");
        }
        return renderTargets
                ? java.util.Arrays.stream(directive.split(",")).map(String::trim)
                        .mapToInt(Integer::parseInt).toArray()
                : directive.chars().map(value -> value - '0').toArray();
    }

    private static String include(ZipFile zip, String entryName, Map<String, String> options) throws Exception {
        var entry = zip.getEntry(entryName);
        if (entry == null) throw new IllegalArgumentException("找不到 include: " + entryName);
        String source;
        try (var stream = zip.getInputStream(entry)) {
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher define = DEFINE.matcher(source);
        StringBuilder changed = new StringBuilder();
        while (define.find()) {
            String value = options.get(define.group(3));
            String replacement = define.group();
            if (value != null) {
                if (value.equals("true") || value.equals("false")) {
                    replacement = define.group(1) + (value.equals("false") ? "//" : "")
                            + "#define " + define.group(3) + define.group(4);
                } else {
                    replacement = define.group(1) + "#define " + define.group(3) + " " + value;
                }
            }
            define.appendReplacement(changed, Matcher.quoteReplacement(replacement));
        }
        define.appendTail(changed);
        Matcher includes = INCLUDE.matcher(changed.toString());
        StringBuilder expanded = new StringBuilder();
        while (includes.find()) {
            String name = includes.group(1);
            String path = name.startsWith("/") ? "shaders" + name
                    : entryName.substring(0, entryName.lastIndexOf('/') + 1) + name;
            includes.appendReplacement(expanded, Matcher.quoteReplacement(include(zip, path, options)));
        }
        includes.appendTail(expanded);
        return expanded.toString();
    }

    private static Map<String, String> options(Path path) throws Exception {
        Properties properties = new Properties();
        if (Files.exists(path)) {
            try (var stream = Files.newInputStream(path)) {
                properties.load(stream);
            }
        }
        Map<String, String> result = new HashMap<>();
        for (String name : properties.stringPropertyNames()) result.put(name, properties.getProperty(name));
        return result;
    }

    private static int link(String vertex, String fragment) {
        int vertexShader = compile(GL46C.GL_VERTEX_SHADER, vertex);
        int fragmentShader = compile(GL46C.GL_FRAGMENT_SHADER, fragment);
        int program = GL46C.glCreateProgram();
        try {
            GL46C.glAttachShader(program, vertexShader);
            GL46C.glAttachShader(program, fragmentShader);
            GL46C.glLinkProgram(program);
            if (GL46C.glGetProgrami(program, GL46C.GL_LINK_STATUS) == 0) {
                throw new AssertionError(GL46C.glGetProgramInfoLog(program));
            }
            return program;
        } catch (RuntimeException | Error failure) {
            GL46C.glDeleteProgram(program);
            throw failure;
        } finally {
            GL46C.glDeleteShader(vertexShader);
            GL46C.glDeleteShader(fragmentShader);
        }
    }

    private static int compile(int type, String source) {
        int shader = GL46C.glCreateShader(type);
        GL46C.glShaderSource(shader, source);
        GL46C.glCompileShader(shader);
        if (GL46C.glGetShaderi(shader, GL46C.GL_COMPILE_STATUS) == 0) {
            String log = GL46C.glGetShaderInfoLog(shader);
            GL46C.glDeleteShader(shader);
            throw new AssertionError(log);
        }
        return shader;
    }

    private static String packStem(String path) {
        String name = Path.of(path).getFileName().toString();
        return name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }

    /** Real transformed-water rendering and production shared-queue ordering. */
    private static final class RealWaterProbe {
        private static final int SIZE = 64;
        private static final float[] CHEST = {.2f, .1f, .05f, 1};
        private static int model;
        private static int solid;
        private static int water;
        private static int source;
        private static int stage;
        private static int white;
        private static int vao;
        private static int indices;
        private static int waterMaterial;
        private static int[] buffers = {0, 1};
        private static int[] textures;
        private static float[] waterPixel;
        private static float[] waterOutside;
        private static float[][] waterAuxiliary;

        static void run(String fragment, int[] drawBuffers, String packName, int material) throws Exception {
            validateBuffers(drawBuffers);
            WorldTransparencyPipeline.release();
            buffers = drawBuffers.clone();
            setup(buffers);
            boolean complementary = Pattern.compile("\\bflat\\s+in\\s+int\\s+mat\\s*;")
                    .matcher(fragment).find();
            waterMaterial = material;
            String vertex = complementary ? varyingVertex(fragment) : bslVertex();
            if (complementary) {
                check(vertex.contains("mat=" + waterMaterial + ";"),
                        "real water vertex must carry the actual water material id " + waterMaterial);
            }
            water = program(vertex, fragment);
            GL46C.glUseProgram(water);
            check(WorldTransparencyPipeline.beginWaterCapture() == null,
                    "no water redirection without a PMX frame queue");
            setUniforms();
            textures = bindTextures();
            bind(source);
            drawWaterBuffers();
            GL46C.glDisable(GL46C.GL_BLEND);
            GL46C.glDisable(GL46C.GL_DEPTH_TEST);
            GL46C.glUniform1f(GL46C.glGetUniformLocation(water, "Depth"), -.2f);
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "native water fixture setup");
            GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "native water reference draw");
            waterPixel = pixel(source, GL46C.GL_COLOR_ATTACHMENT0, 40);
            waterOutside = pixel(source, GL46C.GL_COLOR_ATTACHMENT0, 60);
            // Auxiliary MRTs use the same blending and clear colors as the capture run.
            clearWaterTargets();
            GL46C.glEnable(GL46C.GL_BLEND);
            GL46C.glBlendEquation(GL46C.GL_FUNC_ADD);
            GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                    GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
            GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
            waterAuxiliary = new float[buffers.length - 1][];
            for (int slot = 1; slot < buffers.length; slot++)
                waterAuxiliary[slot - 1] = pixel(source, GL46C.GL_COLOR_ATTACHMENT0 + slot, 40);
            for (float value : waterPixel) check(Float.isFinite(value), "finite real water output");
            check(waterPixel[3] > .001f, "real water alpha is nontrivial");
            System.out.println("Native " + packName + " water=" + java.util.Arrays.toString(waterPixel)
                    + " aux=" + java.util.Arrays.deepToString(waterAuxiliary)
                    + " buffers=" + java.util.Arrays.toString(buffers)
                    + " material=" + waterMaterial
                    + " textures=" + SIZE + "x" + SIZE);
            try {
                waterOrder(false);
                waterOrder(true);
            } finally {
                waterPixel = waterOutside = null;
                waterAuxiliary = null;
                GL46C.glDeleteTextures(textures);
                textures = null;
                GL46C.glDeleteProgram(water);
                GL46C.glDeleteProgram(model);
                GL46C.glDeleteProgram(solid);
                GL46C.glDeleteTextures(white);
                GL46C.glDeleteVertexArrays(vao);
                GL46C.glDeleteBuffers(indices);
                GL46C.glDeleteFramebuffers(source);
                GL46C.glDeleteFramebuffers(stage);
                WorldTransparencyPipeline.release();
            }
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "native water final GL state");
        }

        private static void validateBuffers(int[] drawBuffers) {
            check(drawBuffers != null && drawBuffers.length >= 2 && drawBuffers[0] == 0,
                    "water main output must start at color attachment 0");
            for (int i = 1; i < drawBuffers.length; i++) {
                check(drawBuffers[i] > 0 && drawBuffers[i] < 16,
                        "unsupported water auxiliary attachment: " + drawBuffers[i]);
            }
        }

        private static void setup(int[] drawBuffers) {
            vao = GL46C.glGenVertexArrays();
            GL46C.glBindVertexArray(vao);
            indices = GL46C.glGenBuffers();
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, indices);
            GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER,
                    buffer(12).putInt(0, 0).putInt(4, 1).putInt(8, 2), GL46C.GL_STATIC_DRAW);
            String vertex = """
                    #version 330 core
                    uniform float Depth;
                    uniform float Slope;
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2) * 2.0 - 1.0;
                        gl_Position = vec4(p, Depth + Slope * p.x, 1.0);
                    }
                    """;
            model = program(vertex, """
                    #version 330 core
                    uniform vec4 MaterialColor;
                    uniform int AlphaPass, DisableTransparency;
                    layout(location=0) out vec4 toonOutput0;
                    layout(location=1) out vec4 toonOutput1;
                    void main() {
                        float a = DisableTransparency != 0 ? 1.0 : MaterialColor.a;
                        if (a < .001 || gl_FragCoord.x >= 56.0) discard;
                        if (AlphaPass == 1 && a < .999) discard;
                        if (AlphaPass == 2 && a >= .999) discard;
                        toonOutput0 = vec4(MaterialColor.rgb, a);
                        toonOutput1 = vec4(.75);
                    }
                    """);
            solid = program(vertex, """
                    #version 330 core
                    uniform vec4 Color;
                    layout(location=0) out vec4 fragColor;
                    void main() {
                        fragColor = Color;
                    }
                    """);
            source = target(drawBuffers);
            stage = target(drawBuffers);
            int depth = attachment(source, GL46C.GL_DEPTH_ATTACHMENT);
            bind(stage);
            GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                    GL46C.GL_TEXTURE_2D, depth, 0);
            white = texture(GL46C.GL_RGBA8, 1);
            GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL46C.GL_RGBA,
                    GL46C.GL_UNSIGNED_BYTE, buffer(4).putInt(0, -1));
        }

        private static String bslVertex() {
            return """
                    #version 330 core
                    uniform float Depth;
                    out float iris_FogFragCoord, mat, dist;
                    out vec2 texCoord, lmCoord;
                    out vec3 normal, binormal, tangent, sunVec, upVec, eastVec, viewVector;
                    out vec4 color, vTexCoord, vTexCoordAM;
                    void main() {
                        vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2)*2.0-1.0;
                        gl_Position=vec4(p,Depth,1);
                        iris_FogFragCoord=0; mat=1; dist=1;
                        texCoord=p*.5+.5; lmCoord=vec2(.5,1);
                        normal=vec3(0,0,1); binormal=vec3(0,1,0); tangent=vec3(1,0,0);
                        sunVec=vec3(0,1,0); upVec=sunVec; eastVec=tangent; viewVector=normal;
                        color=vec4(.4,.6,.8,1); vTexCoord=vec4(.5,.5,0,0); vTexCoordAM=vec4(0,0,1,1);
                    }
                    """;
        }

        private static String varyingVertex(String fragment) {
            StringBuilder vertex = new StringBuilder("#version 430 core\nuniform float Depth;\n");
            StringBuilder assignments = new StringBuilder();
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            Matcher declaration = Pattern.compile(
                    "(?m)^\\s*(flat\\s+)?in\\s+([A-Za-z0-9_]+)\\s+([^;]+);").matcher(fragment);
            while (declaration.find()) {
                vertex.append(declaration.group(1) == null ? "" : declaration.group(1))
                        .append("out ").append(declaration.group(2)).append(' ')
                        .append(declaration.group(3)).append(";\n");
                for (String rawName : declaration.group(3).split(",")) {
                    String name = rawName.trim();
                    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || !names.add(name)) continue;
                    String value = switch (declaration.group(2)) {
                        case "int", "uint" -> name.equals("mat") ? Integer.toString(waterMaterial) : "1";
                        case "float" -> name.contains("Fog") ? "0.0" : "1.0";
                        case "vec2" -> name.equals("texCoord") || name.equals("lmCoord")
                                ? "vec2(0.5)" : "vec2(0.0)";
                        case "vec3" -> name.equals("normal") || name.equals("viewVector")
                                ? "vec3(0.0,0.0,1.0)" : "vec3(1.0,0.0,0.0)";
                        case "vec4" -> name.equals("glColor")
                                ? "vec4(0.4,0.6,0.8,1.0)" : "vec4(0.5)";
                        case "ivec2" -> "ivec2(0)";
                        default -> null;
                    };
                    if (value != null) {
                        assignments.append("    ").append(name).append('=').append(value).append(";\n");
                    }
                }
            }
            vertex.append("void main(){\n")
                    .append("vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2)*2.0-1.0;\n")
                    .append("gl_Position=vec4(p,Depth,1.0);\n")
                    .append(assignments).append("}\n");
            return vertex.toString();
        }

        private static void setUniforms() {
            float[] identity = {
                    1, 0, 0, 0,
                    0, 1, 0, 0,
                    0, 0, 1, 0,
                    0, 0, 0, 1
            };
            for (String name : new String[]{"iris_ModelViewMatrix", "iris_ProjectionMatrix",
                    "iris_ProjectionMatrixInverse", "iris_ModelViewMatrixInverse", "gbufferProjection",
                    "gbufferProjectionInverse", "gbufferModelView", "gbufferModelViewInverse",
                    "shadowProjection", "shadowModelView", "gbufferPreviousProjection",
                    "gbufferPreviousModelView", "iris_LightmapTextureMatrix"}) {
                GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(water, name), false, identity);
            }
            for (String name : new String[]{"viewWidth", "viewHeight"}) uniform(name, SIZE);
            uniform("near", .05f);
            uniform("far", 256);
            uniform("renderDistance", 256);
            uniform("aspectRatio", 1);
            uniform("timeAngle", .25f);
            uniform("screenBrightness", 1);
            uniform("timeBrightness", 1);
            uniform("shadowFade", 1);
            uniform("iris_FogEnd", 512);
            uniform("iris_currentAlphaTest", .001f);
            GL46C.glUniform2i(GL46C.glGetUniformLocation(water, "eyeBrightnessSmooth"), 120, 240);
            GL46C.glUniform3f(GL46C.glGetUniformLocation(water, "cameraPosition"), 0, 64, 0);
        }

        private static int[] bindTextures() {
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
            int waterSample = texture(GL46C.GL_RGBA8, 1);
            GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL46C.GL_RGBA,
                    GL46C.GL_UNSIGNED_BYTE,
                    buffer(4).put(0, (byte) 180).put(1, (byte) 210).put(2, (byte) 255).put(3, (byte) 128));
            GL46C.glActiveTexture(GL46C.GL_TEXTURE1);
            int shadow = texture(GL46C.GL_DEPTH_COMPONENT24, 1);
            GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, 1, 1,
                    GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, new float[]{1});
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_MODE,
                    GL46C.GL_COMPARE_REF_TO_TEXTURE);
            for (String name : new String[]{"shadowtex0", "shadowtex1"}) {
                GL46C.glUniform1i(GL46C.glGetUniformLocation(water, name), 1);
            }
            GL46C.glActiveTexture(GL46C.GL_TEXTURE2);
            int cloud = texture(GL46C.GL_RGBA32F, SIZE);
            float[] cloudPixels = new float[SIZE * SIZE * 4];
            for (int i = 0; i < cloudPixels.length; i += 4) {
                // r5.1 stores cloud depth in gaux1.r; r5.9 uses gaux2.a.
                if (waterMaterial != 32000) cloudPixels[i] = 1;
                cloudPixels[i + 3] = 1;
            }
            GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE,
                    GL46C.GL_RGBA, GL46C.GL_FLOAT, cloudPixels);
            GL46C.glUniform1i(GL46C.glGetUniformLocation(water, "gaux2"), 2);
            GL46C.glUniform1i(GL46C.glGetUniformLocation(water, "gaux1"), 2);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE3);
            int sceneDepth = texture(GL46C.GL_DEPTH_COMPONENT24, SIZE);
            float[] depthPixels = new float[SIZE * SIZE];
            java.util.Arrays.fill(depthPixels, 1);
            GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE,
                    GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, depthPixels);
            for (String name : new String[]{"depthtex0", "depthtex1"}) {
                GL46C.glUniform1i(GL46C.glGetUniformLocation(water, name), 3);
            }
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
            return new int[]{waterSample, shadow, cloud, sceneDepth};
        }

        private static void waterOrder(boolean modelBehind) throws Exception {
            WorldTransparencyPipeline.release();
            fixture("primaryColorOutput", true);
            fixture("stablePrimaryColorOutput", true);
            bind(stage);
            WorldTransparencyPipeline.beginFrame(false, true, true);
            WorldTransparencyPipeline.afterWorldTranslucency();
            WorldTransparencyPipeline.endFrame();
            bind(source);
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glColorMask(true, true, true, true);
            GL46C.glDepthMask(true);
            GL46C.glClearDepth(1);
            GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
            clearWaterTargets();
            WorldTransparencyPipeline.beginFrame(false, true, true);
            drawModel(.5f, modelBehind ? .4f : -.6f);
            bind(stage);
            WorldTransparencyPipeline.afterEntities();
            WorldTransparencyPipeline.afterOpaqueWorld();
            bind(source);
            WorldTransparencyPipeline.beforeWorldTranslucency();
            drawWaterBuffers();
            GL46C.glUseProgram(water);
            // Iris reapplies water samplers after entity materials used the atlas unit.
            for (int i = 0; i < textures.length; i++) {
                GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + i);
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, textures[i]);
                check(GL46C.glGetIntegeri(GL46C.GL_TEXTURE_BINDING_2D, i) == textures[i],
                        "native water texture rebound on unit " + i);
            }
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
            GL46C.glUniform1f(GL46C.glGetUniformLocation(water, "Depth"), -.2f);
            GL46C.glEnable(GL46C.GL_DEPTH_TEST);
            GL46C.glDepthFunc(GL46C.GL_LEQUAL);
            GL46C.glDepthMask(true);
            GL46C.glEnable(GL46C.GL_BLEND);
            GL46C.glBlendEquation(GL46C.GL_FUNC_ADD);
            GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                    GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
            try (var capture = WorldTransparencyPipeline.beginWaterCapture()) {
                check(capture != null, "verified water joins the production queue");
                check(GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK),
                        "native water depth writes remain active");
                GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
            }
            check(GL46C.glGetUniformi(water, GL46C.glGetUniformLocation(water, "MmdWaterCapture")) == 0,
                    "water capture disabled at scope exit");
            expect(source, GL46C.GL_COLOR_ATTACHMENT0, 40, CHEST,
                    "water RGB is not blended twice");
            for (int slot = 1; slot < buffers.length; slot++)
                expect(source, GL46C.GL_COLOR_ATTACHMENT0 + slot, 40, waterAuxiliary[slot - 1],
                        "native water colortex" + buffers[slot] + " MRT remains active");
            check(Math.abs(readDepth(source, 40) - .4f) < 1e-6,
                    "native water depth stored for surviving fragments");
            WorldTransparencyPipeline.afterWaterCapture();
            bind(stage);
            WorldTransparencyPipeline.afterWorldTranslucency();
            float[] expected = new float[]{0, 0, 0, 1};
            float alpha = waterPixel[3];
            for (int channel = 0; channel < 3; channel++) {
                expected[channel] = modelBehind
                        ? waterPixel[channel] * alpha + (.5f + .5f * CHEST[channel]) * (1 - alpha)
                        : .5f + .5f * (waterPixel[channel] * alpha + CHEST[channel] * (1 - alpha));
            }
            System.out.println("Water order modelBehind=" + modelBehind
                    + " actual=" + java.util.Arrays.toString(pixel(source, GL46C.GL_COLOR_ATTACHMENT0, 40))
                    + " expected=" + java.util.Arrays.toString(expected));
            float[] outside = new float[]{0, 0, 0, 1};
            for (int channel = 0; channel < 3; channel++) {
                outside[channel] = waterOutside[channel] * waterOutside[3]
                        + CHEST[channel] * (1 - waterOutside[3]);
            }
            expect(source, GL46C.GL_COLOR_ATTACHMENT0, 60, outside,
                    "water remains correct outside PMX coverage");
            expect(source, GL46C.GL_COLOR_ATTACHMENT0, 40, expected,
                    "water attenuates PMX only when the PMX is behind it");
            for (int slot = 1; slot < buffers.length; slot++)
                expect(source, GL46C.GL_COLOR_ATTACHMENT0 + slot, 40, waterAuxiliary[slot - 1],
                        "water resolve preserves colortex" + buffers[slot]);
            WorldTransparencyPipeline.endFrame();
        }

        private static void drawModel(float alpha, float depth) {
            GL46C.glEnable(GL46C.GL_DEPTH_TEST);
            GL46C.glDepthFunc(GL46C.GL_LEQUAL);
            GL46C.glUseProgram(model);
            GL46C.glUniform1f(GL46C.glGetUniformLocation(model, "Depth"), depth);
            ModelMaterial material = new ModelMaterial();
            material.diffuse = new float[]{0, 0, 0, 1};
            material.ambient = new float[]{1, 1, 1};
            material.specular = new float[3];
            material.hasAlpha = material.hasTranslucentPixels = true;
            ByteBuffer mesh = buffer(20).putInt(0, 0).putInt(4, 0).putInt(8, 3)
                    .putFloat(12, alpha).put(16, (byte) 1).put(17, (byte) 1);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, white);
            try (var ignored = WorldTransparencyPipeline.modelScene(RenderScene.WORLD)) {
                SubMeshDrawHelper.draw(mesh, 1, 4, GL46C.GL_UNSIGNED_INT,
                        id -> white, (id, value) -> value,
                        new ModelMaterial[]{material}, null, false, id -> {});
                check(WorldTransparencyPipeline.hasWorldQueue(), "model must join the shared queue");
            }
            check(!WorldTransparencyPipeline.classicDepthPrepass(), "no early translucent depth seal");
        }

        private static void fixture(String name, boolean value) throws Exception {
            var field = IrisToonCompat.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(null, value);
            if (name.equals("stablePrimaryColorOutput")) {
                var target = IrisToonCompat.class.getDeclaredField("preparedWorldColorTarget");
                target.setAccessible(true);
                target.set(null, value ? (java.util.function.BooleanSupplier) () -> {
                    bind(source);
                    return true;
                } : null);
            }
        }

        private static void clearWaterTargets() {
            GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, CHEST);
            for (int slot = 1; slot < buffers.length; slot++)
                GL46C.glClearBufferfv(GL46C.GL_COLOR, slot, new float[]{.2f, .3f, .4f, 1});
        }

        private static void drawWaterBuffers() {
            GL46C.glDrawBuffers(java.util.stream.IntStream.range(0, buffers.length)
                    .map(slot -> GL46C.GL_COLOR_ATTACHMENT0 + slot).toArray());
        }

        private static int target(int[] ignoredBuffers) {
            int framebuffer = GL46C.glGenFramebuffers();
            bind(framebuffer);
            for (int i = 0; i < ignoredBuffers.length; i++) {
                GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER,
                        GL46C.GL_COLOR_ATTACHMENT0 + i, GL46C.GL_TEXTURE_2D,
                        texture(GL46C.GL_RGBA16F, SIZE), 0);
            }
            int depth = texture(GL46C.GL_DEPTH_COMPONENT24, SIZE);
            GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                    GL46C.GL_TEXTURE_2D, depth, 0);
            GL46C.glDrawBuffers(java.util.stream.IntStream.range(0, ignoredBuffers.length)
                    .map(slot -> GL46C.GL_COLOR_ATTACHMENT0 + slot).toArray());
            check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE,
                    "complete water FBO");
            return framebuffer;
        }

        private static int attachment(int framebuffer, int attachment) {
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, framebuffer);
            return GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_READ_FRAMEBUFFER,
                    attachment, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        }

        private static float[] pixel(int framebuffer, int attachment, int x) {
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, framebuffer);
            GL46C.glReadBuffer(attachment);
            float[] result = new float[4];
            GL46C.glReadPixels(x, 32, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, result);
            return result;
        }

        private static float readDepth(int framebuffer, int x) {
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, framebuffer);
            float[] result = new float[1];
            GL46C.glReadPixels(x, 32, 1, 1, GL46C.GL_DEPTH_COMPONENT,
                    GL46C.GL_FLOAT, result);
            return result[0];
        }

        private static void expect(int framebuffer, int attachment, int x, float[] expected,
                                   String message) {
            float[] actual = pixel(framebuffer, attachment, x);
            for (int i = 0; i < 4; i++) {
                check(Math.abs(actual[i] - expected[i]) < .001f,
                        message + " channel=" + i + " actual=" + actual[i] + " expected=" + expected[i]);
            }
        }

        private static int texture(int format, int size) {
            int id = GL46C.glGenTextures();
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
            GL46C.glTexStorage2D(GL46C.GL_TEXTURE_2D, 1, format, size, size);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
            return id;
        }

        private static int program(String vertex, String fragment) {
            int vertexShader = shader(GL46C.GL_VERTEX_SHADER, vertex);
            int fragmentShader = shader(GL46C.GL_FRAGMENT_SHADER, fragment);
            int program = GL46C.glCreateProgram();
            try {
                GL46C.glAttachShader(program, vertexShader);
                GL46C.glAttachShader(program, fragmentShader);
                GL46C.glLinkProgram(program);
                check(GL46C.glGetProgrami(program, GL46C.GL_LINK_STATUS) != 0,
                        GL46C.glGetProgramInfoLog(program));
                return program;
            } catch (RuntimeException | Error failure) {
                GL46C.glDeleteProgram(program);
                throw failure;
            } finally {
                GL46C.glDeleteShader(vertexShader);
                GL46C.glDeleteShader(fragmentShader);
            }
        }

        private static int shader(int type, String source) {
            int shader = GL46C.glCreateShader(type);
            GL46C.glShaderSource(shader, source);
            GL46C.glCompileShader(shader);
            check(GL46C.glGetShaderi(shader, GL46C.GL_COMPILE_STATUS) != 0,
                    GL46C.glGetShaderInfoLog(shader));
            return shader;
        }

        private static void uniform(String name, float value) {
            GL46C.glUniform1f(GL46C.glGetUniformLocation(water, name), value);
        }

        private static void bind(int framebuffer) {
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, framebuffer);
            GL46C.glViewport(0, 0, SIZE, SIZE);
        }

        private static ByteBuffer buffer(int size) {
            return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        }

        private static void check(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
