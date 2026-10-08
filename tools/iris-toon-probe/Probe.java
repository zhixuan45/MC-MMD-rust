import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** 本机可选探针：读取真实光影并在隐藏窗口验证 GLSL 和输出值。 */
public final class Probe {
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#include\\s+\"([^\"]+)\"[^\\r\\n]*");
    private static final Pattern DEFINE = Pattern.compile("(?m)^(\\s*)(//\\s*)?#define\\s+(\\w+)([^\\r\\n]*)");
    private static final Pattern BUFFERS = Pattern.compile("/\\*\\s*DRAWBUFFERS:([0-9]+)\\s*\\*/");
    private static int checked;

    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) throw new IllegalStateException("无法初始化 GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(16, 16, "Toon output probe", 0, 0);
        if (window == 0) throw new IllegalStateException("无法创建隐藏 OpenGL 上下文");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("OpenGL=" + GL46C.glGetString(GL46C.GL_VERSION));
        ToonShaderCpu shader = new ToonShaderCpu();
        try {
            if (!shader.init()) throw new AssertionError("无光影 Toon 编译失败");
            int vanillaProgram = shader.getMainProgram();
            for (String pack : List.of("ComplementaryShaders_v4.7.1.zip", "BSL_v8.4.02.2.zip")) {
                Path path = Path.of(args[0], pack);
                Map<String, String> currentOptions = options(Path.of(path + ".txt"));
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    List<Map<String, String>> variants = new ArrayList<>();
                    variants.add(currentOptions);
                    if (pack.startsWith("BSL")) {
                        for (String blend : List.of("0", "1")) {
                            for (String advanced : List.of("true", "false")) {
                                for (String colored : List.of("true", "false")) {
                                    for (String selective : List.of("true", "false")) {
                                        Map<String, String> config = new HashMap<>(currentOptions);
                                        config.put("ALPHA_BLEND", blend);
                                        config.put("ADVANCED_MATERIALS", advanced);
                                        config.put("REFLECTION_SPECULAR", "true");
                                        config.put("TAA_SELECTIVE", selective);
                                        config.put("MULTICOLORED_BLOCKLIGHT", colored);
                                        variants.add(config);
                                    }
                                }
                            }
                        }
                    } else {
                        for (String advanced : List.of("true", "false")) {
                            Map<String, String> config = new HashMap<>(currentOptions);
                            config.put("ADV_MAT", advanced);
                            config.put("REFLECTION_SPECULAR", "true");
                            variants.add(config);
                        }
                    }
                    for (int variant = 0; variant < variants.size(); variant++) {
                        for (String program : List.of("gbuffers_entities", "gbuffers_hand")) {
                            for (String dimension : List.of("world0", "world-1", "world1")) {
                                String wrapper = "shaders/" + dimension + "/" + program + ".fsh";
                                if (zip.getEntry(wrapper) == null) wrapper = "shaders/" + program + ".fsh";
                                String source = include(zip, wrapper, variants.get(variant));
                                source = JcppProcessor.glslPreprocessSource(source,
                                List.of(new StringPair("MC_VERSION", "12101"), new StringPair("IS_IRIS", "1")));
                                Matcher matcher = BUFFERS.matcher(source);
                                String drawBuffers = null;
                                while (matcher.find()) drawBuffers = matcher.group(1);
                                if (drawBuffers == null) throw new AssertionError("缺少 DRAWBUFFERS: " + program);
                                int[] buffers = drawBuffers.chars().map(value -> value - '0').toArray();
                                ToonOutputProfile profile = ToonOutputProfile.resolve(pack, program, source, buffers);
                                Path output = Path.of("build/iris-toon-probe", pack + "-" + variant + "-" + dimension + "-" + program + ".glsl");
                                Files.writeString(output, source);
                                if (!profile.isSupported()) throw new AssertionError(pack + "/" + program + "/" + variant + ": " + profile.reason());
                                if (!shader.selectOutputProfile(profile)) throw new AssertionError("主/描边编译失败: " + profile.id());
                                checkOutput(profile, buffers, source, pack.startsWith("BSL"));
                                checked++;
                                System.out.println(pack + "/" + dimension + "/" + program + "/" + variant + " -> " + profile.id() + " OK");
                            }
                        }
                    }
                }
            }
            if (!shader.selectOutputProfile(ToonOutputProfile.vanilla()) || shader.getMainProgram() != vanillaProgram) {
                throw new AssertionError("切换回无光影时未复用原程序");
            }
            shader.cleanup();
            if (GL46C.glIsProgram(vanillaProgram)) throw new AssertionError("程序未释放");
            OverlayProbe.run();
            if (GL46C.glGetError() != GL46C.GL_NO_ERROR) throw new AssertionError("OpenGL 状态异常");
            System.out.println("PASS: " + checked + " 个实际程序/选项组合，主程序、描边、颜色回读和变体切换");
        } finally {
            shader.cleanup();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
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
        if (Files.exists(path)) try (var stream = Files.newInputStream(path)) { properties.load(stream); }
        Map<String, String> result = new HashMap<>();
        for (String name : properties.stringPropertyNames()) result.put(name, properties.getProperty(name));
        return result;
    }

    private static void checkOutput(ToonOutputProfile profile, int[] buffers, String source, boolean bsl) {
        String vertex = "#version 330 core\nvoid main(){ vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2); gl_Position=vec4(p*2.0-1.0,0.0,1.0); }";
        String fragment = "#version 330 core\n" + profile.fragmentDeclarations() + profile.fragmentWriter()
        + "\nuniform vec3 TestNormal;\nvoid main(){writeToonOutputs(vec3(0.5,0.25,0.75),vec3(0.6,0.4,0.2),TestNormal,0.4);}";
        int program = ShaderCompiler.compileRenderProgram(vertex, fragment, "输出回读/" + profile.id());
        if (program == 0) throw new AssertionError("回读程序编译失败");
        int framebuffer = GL46C.glGenFramebuffers();
        int vao = GL46C.glGenVertexArrays();
        int[] textures = new int[buffers.length];
        int[] attachments = new int[buffers.length];
        try {
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, framebuffer);
            for (int i = 0; i < buffers.length; i++) {
                textures[i] = GL46C.glGenTextures();
                attachments[i] = GL46C.GL_COLOR_ATTACHMENT0 + i;
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, textures[i]);
                GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA32F, 1, 1, 0,
                GL46C.GL_RGBA, GL46C.GL_FLOAT, (java.nio.ByteBuffer) null);
                GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, attachments[i], GL46C.GL_TEXTURE_2D, textures[i], 0);
            }
            GL46C.glDrawBuffers(attachments);
            if (GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE) {
                throw new AssertionError("输出 FBO 不完整");
            }
            GL46C.glUseProgram(program);
            GL46C.glBindVertexArray(vao);
            GL46C.glViewport(0, 0, 1, 1);
            GL46C.glDisable(GL46C.GL_BLEND);
            GL46C.glDisable(GL46C.GL_DEPTH_TEST);
            String compact = source.replaceAll("\\s+", "");
            boolean sqrtBlend = bsl && compact.contains("albedo.rgb=sqrt(max(albedo.rgb,vec3(0.0)))");
            for (float normalZ : List.of(1.0f, -1.0f)) {
                GL46C.glUniform3f(GL46C.glGetUniformLocation(program, "TestNormal"), 0, 0, normalZ);
                GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
                for (int i = 0; i < buffers.length; i++) {
                    float[] pixels = new float[4];
                    GL46C.glReadBuffer(attachments[i]);
                    GL46C.glReadPixels(0, 0, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, pixels);
                    for (float pixel : pixels) if (!Float.isFinite(pixel)) throw new AssertionError("非有限输出");
                    if (buffers[i] == 0) {
                        double[] color = {0.5, 0.25, 0.75};
                        for (int c = 0; c < 3; c++) {
                            double expected = Math.pow(color[c], sqrtBlend ? 1.1 : 2.2);
                            if (Math.abs(pixels[c] - expected) > 0.0001) throw new AssertionError("颜色编码错误: " + pixels[c] + " != " + expected);
                        }
                        if (Math.abs(pixels[3] - 0.4f) > 0.0001) throw new AssertionError("Alpha 被改变");
                    }
                    if (buffers[i] == 6) {
                        double x = pixels[0] * 4.0 - 2.0;
                        double y = pixels[1] * 4.0 - 2.0;
                        double decodedZ = 1.0 - (x * x + y * y) / 2.0;
                        if (Math.abs(decodedZ - normalZ) > 0.0001) throw new AssertionError("法线极点编码错误");
                    }
                    if (buffers[i] == 1 && !bsl) {
                        double[] raw = {0.6, 0.4, 0.2};
                        for (int c = 0; c < 3; c++) {
                            double expected = Math.pow(raw[c], 2.2) * 0.999 + 0.001;
                            if (Math.abs(pixels[c] - expected) > 0.0001) throw new AssertionError("原色编码错误");
                        }
                    }
                }
            }
        } finally {
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, 0);
            GL46C.glDeleteTextures(textures);
            GL46C.glDeleteFramebuffers(framebuffer);
            GL46C.glDeleteVertexArrays(vao);
            GL46C.glDeleteProgram(program);
        }
    }
}
