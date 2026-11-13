import com.shiroha.mmdskin.render.shader.LightingProbe;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Real pack GLSL and production body/outline shaders; does not launch Minecraft. */
public final class SunlightGlProbe {
    public static void main(String[] args) throws Exception {
        boolean normalBias = args.length > 1 && args[1].equals("normal-bias");
        boolean core = args.length > 1 && args[1].equals("core");
        boolean lightingOnly = args.length > 1 && args[1].equals("lighting-only");
        String minecraftVersion = args.length > 2 ? args[2] : "12101";
        String irisVersion = args.length > 3 ? args[3] : "10814";
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, core ? 4 : 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, core ? GLFW.GLFW_OPENGL_CORE_PROFILE : GLFW.GLFW_OPENGL_COMPAT_PROFILE);
        long window = GLFW.glfwCreateWindow(16, 16, "Sunlight validation", 0, 0);
        if (window == 0) throw new IllegalStateException("Hidden GL context");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("GL: " + GL46C.glGetString(GL46C.GL_VERSION) + " / " + GL46C.glGetString(GL46C.GL_RENDERER));
            if (!lightingOnly) {
                TerrainShadowGlProbe.run();
                com.shiroha.mmdskin.compat.iris.TransmissionGlProbe.run();
            }
            int checked = 0;
            int shadowChecked = 0;
            for (Path path : packPaths(Path.of(args[0]))) {
                String pack = path.getFileName().toString();
                if (normalBias && !pack.startsWith("BSL")) continue;
                var options = new java.util.HashMap<>(Probe.options(Path.of(path + ".txt")));
                if (normalBias) options.put("SHADOW_BIAS", "1");
                var defines = List.of(new StringPair("MC_VERSION", minecraftVersion), new StringPair("IS_IRIS", "1"),
                        new StringPair("IRIS_VERSION", irisVersion));
                try (var zip = new ZipFile(path.toFile())) {
                    if (pack.startsWith("BSL")) {
                        String shadow = zip.getEntry("shaders/world0/shadow.fsh") == null ? "shaders/shadow" : "shaders/world0/shadow";
                        String fragment = JcppProcessor.glslPreprocessSource(Probe.include(zip, shadow + ".fsh", options), defines);
                        String vertex = JcppProcessor.glslPreprocessSource(Probe.include(zip, shadow + ".vsh", options), defines);
                        boolean colored = fragment.replaceAll("\\s+", "").contains("albedo.rgb*=1.0-pow(albedo.a,96.0)");
                        if (pack.equals("BSL_v10.1.8.zip") && !colored) throw new AssertionError("Missing BSL glass color protocol");
                        com.shiroha.mmdskin.compat.iris.TransmissionGlProbe.checkProtocol(fragment, vertex, colored);
                        System.out.println("PASS shadow replay contract " + pack + ": colored=" + colored);
                    }
                    if (!pack.startsWith("BSL")) {
                        shadowChecked += checkModernShadow(zip, pack, options, defines);
                    }
                    for (String dimension : List.of("world0", "world-1", "world1")) {
                        for (String program : programs(pack)) {
                            String wrapper = wrapper(zip, dimension, program);
                            String fragment = JcppProcessor.glslPreprocessSource(Probe.include(zip, wrapper + ".fsh", options), defines);
                            String vertex = JcppProcessor.glslPreprocessSource(Probe.include(zip, wrapper + ".vsh", options), defines);
                            var matcher = Pattern.compile("DRAWBUFFERS\\s*:\\s*([0-9]+)|RENDERTARGETS\\s*:\\s*([0-9, ]+)").matcher(fragment);
                            int[] buffers = null;
                            while (matcher.find()) buffers = matcher.group(1) != null
                                    ? matcher.group(1).chars().map(c -> c - '0').toArray()
                                    : Arrays.stream(matcher.group(2).split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
                            if (buffers == null) throw new AssertionError("Missing output layout");
                            var profile = ToonOutputProfile.resolve(pack, program, fragment, vertex, buffers);
                            if (!profile.isSupported() || profile.lightingSource().isEmpty())
                                throw new AssertionError(pack + "/" + dimension + "/" + program + ": " + profile.reason());
                            var shader = new ToonShaderCpu();
                            try {
                                if (!shader.selectOutputProfile(profile)) throw new AssertionError("Shader compilation: " + profile.id());
                                Probe.checkSelectedProfile(shader, profile);
                                if (dimension.equals("world0") && program.equals("gbuffers_entities")) {
                                    Path output = Path.of("build/sunlight-probe");
                                    java.nio.file.Files.createDirectories(output);
                                    java.nio.file.Files.writeString(output.resolve(
                                            pack + (normalBias ? ".bias1" : "") + ".lighting.glsl"),
                                            profile.lightingSource());
                                    if (!profile.lightingSource().contains("toonPrepareSunlight("))
                                        throw new AssertionError("Missing verified sunlight path: " + pack);
                                    System.out.println("PIXELS " + pack);
                                    LightingProbe.run(shader, profile);
                                }
                                checked++;
                                System.out.println("PASS compile " + pack + "/" + dimension + "/" + program);
                            } finally { shader.cleanup(); }
                        }
                    }
                }
            }
            if (GL46C.glGetError() != GL46C.GL_NO_ERROR) throw new AssertionError("Final GL error");
            System.out.println("PASS sunlight validation: " + checked + " real pack main/outline variants"
                    + ", " + shadowChecked + " modern shadow contracts"
                    + (normalBias ? " (BSL SHADOW_BIAS=1)" : ""));
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static int checkModernShadow(ZipFile zip, String pack, java.util.Map<String, String> options,
                                         List<StringPair> defines) throws Exception {
        Path output = Path.of("build/sunlight-probe");
        java.nio.file.Files.createDirectories(output);
        int checked = 0;
        for (String dimension : List.of("world0", "world-1", "world1")) {
            String wrapper = "shaders/" + dimension + "/shadow";
            if (zip.getEntry(wrapper + ".fsh") == null) {
                wrapper = "shaders/world0/shadow";
                if (zip.getEntry(wrapper + ".fsh") == null) wrapper = "shaders/shadow";
            }
            String fragment = JcppProcessor.glslPreprocessSource(
                    Probe.include(zip, wrapper + ".fsh", options), defines);
            String vertex = JcppProcessor.glslPreprocessSource(
                    Probe.include(zip, wrapper + ".vsh", options), defines);
            if (!hasModernShadowContract(fragment)) {
                System.out.println("SKIP non-modern shadow contract " + pack + "/" + dimension);
                continue;
            }
            int targets = lastOutputTargetCount(fragment);
            // Iris 1.21.1 creates the shadow framebuffer as {0,1} when
            // ProgramDirectives.hasUnknownDrawBuffers() is true.
            if (targets < 0) targets = 2;
            if (targets <= 0) throw new AssertionError(pack + "/" + dimension + " invalid shadow targets");
            com.shiroha.mmdskin.compat.iris.TransmissionGlProbe.checkProtocol(fragment, vertex, targets);
            java.nio.file.Files.writeString(output.resolve(pack + "." + dimension + ".shadow.frag.glsl"), fragment);
            java.nio.file.Files.writeString(output.resolve(pack + "." + dimension + ".shadow.vert.glsl"), vertex);
            System.out.println("PASS modern shadow contract " + pack + "/" + dimension
                    + ": targets=" + targets);
            checked++;
        }
        return checked;
    }

    private static List<Path> packPaths(Path shaderpacks) {
        List<String> names = new ArrayList<>(List.of(
                "BSL_v10.1.8.zip", "BSL_v8.4.02.2.zip", "ComplementaryShaders_v4.7.1.zip",
                "ComplementaryReimagined_r5.9.3.zip", "ComplementaryUnbound_r5.9.3.zip"));
        String extra = System.getProperty("mmdskin.irisProbe.extraPack", "").trim();
        List<Path> paths = new ArrayList<>();
        for (String name : names) paths.add(shaderpacks.resolve(name).toAbsolutePath().normalize());
        if (!extra.isEmpty()) {
            Path path = Path.of(extra).toAbsolutePath().normalize();
            if (!paths.contains(path)) {
                if (!java.nio.file.Files.isRegularFile(path)) {
                    throw new AssertionError("mmdskin.irisProbe.extraPack 不存在: " + path);
                }
                paths.add(path);
            }
        }
        return paths;
    }

    private static String wrapper(ZipFile zip, String dimension, String program) {
        String dimensional = "shaders/" + dimension + "/" + program;
        if (zip.getEntry(dimensional + ".fsh") != null) return dimensional;
        String shared = "shaders/" + program;
        if (zip.getEntry(shared + ".fsh") != null) return shared;
        if (program.endsWith("_translucent")) {
            String fallback = program.substring(0, program.length() - "_translucent".length());
            String dimensionalFallback = "shaders/" + dimension + "/" + fallback;
            if (zip.getEntry(dimensionalFallback + ".fsh") != null) return dimensionalFallback;
            String sharedFallback = "shaders/" + fallback;
            if (zip.getEntry(sharedFallback + ".fsh") != null) return sharedFallback;
        }
        if (program.equals("gbuffers_entities_glowing")) {
            String dimensionalFallback = "shaders/" + dimension + "/gbuffers_entities";
            if (zip.getEntry(dimensionalFallback + ".fsh") != null) return dimensionalFallback;
            if (zip.getEntry("shaders/gbuffers_entities.fsh") != null) return "shaders/gbuffers_entities";
        }
        throw new AssertionError("包中缺少程序源码: " + dimensional);
    }

    private static List<String> programs(String pack) {
        if (pack.startsWith("BSL") || pack.equals("ComplementaryShaders_v4.7.1.zip"))
            return List.of("gbuffers_entities", "gbuffers_hand");
        return List.of("gbuffers_entities", "gbuffers_entities_translucent",
                "gbuffers_entities_glowing", "gbuffers_hand");
    }

    private static boolean hasModernShadowContract(String source) {
        String compact = source.replaceAll("\\s+", "");
        return compact.contains("voidDoNaturalShadowCalculation(inoutvec4color1,inoutvec4color2)")
                && (compact.contains("color1.rgb*=0.1;") || compact.contains("color1.rgb*=0.2;"))
                && (compact.contains("gl_FragData[0]=color1;")
                || compact.contains("gl_FragData[0]=vec4(saturateColors(color1.rgb,"));
    }

    private static int lastOutputTargetCount(String source) {
        var matcher = Pattern.compile(
                "DRAWBUFFERS\\s*:\\s*([0-9]+)|RENDERTARGETS\\s*:\\s*([0-9]+(?:\\s*,\\s*[0-9]+)*)")
                .matcher(source);
        int count = -1;
        while (matcher.find()) {
            count = matcher.group(1) != null
                    ? matcher.group(1).length()
                    : matcher.group(2).split(",").length;
        }
        return count;
    }
}
