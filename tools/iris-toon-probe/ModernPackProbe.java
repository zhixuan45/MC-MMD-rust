import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import com.shiroha.mmdskin.render.shader.TranslucentBatchShader;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
        run(shaderpacks, "12101", "10814");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: ModernPackProbe <shaderpacks> [mcVersion] [irisVersion]");
        }
        String minecraftVersion = args.length > 1 ? args[1] : "12101";
        String irisVersion = args.length > 2 ? args[2] : "10814";
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
        for (Path pack : packPaths(shaderpacks)) runPack(pack, minecraftVersion, irisVersion);
    }

    private static void runPack(Path pack, String minecraftVersion, String irisVersion) throws Exception {
        if (!Files.isRegularFile(pack)) throw new AssertionError("缺少真实包: " + pack);
        String packName = pack.getFileName().toString();
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
                        checkBatchRegistration(shader, fragment, vertex, dimension, program, profile);
                        System.out.println("PASS modern " + packName + " " + dimension + "/" + program
                                + " main+outline+batch -> " + profile.id());
                    } finally {
                        shader.cleanup();
                    }
                }
            }
        }
        if (checked != DIMENSIONS.size() * PROGRAMS.size()) throw new AssertionError("现代包程序覆盖不完整: " + checked);
        System.out.println("PASS modern package " + packName + ": " + checked
                + " real program main+outline+batch compilations with sampler/SSBO checks");
    }

    private static List<Path> packPaths(Path shaderpacks) {
        List<Path> paths = new ArrayList<>();
        for (String packName : PACKS) paths.add(shaderpacks.resolve(packName));
        String extra = System.getProperty("mmdskin.irisProbe.extraPack", "").trim();
        if (!extra.isEmpty()) {
            Path path = Path.of(extra).toAbsolutePath().normalize();
            boolean duplicate = paths.stream().map(value -> value.toAbsolutePath().normalize())
                    .anyMatch(path::equals);
            if (!duplicate) {
                if (!Files.isRegularFile(path)) {
                    throw new AssertionError("mmdskin.irisProbe.extraPack 不存在: " + path);
                }
                paths.add(path);
            }
        }
        return paths;
    }

    private static void checkBatchRegistration(ToonShaderCpu shader, String fragment, String vertex,
                                               String dimension, String program, ToonOutputProfile profile)
            throws Exception {
        if (!profile.id().startsWith("complementary_modern:")) return;
        String variant = profile.id() + " " + dimension + "/" + program;
        if (!TranslucentBatchShader.supported()) {
            throw new AssertionError(variant + " 合批能力不可用");
        }
        checkPackLightingUniforms(shader, vertex, fragment, variant);
        shader.useMain();
        int target = shader.getMainProgram();
        if (TranslucentBatchShader.current() == null) {
            throw new AssertionError(variant + " 现代 Complementary 合批未登记到实际主程序");
        }
        checkSamplerLayout(target, profile.lightingSource(), variant);
        checkStorageBindings(target, variant);
    }

    private static void checkPackLightingUniforms(ToonShaderCpu shader, String vertex, String fragment,
                                                  String variant) {
        int source = ShaderCompiler.compileRenderProgram(vertex, fragment, "ModernPackProbe original");
        if (source <= 0) throw new AssertionError(variant + " 原包光照来源编译失败");
        try {
            GL46C.glUseProgram(source);
            assignSamplerUnits(source, variant);
            shader.useMain();
            if (!shader.setEnvironmentLighting(source, new Object(), 15, 15)) {
                throw new AssertionError(variant + " 原包光照 uniform 同步失败");
            }
            if (GL46C.glGetError() != GL46C.GL_NO_ERROR) {
                throw new AssertionError(variant + " 原包 uniform 类型产生 GL 错误");
            }
        } finally {
            GL46C.glDeleteProgram(source);
            shader.useMain();
        }
    }

    private static int assignSamplerUnits(int program, String variant) {
        int maxUnits = Math.min(GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_IMAGE_UNITS),
                GL46C.glGetInteger(GL46C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
        // Keep 0..3 available for Sampler0 and the model's optional material textures.
        int nextUnit = 4, assigned = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            int active = GL46C.glGetProgrami(program, GL46C.GL_ACTIVE_UNIFORMS);
            for (int index = 0; index < active; index++) {
                String name = GL46C.glGetActiveUniform(program, index, size, type);
                if (!isSampler(type.get(0))) continue;
                int count = Math.max(1, size.get(0));
                for (int element = 0; element < count; element++) {
                    if (nextUnit >= maxUnits) {
                        throw new AssertionError(variant + " 原包 sampler 超出可用纹理单元: "
                                + assigned + " sampled, max=" + maxUnits);
                    }
                    String entry = count == 1 ? name : name.replace("[0]", "[" + element + "]");
                    int location = GL46C.glGetUniformLocation(program, entry);
                    if (location < 0) {
                        throw new AssertionError(variant + " 原包 sampler 缺少 uniform 位置: " + entry);
                    }
                    GL46C.glUniform1i(location, nextUnit++);
                    assigned++;
                }
            }
        }
        if (assigned == 0) throw new AssertionError(variant + " 原包没有活动 sampler");
        return assigned;
    }

    private static void checkSelectedProfile(ToonShaderCpu shader, ToonOutputProfile profile) {
        try {
            var selected = Class.forName("com.shiroha.mmdskin.render.shader.ToonShaderBase")
                    .getDeclaredField("activeProfile");
            selected.setAccessible(true);
            if (!profile.id().equals(selected.get(shader))) {
                throw new AssertionError("实际使用了光照回退: " + profile.id());
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("无法校验实际 Toon profile", exception);
        }
    }

    private static void checkSamplerLayout(int program, String lightingSource, String variant) throws Exception {
        int maxUnits = GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_IMAGE_UNITS);
        Set<String> packSamplers = samplerNames(lightingSource);
        boolean[] occupied = new boolean[maxUnits];
        int activeSamplers = 0, batchSamplers = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            int active = GL46C.glGetProgrami(program, GL46C.GL_ACTIVE_UNIFORMS);
            for (int index = 0; index < active; index++) {
                String name = GL46C.glGetActiveUniform(program, index, size, type);
                if (!isSampler(type.get(0))) continue;
                int count = Math.max(1, size.get(0));
                if (name.startsWith("MmdBatchTextures")) {
                    batchSamplers += count;
                    continue;
                }
                activeSamplers += count;
                for (int element = 0; element < count; element++) {
                    String entry = count == 1 ? name : name.replace("[0]", "[" + element + "]");
                    int location = GL46C.glGetUniformLocation(program, entry);
                    int unit = location < 0 ? -1 : GL46C.glGetUniformi(program, location);
                    if (unit < 0 || unit >= maxUnits) {
                        throw new AssertionError(variant + " 采样器单元越界: " + entry + "=" + unit
                                + ", max=" + maxUnits);
                    }
                    String base = entry.replaceFirst("\\[\\d+\\]$", "");
                    if (packSamplers.contains(base.replaceFirst("^ToonPackTexture$", "texture"))
                            && occupied[unit]) {
                        throw new AssertionError(variant + " 采样器纹理单元冲突: " + entry + "=" + unit);
                    }
                    if (packSamplers.contains(base.replaceFirst("^ToonPackTexture$", "texture")))
                        occupied[unit] = true;
                }
            }
        }
        int free = 0;
        for (boolean used : occupied) if (!used) free++;
        if (activeSamplers + 1 > maxUnits || free == 0) {
            throw new AssertionError(variant + " 材质合批无空闲纹理单元: active=" + activeSamplers
                    + ", max=" + maxUnits);
        }
        if (batchSamplers != 8) {
            throw new AssertionError(variant + " 材质表 sampler 数量异常: " + batchSamplers);
        }
        Class<?> layoutClass = Class.forName("com.shiroha.mmdskin.render.material.ProgramSamplerLayout");
        Method capture = layoutClass.getDeclaredMethod("capture", int.class);
        capture.setAccessible(true);
        if (capture.invoke(null, program) == null) {
            throw new AssertionError(variant + " ProgramSamplerLayout 拒绝合批采样器布局");
        }
    }

    private static Set<String> samplerNames(String source) {
        Set<String> names = new HashSet<>();
        var matcher = Pattern.compile("\\buniform\\s+\\w*sampler\\w*\\s+([^;]+);").matcher(source);
        while (matcher.find()) {
            for (String declaration : matcher.group(1).split(",")) {
                names.add(declaration.trim().replaceFirst("\\s*\\[.*", ""));
            }
        }
        return names;
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

    private static void checkStorageBindings(int program, String variant) {
        int maxBlocks = GL46C.glGetInteger(GL46C.GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS);
        int active = GL46C.glGetProgramInterfacei(program, GL46C.GL_SHADER_STORAGE_BLOCK,
                GL46C.GL_ACTIVE_RESOURCES);
        if (maxBlocks < 2 || active < 2 || active > maxBlocks) {
            throw new AssertionError(variant + " SSBO 数量/容量异常: active=" + active
                    + ", max=" + maxBlocks);
        }
        boolean materials = false, triangles = false;
        for (int index = 0; index < active; index++) {
            String name = GL46C.glGetProgramResourceName(program, GL46C.GL_SHADER_STORAGE_BLOCK, index);
            int[] length = new int[1];
            int[] binding = new int[1];
            GL46C.glGetProgramResourceiv(program, GL46C.GL_SHADER_STORAGE_BLOCK, index,
                    new int[]{GL46C.GL_BUFFER_BINDING}, length, binding);
            if (name.equals("MmdBatchMaterials") && binding[0] == 2) materials = true;
            else if (name.equals("MmdBatchTriangles") && binding[0] == 3) triangles = true;
            else if (binding[0] == 2 || binding[0] == 3) {
                throw new AssertionError(variant + " SSBO 绑定冲突: " + name + "=" + binding[0]
                        + ", expected MmdBatchMaterials=2/MmdBatchTriangles=3");
            }
        }
        if (!materials || !triangles) {
            throw new AssertionError(variant + " 材质合批 SSBO 不完整");
        }
    }

    private static boolean isSampler(int type) {
        return switch (type) {
            case GL46C.GL_SAMPLER_1D, GL46C.GL_SAMPLER_2D, GL46C.GL_SAMPLER_3D,
                    GL46C.GL_SAMPLER_CUBE, GL46C.GL_SAMPLER_1D_SHADOW, GL46C.GL_SAMPLER_2D_SHADOW,
                    GL46C.GL_SAMPLER_CUBE_SHADOW, GL46C.GL_SAMPLER_1D_ARRAY,
                    GL46C.GL_SAMPLER_2D_ARRAY, GL46C.GL_SAMPLER_1D_ARRAY_SHADOW,
                    GL46C.GL_SAMPLER_2D_ARRAY_SHADOW, GL46C.GL_SAMPLER_2D_RECT,
                    GL46C.GL_SAMPLER_2D_RECT_SHADOW, GL46C.GL_SAMPLER_BUFFER,
                    GL46C.GL_SAMPLER_2D_MULTISAMPLE, GL46C.GL_SAMPLER_2D_MULTISAMPLE_ARRAY,
                    GL46C.GL_SAMPLER_CUBE_MAP_ARRAY, GL46C.GL_SAMPLER_CUBE_MAP_ARRAY_SHADOW,
                    GL46C.GL_INT_SAMPLER_1D, GL46C.GL_INT_SAMPLER_2D, GL46C.GL_INT_SAMPLER_3D,
                    GL46C.GL_INT_SAMPLER_CUBE, GL46C.GL_INT_SAMPLER_1D_ARRAY,
                    GL46C.GL_INT_SAMPLER_2D_ARRAY, GL46C.GL_INT_SAMPLER_2D_RECT,
                    GL46C.GL_INT_SAMPLER_BUFFER, GL46C.GL_INT_SAMPLER_2D_MULTISAMPLE,
                    GL46C.GL_INT_SAMPLER_2D_MULTISAMPLE_ARRAY, GL46C.GL_INT_SAMPLER_CUBE_MAP_ARRAY,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_1D, GL46C.GL_UNSIGNED_INT_SAMPLER_2D,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_3D, GL46C.GL_UNSIGNED_INT_SAMPLER_CUBE,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_1D_ARRAY, GL46C.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_2D_RECT, GL46C.GL_UNSIGNED_INT_SAMPLER_BUFFER,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE_ARRAY,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_CUBE_MAP_ARRAY -> true;
            default -> false;
        };
    }

    private static String wrapper(ZipFile zip, String dimension, String program, String extension) {
        String dimensional = "shaders/" + dimension + "/" + program + "." + extension;
        if (zip.getEntry(dimensional) != null) return dimensional;
        String shared = "shaders/" + program + "." + extension;
        if (zip.getEntry(shared) != null) return shared;
        if (program.endsWith("_translucent")) {
            String fallback = program.substring(0, program.length() - "_translucent".length());
            String dimensionalFallback = "shaders/" + dimension + "/" + fallback + "." + extension;
            if (zip.getEntry(dimensionalFallback) != null) return dimensionalFallback;
            String sharedFallback = "shaders/" + fallback + "." + extension;
            if (zip.getEntry(sharedFallback) != null) return sharedFallback;
        }
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
