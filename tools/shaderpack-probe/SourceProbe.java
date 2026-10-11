import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;

/** 可选真实光影探针：使用 Iris 预处理器验证三个维度及设置变体，不分发光影源码。 */
public final class SourceProbe {
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#include\\s+\"([^\"]+)\"[^\\r\\n]*");
    private static final Pattern DEFINE = Pattern.compile("(?m)^(\\s*)(//\\s*)?#define\\s+(\\w+)([^\\r\\n]*)");
    private static final Pattern BUFFERS = Pattern.compile("DRAWBUFFERS\\s*:\\s*([0-9]+)|RENDERTARGETS\\s*:\\s*([0-9]+(?:\\s*,\\s*[0-9]+)*)");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("SourceProbe <output-directory> <shader.zip>...");
        Path output = Path.of(args[0]); Files.createDirectories(output);
        int checked = 0;
        for (int packIndex = 1; packIndex < args.length; packIndex++) {
            Path path = Path.of(args[packIndex]); String pack = path.getFileName().toString();
            List<Map<String, String>> variants = new ArrayList<>(); variants.add(Map.of());
            if (pack.toLowerCase(Locale.ROOT).contains("bsl")) {
                for (String blend : List.of("0", "1")) for (String advanced : List.of("true", "false"))
                    for (String colored : List.of("true", "false")) for (String selective : List.of("true", "false")) {
                        variants.add(Map.of("ALPHA_BLEND", blend, "ADVANCED_MATERIALS", advanced,
                                "REFLECTION_SPECULAR", "true", "MCBL_SS", colored, "TAA_SELECTIVE", selective));
                    }
            } else {
                variants.add(Map.of("RP_MODE", "0", "BLOCK_REFLECT_QUALITY", "0", "DETAIL_QUALITY", "0"));
                variants.add(Map.of("RP_MODE", "2", "BLOCK_REFLECT_QUALITY", "3", "DETAIL_QUALITY", "0"));
                variants.add(Map.of("RP_MODE", "1", "BLOCK_REFLECT_QUALITY", "3", "DETAIL_QUALITY", "2"));
            }
            try (ZipFile zip = new ZipFile(path.toFile())) {
                for (int variant = 0; variant < variants.size(); variant++) {
                    for (String dimension : List.of("world0", "world-1", "world1")) {
                        for (String program : List.of("gbuffers_entities", "gbuffers_hand",
                                "gbuffers_entities_translucent", "gbuffers_entities_glowing")) {
                            String wrapper = "shaders/" + dimension + "/" + program + ".fsh";
                            if (zip.getEntry(wrapper) == null) wrapper = "shaders/" + program + ".fsh";
                            if (zip.getEntry(wrapper) == null) continue;
                            List<StringPair> defines = List.of(new StringPair("MC_VERSION", "12101"),
                                    new StringPair("IS_IRIS", "1"), new StringPair("IRIS_FEATURE_ENTITY_TRANSLUCENT", "1"));
                            String fragment = JcppProcessor.glslPreprocessSource(include(zip, wrapper, variants.get(variant)), defines);
                            String vertex = JcppProcessor.glslPreprocessSource(include(zip, wrapper.replace(".fsh", ".vsh"), variants.get(variant)), defines);
                            Matcher matcher = BUFFERS.matcher(fragment); int[] buffers = null;
                            while (matcher.find()) buffers = matcher.group(1) != null
                                    ? matcher.group(1).chars().map(c -> c - '0').toArray()
                                    : Arrays.stream(matcher.group(2).split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
                            ToonOutputProfile profile = ToonOutputProfile.resolve(pack, program, fragment, vertex, buffers);
                            // 用无家族名称、空名称及误导性名称重放同一程序，结果必须完全相同。
                            for (String alias : Arrays.asList("renamed-pack.zip", "BSL_Complementary_misleading.zip", "", null)) {
                                ToonOutputProfile renamed = ToonOutputProfile.resolve(alias, program, fragment, vertex, buffers);
                                if (renamed.isSupported() != profile.isSupported() || !renamed.id().equals(profile.id())
                                        || !renamed.fragmentWriter().equals(profile.fragmentWriter())
                                        || !renamed.lightingSource().equals(profile.lightingSource())) {
                                    throw new AssertionError("Pack name changed protocol resolution: " + pack + " -> " + alias);
                                }
                            }
                            String label = pack + "-" + variant + "-" + dimension + "-" + program;
                            if (!profile.isSupported()) throw new AssertionError(label + ": " + profile.reason());
                            if (pack.toLowerCase(Locale.ROOT).contains("complementary") && profile.lightingSource().isEmpty())
                                throw new AssertionError(label + ": missing lighting");
                            for (String pass : List.of("main", "outline")) {
                                String body = Files.readString(Path.of("common/src/main/resources/assets/mmdskin/shader/toon_" + pass + "_body.frag.glsl"));
                                String generated = body.replace("/* TOON_OUTPUT_DECLARATIONS */", profile.fragmentDeclarations())
                                        .replace("/* TOON_OUTPUT_WRITER */", profile.fragmentWriter())
                                        .replace("/* TOON_LIGHTING */", profile.lightingSource().isEmpty()
                                                ? "vec3 applyPackLighting(vec3 raw, vec3 n, vec3 p) { return raw; }" : profile.lightingSource());
                                if (pass.equals("outline")) {
                                    generated = body.replace("/* TOON_OUTPUT_DECLARATIONS */", "layout(location = 0) out vec4 outlineColor;\n")
                                            .replace("/* TOON_OUTPUT_WRITER */", profile.outlineWriter());
                                }
                                Files.writeString(output.resolve(label + "." + pass + ".glsl"), generated);
                            }
                            // 独立输出程序供 EGL 颜色/法线/透明度/深度回读，避免光照影响协议断言。
                            String writer = "#version 330 core\nvec3 viewPos = vec3(0.0, 0.0, -16.0);\n"
                                    + profile.fragmentDeclarations() + profile.fragmentWriter()
                                    + "uniform vec3 TestNormal;\nvoid main() { writeToonOutputs(vec3(0.5,0.25,0.75),vec3(0.6,0.4,0.2),TestNormal,0.4); }\n";
                            Files.writeString(output.resolve(label + ".writer.glsl"), writer);
                            String outlineWriter = "#version 330 core\nuniform bool Premultiplied;\nlayout(location = 0) out vec4 outlineColor;\n"
                                    + profile.outlineWriter()
                                    + "void main() { writeToonOutputs(vec3(0.5,0.25,0.75),vec3(0.6,0.4,0.2),vec3(0.0,0.0,1.0),0.4); }\n";
                            Files.writeString(output.resolve(label + ".outline-writer.glsl"), outlineWriter);
                            Files.writeString(output.resolve(label + ".meta"), profile.id() + "\n"
                                    + String.join(",", Arrays.stream(buffers).mapToObj(String::valueOf).toList()) + "\n");
                            Files.writeString(output.resolve(label + ".source.fsh"), fragment);
                            Files.writeString(output.resolve(label + ".source.vsh"), vertex);
                            checked++;
                        }
                    }
                    System.out.println(pack + " variant=" + variant + " " + variants.get(variant) + " OK");
                }
            }
        }
        System.out.println("PASS: " + checked + " actual programs/options; sources written to " + output);
    }

    static String include(ZipFile zip, String entryName, Map<String, String> options) throws Exception {
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

}
