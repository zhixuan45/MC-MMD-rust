import com.shiroha.mmdskin.compat.iris.IrisWaterShaderPatcher;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Validate real Iris/Oculus water transforms, GL links, auxiliary MRTs and ordering. */
public final class WaterShaderProbe {
    private static final Pattern BUFFERS = Pattern.compile(
            "DRAWBUFFERS\\s*:\\s*([0-9]+)|RENDERTARGETS\\s*:\\s*([0-9]+(?:\\s*,\\s*[0-9]+)*)");
    private static final Pattern INCLUDE = Pattern.compile(
            "(?m)^\\s*#include\\s+\"([^\"]+)\"[^\\r\\n]*");
    private static final Pattern DEFINE = Pattern.compile(
            "(?m)^(\\s*)(//\\s*)?#define\\s+(\\w+)([^\\r\\n]*)");

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: WaterShaderProbe <pack.zip> [mcVersion] [irisVersion]");
        }
        String minecraftVersion = args.length > 1 ? args[1] : "12001";
        String irisVersion = args.length > 2 ? args[2] : "10800";
        String adapterPolicy = args.length > 3 ? args[3] : "auto";
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = GLFW.glfwCreateWindow(64, 64, "Iris water", 0, 0);
        if (window == 0) throw new AssertionError("GL 4.3");
        try (ZipFile zip = new ZipFile(args[0])) {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            Method patchSodium = findPatchSodium();
            verifyWaterStateEntryPoints();
            System.out.println("PASS installed water source/state signatures: transformArgs="
                    + patchSodium.getParameterCount());
            List<StringPair> defines = List.of(new StringPair("MC_VERSION", minecraftVersion),
                    new StringPair("IS_IRIS", "1"), new StringPair("IRIS_VERSION", irisVersion));
            Map<String, String> options = options(Path.of(args[0] + ".txt"));
            Path output = Path.of("build/iris-toon-probe");
            Files.createDirectories(output);
            for (String dimension : List.of("world0", "world-1", "world1")) {
                String base = "shaders/" + dimension + "/gbuffers_water.";
                String vertex = JcppProcessor.glslPreprocessSource(
                        include(zip, base + "vsh", options), defines);
                String fragment = JcppProcessor.glslPreprocessSource(
                        include(zip, base + "fsh", options), defines);
                Map<PatchShaderType, String> nativeSources = transform(
                        patchSodium, "gbuffers_water", vertex, fragment);
                Files.writeString(output.resolve(packStem(args[0]) + "-" + dimension + ".frag.glsl"),
                        nativeSources.get(PatchShaderType.FRAGMENT));

                int[] buffers = drawBuffers(fragment);
                Map<PatchShaderType, String> adapted = nativeSources;
                boolean patched = false;
                try {
                    if (IrisWaterShaderPatcher.supports("gbuffers_water", fragment)) {
                        adapted = IrisWaterShaderPatcher.patch("gbuffers_water", fragment, nativeSources);
                        patched = adapted != nativeSources;
                    }
                } catch (RuntimeException unavailable) {
                    if (adapterPolicy.equals("required")) throw unavailable;
                    System.out.println("WAIT water adapter is being integrated; native transform continues: "
                            + unavailable);
                    adapted = nativeSources;
                    patched = false;
                }
                if (adapterPolicy.equals("required") && !patched) {
                    throw new AssertionError(Path.of(args[0]).getFileName()
                            + " real water adapter rejected the active contract");
                }
                int linkedProgram = link(adapted.get(PatchShaderType.VERTEX),
                        adapted.get(PatchShaderType.FRAGMENT));
                try {
                    if (patched && GL46C.glGetUniformLocation(linkedProgram, "MmdWaterCapture") < 0) {
                        throw new AssertionError(Path.of(args[0]).getFileName()
                                + " water capture was optimized away");
                    }
                } finally {
                    GL46C.glDeleteProgram(linkedProgram);
                }
                System.out.println("PASS native water transform/link " + dimension
                        + " buffers=" + java.util.Arrays.toString(buffers) + " adapter=" + patched);
                com.shiroha.mmdskin.render.material.BlockEntityTransparencyGlProbe.nativeWaterScenario(
                        adapted.get(PatchShaderType.FRAGMENT), buffers,
                        Path.of(args[0]).getFileName().toString());
            }
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static Method findPatchSodium() {
        for (Method method : TransformPatcher.class.getDeclaredMethods()) {
            if (!method.getName().equals("patchSodium")) continue;
            if (method.getParameterCount() == 8 || method.getParameterCount() == 9) return method;
        }
        throw new AssertionError("installed Iris/Oculus TransformPatcher.patchSodium signature not found");
    }

    private static void verifyWaterStateEntryPoints() throws Exception {
        try {
            Class<?> shader = Class.forName("net.irisshaders.iris.pipeline.programs.ExtendedShader");
            shader.getMethod("apply");
            shader.getMethod("clear");
            shader.getMethod("getName");
            System.out.println("PASS Oculus ExtendedShader apply/clear state entries");
        } catch (ClassNotFoundException missingExtendedShader) {
            Class<?> shader = Class.forName("net.irisshaders.iris.pipeline.programs.SodiumShader");
            shader.getDeclaredMethod("setupState");
            shader.getDeclaredMethod("resetState");
            System.out.println("PASS Iris SodiumShader setup/reset state entries");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<PatchShaderType, String> transform(Method method, String name, String vertex,
                                                          String fragment) throws Exception {
        Object[] arguments;
        if (method.getParameterCount() == 9) {
            Object attributes = Class.forName("net.irisshaders.iris.gl.state.ShaderAttributeInputs")
                    .getConstructor(boolean.class, boolean.class, boolean.class, boolean.class, boolean.class)
                    .newInstance(true, true, true, true, true);
            arguments = new Object[]{name, vertex, null, null, null, fragment,
                    new AlphaTest(AlphaTestFunction.GREATER, .001f), attributes,
                    new Object2ObjectOpenHashMap<>()};
        } else {
            arguments = new Object[]{name, vertex, null, null, null, fragment,
                    new AlphaTest(AlphaTestFunction.GREATER, .001f), new Object2ObjectOpenHashMap<>()};
        }
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
            throw new AssertionError(GL46C.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static String packStem(String path) {
        String name = Path.of(path).getFileName().toString();
        return name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }
}
