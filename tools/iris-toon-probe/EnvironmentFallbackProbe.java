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
            checkGenericPixels(shader, source, identity);
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

    private static void checkGenericPixels(ToonShaderCpu shader, int source, Object identity) {
        int vao = GL46C.glGenVertexArrays();
        GL46C.glBindVertexArray(vao);
        int[] buffers = new int[3];
        float[][] data = {{-1,-1,-.5f, 3,-1,-.5f, -1,3,-.5f}, {0,1,0, 0,1,0, 0,1,0}, {0,0, 0,0, 0,0}};
        for (int i = 0; i < 3; i++) {
            buffers[i] = GL46C.glGenBuffers();
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, buffers[i]);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, data[i], GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribPointer(i, i == 2 ? 2 : 3, GL46C.GL_FLOAT, false, 0, 0);
            GL46C.glEnableVertexAttribArray(i);
        }
        int[] textures = new int[2];
        for (int i = 0; i < 2; i++) {
            textures[i] = GL46C.glGenTextures();
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, textures[i]);
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA32F, i == 0 ? 1 : 16, i == 0 ? 1 : 16,
                    0, GL46C.GL_RGBA, GL46C.GL_FLOAT, i == 0 ? new float[]{1,1,1,1} : null);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        }
        int fbo = GL46C.glGenFramebuffers();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, textures[1], 0);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        try {
            check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "通用环境光 FBO");
            GL46C.glViewport(0,0,16,16);
            GL46C.glDisable(GL46C.GL_BLEND); GL46C.glDisable(GL46C.GL_DEPTH_TEST); GL46C.glDisable(GL46C.GL_CULL_FACE);
            shader.useMain();
            float[] matrix = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(shader.getMainProgram(), "ProjMat"), false, matrix);
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(shader.getMainProgram(), "ModelViewMat"), false, matrix);
            shader.setSampler0(0); shader.setLightIntensity(.8f); shader.setGlobalAlpha(1); shader.setAlphaCutoff(.1f);
            shader.setToonLevels(4); shader.setShadowColor(.3f,.3f,.3f); shader.setSpecular(96,0); shader.setRimLight(5,0);
            shader.setLightDirection(0,1,0); shader.setEyeEmission(0);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, textures[0]);
            float[] lit = genericPixel(shader, source, identity, 1, 0, 0, 15);
            float[] backlit = genericPixel(shader, source, identity, -1, 0, 0, 15);
            float[] rain = genericPixel(shader, source, identity, 1, 1, 0, 15);
            float[] torch = genericPixel(shader, source, identity, 1, 0, 15, 0);
            check(lit[0] > backlit[0] * 2, "通用照明太阳方向应改变色阶");
            check(lit[0] > rain[0], "通用照明雨天应变暗");
            check(torch[0] > torch[2], "通用方块光应保留暖色");
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "通用照明像素 GL 状态");
            System.out.println("PASS generic pixels: solar Toon bands, weather attenuation, warm blocklight");
        } finally {
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, 0); GL46C.glBindVertexArray(0);
            GL46C.glDeleteFramebuffers(fbo); GL46C.glDeleteVertexArrays(vao);
            for (int buffer : buffers) GL46C.glDeleteBuffers(buffer);
            for (int texture : textures) GL46C.glDeleteTextures(texture);
        }
    }

    private static float[] genericPixel(ToonShaderCpu shader, int source, Object identity, float sunY, float rain, int block, int sky) {
        GL46C.glUseProgram(source);
        GL46C.glUniform3f(GL46C.glGetUniformLocation(source, "shadowLightPosition"),0,sunY,0);
        GL46C.glUniform3f(GL46C.glGetUniformLocation(source, "fogColor"),1,1,1);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "rainStrength"),rain);
        shader.useMain(); shader.setEnvironmentLighting(source, identity, block, sky);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES,0,3);
        float[] pixel = new float[4];
        GL46C.glReadPixels(8,8,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,pixel);
        for (float value : pixel) check(Float.isFinite(value), "通用照明有限颜色");
        return pixel;
    }
}
