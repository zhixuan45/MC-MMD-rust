package com.shiroha.mmdskin.render.shader;

import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** 在实际 Toon 程序中验证包内环境色、阴影与 uniform 同步。 */
public final class LightingProbe {
    private record Uniform(String name, String type) {}

    /** 原包编译后的 uniform 不能因优化缺失，否则游戏中会整条回退。 */
    public static void checkPackProgram(ToonShaderCpu shader, ToonOutputProfile profile, String vertex, String fragment) {
        int program = ShaderCompiler.compileRenderProgram(vertex, fragment, "Complementary 原包光照来源");
        check(program > 0, "原包程序编译");
        try {
            shader.useMain();
            List<String> missing = activePackUniforms(shader.getMainProgram(), profile.lightingSource()).stream()
                    .filter(uniform -> GL46C.glGetUniformLocation(program, uniform.name()) < 0)
                    .map(Uniform::name).toList();
            check(shader.setEnvironmentLighting(program, 15, 15), "原包优化后的光照 uniform 不完整: " + profile.id() + " " + missing);
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "原包 uniform 类型同步");
        } finally {
            GL46C.glDeleteProgram(program);
        }
    }

    public static void run(ToonShaderCpu shader, ToonOutputProfile profile) {
        boolean bsl = profile.id().startsWith("bsl:");
        int target = shader.getMainProgram();
        List<Uniform> uniforms = activePackUniforms(target, profile.lightingSource());
        // 位置由另一程序独立分配，不能直接复用 Iris 的 location。
        int source = uniformSource(uniforms);
        Object sourceIdentity = new Object();
        int vao = GL46C.glGenVertexArrays();
        GL46C.glBindVertexArray(vao);
        int positions = buffer(0, 3, new float[]{-1,-1,-.5f, 3,-1,-.5f, -1,3,-.5f});
        int normals = buffer(1, 3, new float[]{0,1,0, 0,1,0, 0,1,0});
        int uvs = buffer(2, 2, new float[]{0,0, 0,0, 0,0});
        int albedo = colorTexture(0);
        List<Integer> packTextures = new ArrayList<>();
        int shadowTexture = 0;
        int opaqueShadowTexture = 0;
        GL46C.glUseProgram(source);
        int unit = 4;
        for (Uniform uniform : uniforms) {
            int location = GL46C.glGetUniformLocation(source, uniform.name());
            switch (uniform.type()) {
                case "mat4" -> GL46C.glUniformMatrix4fv(location, false, identity());
                case "ivec2" -> GL46C.glUniform2i(location, 240, 240);
                case "vec3" -> GL46C.glUniform3f(location, .5f, .5f, .5f);
                case "int" -> GL46C.glUniform1i(location, 0);
                case "float" -> GL46C.glUniform1f(location, uniform.name().equals("screenBrightness") ? .5f
                        : uniform.name().equals("shadowFade") ? 1 : 0);
                case "sampler2D", "sampler2DShadow" -> {
                    int texture = uniform.type().equals("sampler2DShadow") ? depthTexture(unit) : colorTexture(unit);
                    GL46C.glUniform1i(location, unit++);
                    packTextures.add(texture);
                    if (uniform.name().equals("shadowtex0")) shadowTexture = texture;
                    if (uniform.name().equals("shadowtex1")) opaqueShadowTexture = texture;
                }
                default -> throw new AssertionError("探针缺少 uniform 类型: " + uniform);
            }
        }
        int fbo = GL46C.glGenFramebuffers();
        int output = colorTexture(1);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA32F, 16, 16, 0, GL46C.GL_RGBA, GL46C.GL_FLOAT, (float[]) null);
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, output, 0);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "光照 FBO");
        GL46C.glViewport(0, 0, 16, 16);
        GL46C.glDisable(GL46C.GL_BLEND);
        GL46C.glDisable(GL46C.GL_DEPTH_TEST);
        GL46C.glDisable(GL46C.GL_CULL_FACE);
        shader.useMain();
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(target, "ProjMat"), false, identity());
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(target, "ModelViewMat"), false, identity());
        shader.setSampler0(0);
        shader.setToonLevels(3);
        shader.setShadowColor(.65f, .7f, .8f);
        shader.setGlobalAlpha(1);
        shader.setAlphaCutoff(.1f);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, albedo);
        float[] day = sample(shader, source, sourceIdentity, .25f, 0, 0, 15);
        float[] night = sample(shader, source, sourceIdentity, .75f, 0, 0, 15);
        float[] cave = sample(shader, source, sourceIdentity, .25f, 0, 0, 0);
        float[] torch = sample(shader, source, sourceIdentity, .25f, 0, 15, 0);
        float[] rain = sample(shader, source, sourceIdentity, .25f, 1, 0, 15);
        if (shadowTexture > 0) {
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + GL46C.glGetUniformi(source, GL46C.glGetUniformLocation(source, "shadowtex0")));
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, shadowTexture);
            GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, new float[]{0});
        }
        if (bsl && opaqueShadowTexture > 0) setShadowDepth(source, "shadowtex1", opaqueShadowTexture, 0);
        float[] shadow = sample(shader, source, sourceIdentity, .25f, 0, 0, 15);
        System.out.printf("Lighting linear pixels: day=%s night=%s cave=%s torch=%s rain=%s shadow=%s%n",
                Arrays.toString(day), Arrays.toString(night), Arrays.toString(cave), Arrays.toString(torch), Arrays.toString(rain), Arrays.toString(shadow));
        check(luminance(day) > luminance(night) * 1.5f, "白天应明显亮于夜晚");
        check(luminance(torch) > luminance(cave) * 2, "火把应照亮洞穴");
        check(torch[0] > torch[2], "火把应保留暖色");
        check(luminance(day) > luminance(shadow), "阴影图应降低直射光");
        check(luminance(day) > luminance(rain), "降雨应影响直射光");
        if (shadowTexture > 0) {
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + GL46C.glGetUniformi(source, GL46C.glGetUniformLocation(source, "shadowtex0")));
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, shadowTexture);
            GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, new float[]{1});
        }
        if (bsl && opaqueShadowTexture > 0) setShadowDepth(source, "shadowtex1", opaqueShadowTexture, 1);
        GL46C.glUseProgram(source);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "sunAngle"), .25f);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "timeAngle"), .25f);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "rainStrengthS"), 0);
        var rotation = Pattern.compile("sunPathRotation\\s*=\\s*([-0-9.]+)").matcher(profile.lightingSource());
        check(rotation.find(), "太阳旋转参数");
        double angle = Math.toRadians(Double.parseDouble(rotation.group(1)));
        if (!bsl) ToonControlsProbe.run(shader, source, sourceIdentity, new float[]{0,(float)Math.cos(angle),-(float)Math.sin(angle)});
        shader.useMain();
        check(!shader.setEnvironmentLighting(0, 15, 15), "GUI/无 Iris 应关闭包内光照");
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, 0);
        GL46C.glDeleteFramebuffers(fbo);
        for (int texture : packTextures) GL46C.glDeleteTextures(texture);
        GL46C.glDeleteTextures(albedo);
        GL46C.glDeleteTextures(output);
        GL46C.glDeleteBuffers(positions);
        GL46C.glDeleteBuffers(normals);
        GL46C.glDeleteBuffers(uvs);
        GL46C.glDeleteVertexArrays(vao);
        GL46C.glDeleteProgram(source);
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "光照探针 GL 状态");
        System.out.println("PASS lighting: production shader, uniform bridge, daylight/night/cave/torch/rain/shadow, GUI reset");
    }

    private static float[] sample(ToonShaderCpu shader, int source, Object identity, float angle, float rain, int block, int sky) {
        GL46C.glUseProgram(source);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "sunAngle"), angle);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "timeAngle"), angle);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "timeBrightness"), angle < .5f ? 1 : 0);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "rainStrengthS"), rain);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "rainStrength"), rain);
        shader.useMain();
        check(shader.setEnvironmentLighting(source, identity, block, sky), "实际包光照 uniform 同步失败");
        GL46C.glClearColor(0, 0, 0, 0);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        float[] pixel = new float[4];
        GL46C.glReadPixels(8, 8, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, pixel);
        for (float value : pixel) check(Float.isFinite(value), "光照不能产生 NaN/Inf");
        return pixel;
    }

    private static void setShadowDepth(int source, String name, int texture, float depth) {
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + GL46C.glGetUniformi(source, GL46C.glGetUniformLocation(source, name)));
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
        GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, new float[]{depth});
    }

    private static List<Uniform> activePackUniforms(int program, String source) {
        List<Uniform> uniforms = new ArrayList<>();
        var matcher = Pattern.compile("uniform\\s+(\\w+)\\s+([^;]+);").matcher(source);
        while (matcher.find()) {
            for (String variable : matcher.group(2).split(",")) {
                String name = variable.trim();
                if (GL46C.glGetUniformLocation(program, name) >= 0) {
                    uniforms.add(new Uniform(name.equals("ToonPackTexture") ? "texture" : name, matcher.group(1)));
                }
            }
        }
        return uniforms;
    }

    private static int uniformSource(List<Uniform> uniforms) {
        StringBuilder fragment = new StringBuilder("#version 330 core\nlayout(location=0) out vec4 c;\n");
        StringBuilder sink = new StringBuilder("void main(){ float s=0.0;\n");
        for (Uniform uniform : uniforms.reversed()) {
            fragment.append("uniform ").append(uniform.type()).append(' ').append(uniform.name()).append(";\n");
            String expression = switch (uniform.type()) {
                case "mat4" -> uniform.name() + "[0][0]";
                case "vec3", "ivec2" -> "float(" + uniform.name() + ".x)";
                case "sampler2D" -> "texture(" + uniform.name() + ",vec2(0.5)).r";
                case "sampler2DShadow" -> "texture(" + uniform.name() + ",vec3(0.5))";
                default -> "float(" + uniform.name() + ")";
            };
            sink.append("s+=").append(expression).append(";\n");
        }
        fragment.append(sink).append("c=vec4(s);}");
        int program = ShaderCompiler.compileRenderProgram("#version 330 core\nvoid main(){gl_Position=vec4(0.0);}", fragment.toString(), "光照 uniform 来源探针");
        check(program > 0, "来源程序编译");
        return program;
    }

    private static int colorTexture(int unit) {
        int id = GL46C.glGenTextures();
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA32F, 1, 1, 0, GL46C.GL_RGBA, GL46C.GL_FLOAT, new float[]{1,1,1,1});
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        return id;
    }

    private static int depthTexture(int unit) {
        int id = colorTexture(unit);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_DEPTH_COMPONENT32F, 1, 1, 0, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, new float[]{1});
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_MODE, GL46C.GL_COMPARE_REF_TO_TEXTURE);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_FUNC, GL46C.GL_LEQUAL);
        return id;
    }

    private static int buffer(int location, int size, float[] data) {
        int id = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, id);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, data, GL46C.GL_STATIC_DRAW);
        GL46C.glEnableVertexAttribArray(location);
        GL46C.glVertexAttribPointer(location, size, GL46C.GL_FLOAT, false, 0, 0);
        return id;
    }

    private static float[] identity() { return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}; }
    private static float luminance(float[] rgb) { return rgb[0]*.2126f + rgb[1]*.7152f + rgb[2]*.0722f; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
