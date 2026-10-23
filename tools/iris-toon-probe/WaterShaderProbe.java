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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

/** Validate the adapter against the installed Iris transform and actual BSL water GLSL. */
public final class WaterShaderProbe {
    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = GLFW.glfwCreateWindow(64, 64, "BSL native water", 0, 0);
        if (window == 0) throw new AssertionError("GL 4.3");
        try (ZipFile zip = new ZipFile(args[0])) {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            TransformPatcher.class.getDeclaredMethod("patchSodium", String.class, String.class, String.class,
                    String.class, String.class, String.class, AlphaTest.class,
                    it.unimi.dsi.fastutil.objects.Object2ObjectMap.class);
            var state = Class.forName("net.irisshaders.iris.pipeline.programs.SodiumShader", false,
                    WaterShaderProbe.class.getClassLoader());
            state.getDeclaredMethod("setupState"); state.getDeclaredMethod("resetState");
            System.out.println("PASS installed Iris water source/setup/reset entry-point signatures");
            var defines = List.of(new StringPair("MC_VERSION", "12101"), new StringPair("IS_IRIS", "1"),
                    new StringPair("IRIS_VERSION", "10814"));
            var options = Probe.options(Path.of(args[0] + ".txt"));
            for (String dimension : List.of("world0", "world-1", "world1")) {
                String base = "shaders/" + dimension + "/gbuffers_water.";
                String vertex = JcppProcessor.glslPreprocessSource(Probe.include(zip, base + "vsh", options), defines);
                String fragment = JcppProcessor.glslPreprocessSource(Probe.include(zip, base + "fsh", options), defines);
                var nativeSources = TransformPatcher.patchSodium("gbuffers_water", vertex, null, null, null, fragment,
                        new AlphaTest(AlphaTestFunction.GREATER, .001f), new Object2ObjectOpenHashMap<>());
                Files.writeString(Path.of("build/iris-toon-probe/water-" + dimension + ".frag.glsl"),
                        nativeSources.get(PatchShaderType.FRAGMENT));
                var adapted = IrisWaterShaderPatcher.patch("gbuffers_water", fragment, nativeSources);
                if (adapted == nativeSources) throw new AssertionError("BSL " + dimension + " adapter rejected");
                int vs = compile(GL46C.GL_VERTEX_SHADER, adapted.get(PatchShaderType.VERTEX));
                int fs = compile(GL46C.GL_FRAGMENT_SHADER, adapted.get(PatchShaderType.FRAGMENT));
                int program = GL46C.glCreateProgram();
                GL46C.glAttachShader(program, vs); GL46C.glAttachShader(program, fs); GL46C.glLinkProgram(program);
                if (GL46C.glGetProgrami(program, GL46C.GL_LINK_STATUS) == 0)
                    throw new AssertionError(GL46C.glGetProgramInfoLog(program));
                if (GL46C.glGetUniformLocation(program, "MmdWaterCapture") < 0)
                    throw new AssertionError("water capture optimized away");
                GL46C.glDeleteProgram(program); GL46C.glDeleteShader(vs); GL46C.glDeleteShader(fs);
                System.out.println("PASS native BSL water transform/adapter/link " + dimension);
                com.shiroha.mmdskin.render.material.BlockEntityTransparencyGlProbe.nativeWaterScenario(
                        adapted.get(PatchShaderType.FRAGMENT));
            }
        } finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }

    private static int compile(int type, String source) {
        int shader = GL46C.glCreateShader(type);
        GL46C.glShaderSource(shader, source); GL46C.glCompileShader(shader);
        if (GL46C.glGetShaderi(shader, GL46C.GL_COMPILE_STATUS) == 0)
            throw new AssertionError(GL46C.glGetShaderInfoLog(shader));
        return shader;
    }
}
