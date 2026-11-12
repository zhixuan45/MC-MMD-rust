import com.shiroha.mmdskin.render.shader.LightingProbe;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

import java.nio.file.Path;
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
        String minecraftVersion = args.length > 2 ? args[2] : "12001";
        String irisVersion = args.length > 3 ? args[3] : "10800";
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
            for (String pack : List.of("BSL_v10.1.8.zip", "BSL_v8.4.02.2.zip", "ComplementaryShaders_v4.7.1.zip",
                    "ComplementaryReimagined_r5.9.3.zip", "ComplementaryUnbound_r5.9.3.zip")) {
                if (normalBias && !pack.startsWith("BSL")) continue;
                Path path = Path.of(args[0], pack);
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
                    if (pack.endsWith("_r5.9.3.zip")) {
                        checkModernShadow(Path.of(args[0]), pack, zip, defines);
                        shadowChecked += 2;
                    }
                    for (String dimension : List.of("world0", "world-1", "world1")) {
                        for (String program : List.of("gbuffers_entities", "gbuffers_hand")) {
                            String wrapper = "shaders/" + dimension + "/" + program;
                            if (zip.getEntry(wrapper + ".fsh") == null) wrapper = "shaders/" + program;
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
                                    java.nio.file.Files.writeString(Path.of("build/sunlight-probe",
                                            pack + (normalBias ? ".bias1" : "") + ".lighting.glsl"), profile.lightingSource());
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
                    + ", " + shadowChecked + " modern low/medium shadow contracts"
                    + (normalBias ? " (BSL SHADOW_BIAS=1)" : ""));
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void checkModernShadow(Path shaderpacks, String pack, ZipFile zip,
                                          List<StringPair> defines) throws Exception {
        Path packPath = shaderpacks.resolve(pack);
        var baseOptions = new java.util.HashMap<>(Probe.options(Path.of(packPath + ".txt")));
        for (int quality : new int[]{0, 2}) {
            var options = new java.util.HashMap<>(baseOptions);
            options.put("SHADOW_QUALITY", Integer.toString(quality));
            String fragment = JcppProcessor.glslPreprocessSource(
                    Probe.include(zip, "shaders/world0/shadow.fsh", options), defines);
            String vertex = JcppProcessor.glslPreprocessSource(
                    Probe.include(zip, "shaders/world0/shadow.vsh", options), defines);
            Path output = Path.of("build/sunlight-probe");
            java.nio.file.Files.createDirectories(output);
            java.nio.file.Files.writeString(output.resolve(pack + ".shadow-q" + quality + ".frag.glsl"), fragment);
            java.nio.file.Files.writeString(output.resolve(pack + ".shadow-q" + quality + ".vert.glsl"), vertex);
            int targets = shadowTargetCount(fragment, vertex);
            int expected = quality == 0 ? 1 : 2;
            if (targets != expected) {
                throw new AssertionError(pack + " SHADOW_QUALITY=" + quality
                        + " replay targets=" + targets + ", expected=" + expected);
            }
            System.out.println("PASS modern shadow " + pack + " SHADOW_QUALITY=" + quality
                    + " replayTargets=" + targets);
        }
    }

    private static int shadowTargetCount(String fragment, String vertex) throws Exception {
        Class<?> replay = Class.forName("com.shiroha.mmdskin.compat.iris.IrisTerrainShadowReplay");
        var method = replay.getDeclaredMethod("colorTargetCount", String.class, String.class);
        method.setAccessible(true);
        return (int) method.invoke(null, fragment, vertex);
    }
}
