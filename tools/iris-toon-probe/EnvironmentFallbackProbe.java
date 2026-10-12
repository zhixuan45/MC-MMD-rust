import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import com.shiroha.mmdskin.render.shader.LightingProbe;
import com.shiroha.mmdskin.render.shader.ToonControlsProbe;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import org.lwjgl.opengl.GL46C;

/** 验证真实 Java 路由：通用输入、uniform 缺失、编译失败、重载与发光控制。 */
public final class EnvironmentFallbackProbe {
    private static final String VERTEX = "#version 330 core\nvoid main(){gl_Position=vec4(0.0);}";
    private static final String SOURCE = """
            #version 330 core
            uniform vec3 shadowLightPosition;
            uniform vec3 fogColor;
            uniform float rainStrength;
            out vec4 Color;
            void main(){Color=vec4(shadowLightPosition+fogColor+rainStrength,1.0);}
            """;
    private static final String NATIVE_FRAGMENT = """
            varying vec2 texCoord, lmCoord;
            varying vec3 normal, sunVec, upVec, eastVec;
            varying vec4 color;
            uniform float timeAngle;
            uniform float MissingIrradiance;
            vec3 lightVec = sunVec;
            vec3 ToWorld(vec3 p) { return p; }
            void GetLighting(inout vec3 a, out vec3 s, vec3 p, vec3 w, vec3 n, vec2 lm,
                    float sl, float nl, float vd, float ps, float e, float ss, float bs) { a *= MissingIrradiance; }
            void main(){ vec4 albedo = vec4(1); albedo.rgb = pow(albedo.rgb, vec3(2.2));
                /* DRAWBUFFERS:0 */ gl_FragData[0] = albedo; }
            """;
    private static final String NATIVE_VERTEX = """
            void main(){const vec2 sunRotationData=vec2(1,0);sunVec=vec3(0,1,0);
                upVec=normalize(vec3(0,1,0));eastVec=normalize(vec3(1,0,0));}
            """;

    public static void run() throws Exception {
        int source = ShaderCompiler.compileRenderProgram(VERTEX, SOURCE, "通用环境光输入");
        check(source != 0, "来源编译");
        Object identity = new Object();
        ToonShaderCpu shader = new ToonShaderCpu();
        try {
            GL46C.glUseProgram(source);
            GL46C.glUniform3f(GL46C.glGetUniformLocation(source, "shadowLightPosition"), 0, 0, 100);
            GL46C.glUniform3f(GL46C.glGetUniformLocation(source, "fogColor"), .2f, .4f, .8f);
            GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "rainStrength"), 1);
            check(shader.init(), "主体初始化");
            shader.useMain();
            check(!shader.setEnvironmentLighting(source, identity, 15, 15), "无原包函数须使用通用照明");
            int target = shader.getMainProgram();
            check(integer(target, "ToonGenericEnvironment") == 1, "通用照明开启");
            check(integer(target, "ToonHasEnvironmentDirection") == 1, "太阳方向读取");
            check(Math.abs(scalar(target, "ToonEnvironmentWeather") - .65f) < .0001, "雨天衰减");
            float[] value = new float[3];
            GL46C.glGetUniformfv(target, GL46C.glGetUniformLocation(target, "ToonEnvironmentDirection"), value);
            check(Math.abs(value[2]-1) < .0001, "方向归一化");
            // 缺失/错误类型的输入不能保留上一帧的太阳与天气。
            int empty = ShaderCompiler.compileRenderProgram(VERTEX,
                    "#version 330 core\nuniform int shadowLightPosition;out vec4 Color;void main(){Color=vec4(shadowLightPosition);}", "错误类型输入");
            try {
                shader.setEnvironmentLighting(empty, new Object(), 0, 0);
                check(integer(target, "ToonHasEnvironmentDirection") == 0, "缺失方向重置");
                check(Math.abs(scalar(target, "ToonEnvironmentWeather")-1) < .0001, "缺失天气重置");
            } finally { GL46C.glDeleteProgram(empty); }
            var profile = ToonOutputProfile.resolve("unrelated.zip", "gbuffers_entities", NATIVE_FRAGMENT, NATIVE_VERTEX, new int[]{0});
            check(profile.isSupported() && !profile.lightingSource().isEmpty(), "原包照明配置");
            check(shader.selectOutputProfile(profile), "原包照明编译");
            shader.useMain();
            check(!shader.setEnvironmentLighting(source, identity, 15, 15), "缺少原包 uniform 应回退");
            check(integer(shader.getMainProgram(), "UsePackLighting") == 0, "原包函数关闭");
            check(integer(shader.getMainProgram(), "ToonGenericEnvironment") == 1, "Toon 通用函数保留");
            ToonShaderCpu failed = new ToonShaderCpu() {
                @Override protected int compileProgram(String vertex, String fragment, String name) {
                    return name.contains(":light-") && !name.contains(":generic-light") ? 0 : super.compileProgram(vertex, fragment, name);
                }
            };
            try {
                check(failed.selectOutputProfile(profile), "原包照明编译失败后继续 Toon");
                int generic = failed.getMainProgram();
                check(generic > 0 && failed.selectOutputProfile(profile) && failed.getMainProgram() == generic,
                        "失败缓存须复用通用变体");
            } finally { failed.cleanup(); }
            check(shader.selectOutputProfile(ToonOutputProfile.vanilla()), "恢复 GUI 变体");
            ToonControlsProbe.run(shader, 0, null, new float[]{0, 1, 0});
            String sources = System.getenv("PROBE_BSL_SOURCES");
            if (sources != null) {
                try (var paths = java.nio.file.Files.list(java.nio.file.Path.of(sources))) {
                    var metadata = paths.filter(path -> path.toString().endsWith("-0-world0-gbuffers_entities.meta"))
                            .filter(path -> path.getFileName().toString().toLowerCase().contains("bsl")).findFirst().orElseThrow();
                    String prefix = metadata.toString().replaceFirst("\\.meta$", "");
                    String fragment = java.nio.file.Files.readString(java.nio.file.Path.of(prefix + ".source.fsh"));
                    String vertex = java.nio.file.Files.readString(java.nio.file.Path.of(prefix + ".source.vsh"));
                    int[] buffers = java.util.Arrays.stream(java.nio.file.Files.readAllLines(metadata).get(1).split(","))
                            .mapToInt(Integer::parseInt).toArray();
                    var actual = ToonOutputProfile.resolve(null, "gbuffers_entities", fragment, vertex, buffers);
                    check(shader.selectOutputProfile(actual), "真实 BSL 原包照明");
                    LightingProbe.run(shader, actual);
                }
            }
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "环境回退 GL 状态");
            System.out.println("PASS environment fallback: optional typed inputs, resets, missing native uniforms, native compile failure, cache reuse, GUI and eye controls");
        } finally { shader.cleanup(); GL46C.glDeleteProgram(source); }
    }

    private static int integer(int program, String name) {
        return GL46C.glGetUniformi(program, GL46C.glGetUniformLocation(program, name));
    }
    private static float scalar(int program, String name) {
        return GL46C.glGetUniformf(program, GL46C.glGetUniformLocation(program, name));
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
