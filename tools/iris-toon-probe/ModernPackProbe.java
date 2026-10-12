import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    private ModernPackProbe() {}

    public static void run(Path shaderpacks) throws Exception {
        Path pack = shaderpacks.resolve("ComplementaryUnbound_r5.9.3.zip");
        if (!Files.isRegularFile(pack)) throw new AssertionError("缺少真实包: " + pack);
        Map<String, String> options = Probe.options(Path.of(pack + ".txt"));
        List<StringPair> defines = List.of(new StringPair("MC_VERSION", "12101"),
                new StringPair("IS_IRIS", "1"), new StringPair("IRIS_VERSION", "10814"));
        int checked = 0;

        try (ZipFile zip = new ZipFile(pack.toFile())) {
            for (String dimension : DIMENSIONS) {
                for (String program : PROGRAMS) {
                    String fragmentPath = wrapper(zip, dimension, program, "fsh");
                    String vertexPath = wrapper(zip, dimension, program, "vsh");
                    String fragment = JcppProcessor.glslPreprocessSource(Probe.include(zip, fragmentPath, options), defines);
                    String vertex = JcppProcessor.glslPreprocessSource(Probe.include(zip, vertexPath, options), defines);
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
                        System.out.println("PASS modern " + dimension + "/" + program + " main+outline -> " + profile.id());
                    } finally {
                        shader.cleanup();
                    }
                }
            }
        }
        if (checked != DIMENSIONS.size() * PROGRAMS.size()) throw new AssertionError("现代包程序覆盖不完整: " + checked);
        System.out.println("PASS modern package: " + checked + " real program main+outline compilations");
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
