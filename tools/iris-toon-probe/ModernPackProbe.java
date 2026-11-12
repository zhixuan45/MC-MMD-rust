import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import com.shiroha.mmdskin.render.shader.TranslucentBatchShader;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

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

/** 编译真实 Complementary 5.x 程序的 Toon 主着色器与描边变体。 */
public final class ModernPackProbe {
    private static final List<String> DIMENSIONS = List.of("world0", "world-1", "world1");
    private static final List<String> PROGRAMS = List.of(
            "gbuffers_entities", "gbuffers_entities_translucent", "gbuffers_entities_glowing", "gbuffers_hand");
    private static final Pattern BUFFERS = Pattern.compile(
            "DRAWBUFFERS\\s*:\\s*([0-9]+)|RENDERTARGETS\\s*:\\s*([0-9]+(?:\\s*,\\s*[0-9]+)*)");
    private static final Pattern INCLUDE = Pattern.compile(
            "(?m)^\\s*#include\\s+\"([^\"]+)\"[^\\r\\n]*");
    private static final Pattern DEFINE = Pattern.compile(
            "(?m)^(\\s*)(//\\s*)?#define\\s+(\\w+)([^\\r\\n]*)");
    private static final List<String> PACKS = List.of(
            "ComplementaryReimagined_r5.9.3.zip", "ComplementaryUnbound_r5.9.3.zip");

    private ModernPackProbe() {}

    public static void run(Path shaderpacks) throws Exception {
        run(shaderpacks, "12001", "10800");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: ModernPackProbe <shaderpacks> [mcVersion] [irisVersion]");
        }
        String minecraftVersion = args.length > 1 ? args[1] : "12001";
        String irisVersion = args.length > 2 ? args[2] : "10800";
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE);
        long window = GLFW.glfwCreateWindow(64, 64, "Complementary 5 Toon probe", 0, 0);
        if (window == 0) throw new AssertionError("OpenGL 4.3 context");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            System.out.println("GL=" + GL46C.glGetString(GL46C.GL_VERSION));
            run(Path.of(args[0]), minecraftVersion, irisVersion);
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    public static void run(Path shaderpacks, String minecraftVersion, String irisVersion) throws Exception {
        for (String packName : PACKS) runPack(shaderpacks, packName, minecraftVersion, irisVersion);
    }

    private static void runPack(Path shaderpacks, String packName, String minecraftVersion,
                                String irisVersion) throws Exception {
        Path pack = shaderpacks.resolve(packName);
        if (!Files.isRegularFile(pack)) throw new AssertionError("缺少真实包: " + pack);
        Map<String, String> options = options(Path.of(pack + ".txt"));
        List<StringPair> defines = List.of(new StringPair("MC_VERSION", minecraftVersion),
                new StringPair("IS_IRIS", "1"), new StringPair("IRIS_VERSION", irisVersion));
        int checked = 0;

        try (ZipFile zip = new ZipFile(pack.toFile())) {
            for (String dimension : DIMENSIONS) {
                for (String program : PROGRAMS) {
                    String fragmentPath = wrapper(zip, dimension, program, "fsh");
                    String vertexPath = wrapper(zip, dimension, program, "vsh");
                    String fragment = JcppProcessor.glslPreprocessSource(include(zip, fragmentPath, options), defines);
                    String vertex = JcppProcessor.glslPreprocessSource(include(zip, vertexPath, options), defines);
                    int[] buffers = drawBuffers(fragment, program);
                    ToonOutputProfile profile = ToonOutputProfile.resolve(pack.getFileName().toString(),
                            program, fragment, vertex, buffers);
                    if (!profile.isSupported()) {
                        throw new AssertionError(dimension + "/" + program + " 契约不支持: " + profile.reason());
                    }

                    ToonShaderCpu shader = new ToonShaderCpu();
                    try {
                        if (!shader.selectOutputProfile(profile) || !shader.isInitialized()
                                || shader.getMainProgram() <= 0 || shader.getOutlineProgram() <= 0) {
                            throw new AssertionError(dimension + "/" + program + " 主/描边编译失败: " + profile.id());
                        }
                        checked++;
                        checkSelectedProfile(shader, profile);
                        checkBatchRegistration(shader, dimension, program, profile);
                        System.out.println("PASS modern " + dimension + "/" + program
                                + " main+outline+batch -> " + profile.id());
                    } finally {
                        shader.cleanup();
                    }
                }
            }
        }
        if (checked != DIMENSIONS.size() * PROGRAMS.size()) throw new AssertionError("现代包程序覆盖不完整: " + checked);
        System.out.println("PASS modern package " + pack.getFileName() + ": " + checked
                + " real program main+outline+batch compilations");
    }

    private static void checkBatchRegistration(ToonShaderCpu shader, String dimension, String program,
                                               ToonOutputProfile profile) {
        if (!profile.id().startsWith("complementary_modern:")) return;
        if (!TranslucentBatchShader.supported()) {
            throw new AssertionError(dimension + "/" + program + " 合批能力不可用");
        }
        shader.useMain();
        if (TranslucentBatchShader.current() == null) {
            throw new AssertionError(dimension + "/" + program
                    + " modern Complementary 合批未登记到实际主程序: " + profile.id());
        }
    }

    private static void checkSelectedProfile(ToonShaderCpu shader, ToonOutputProfile profile) {
        try {
            var selected = com.shiroha.mmdskin.render.shader.ToonShaderBase.class
                    .getDeclaredField("activeProfile");
            selected.setAccessible(true);
            if (!profile.id().equals(selected.get(shader))) {
                throw new AssertionError("实际使用了光照回退: " + profile.id());
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("无法校验实际 Toon profile", exception);
        }
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

    private static String wrapper(ZipFile zip, String dimension, String program, String extension) {
        String dimensional = "shaders/" + dimension + "/" + program + "." + extension;
        if (zip.getEntry(dimensional) != null) return dimensional;
        String shared = "shaders/" + program + "." + extension;
        if (zip.getEntry(shared) != null) return shared;
        throw new AssertionError("包中缺少程序源码: " + dimensional);
    }

    private static int[] drawBuffers(String source, String program) {
        Matcher matcher = BUFFERS.matcher(source);
        String directive = null;
        boolean renderTargets = false;
        while (matcher.find()) {
            renderTargets = matcher.group(1) == null;
            directive = renderTargets ? matcher.group(2) : matcher.group(1);
        }
        if (directive == null) throw new AssertionError(program + " 缺少 DRAWBUFFERS/RENDERTARGETS");
        return renderTargets
                ? java.util.Arrays.stream(directive.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray()
                : directive.chars().map(value -> value - '0').toArray();
    }
}
