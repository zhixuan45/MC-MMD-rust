package com.shiroha.mmdskin.render.material;

import com.shiroha.mmdskin.render.effectpack.EffectPackRuntime;
import com.shiroha.mmdskin.render.effectpack.FxEffect;
import org.lwjgl.opengl.GL46C;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** 包装已知的主贴图采样；保留包的 RGB、UV 表达式与 alpha-test。 */
final class ShadowFxAlphaBinding implements AutoCloseable {
    private final int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
    private final int enabled = GL46C.glGetUniformLocation(program, "MmdShadowFxEnabled");
    private int unit = -1, texture, sampler;

    ShadowFxAlphaBinding(boolean active) {
        if (enabled < 0 || !active) return;
        int location = GL46C.glGetUniformLocation(program, "MmdShadowFxMap");
        int[] units = ProgramSamplerLayout.effectUnits(program, new int[]{location}, 1);
        if (units.length == 0) return;
        unit = units[0];
        int previous = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
        texture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        sampler = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, unit);
        GL46C.glActiveTexture(previous);
        GL46C.glUniform1i(location, unit);
        GL46C.glUniform1i(enabled, 0);
    }

    void apply(int index, String name) {
        EffectPackRuntime runtime = EffectPackRuntime.active();
        if (runtime == null) return;
        FxEffect effect = runtime.materialEffect(index, name);
        var channel = effect == null ? null : effect.channels().get("ALPHA");
        if (enabled >= 0) GL46C.glUniform1i(enabled, 0);
        if (channel == null) return;
        if (unit < 0) {
            runtime.problem("shadow-alpha:" + effect.id(), "当前阴影 shader 未适配外置 alpha: " + effect.name());
            return;
        }
        int id = runtime.texture(effect, channel);
        if (id <= 0) return;
        int previous = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        try {
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
            GL46C.glBindSampler(unit, 0);
            GL46C.glUniform2f(GL46C.glGetUniformLocation(program, "MmdShadowFxRepeat"), channel.repeat()[0], channel.repeat()[1]);
            GL46C.glUniform2i(GL46C.glGetUniformLocation(program, "MmdShadowFxOptions"), channel.uvFlip(), channel.component());
            GL46C.glUniform1i(enabled, 1);
        } finally { GL46C.glActiveTexture(previous); }
    }

    static String patch(String fragment, int original) {
        String clean = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL).matcher(fragment)
                .replaceAll(m -> " ".repeat(m.group().length()));
        var main = Pattern.compile("void\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{").matcher(clean);
        if (!main.find()) throw new IllegalArgumentException("阴影入口不支持");
        // Iris 可能将原 main 改写为辅助函数；按唯一主 sampler 包装所有两参数采样。
        var samplers = Pattern.compile("uniform\\s+sampler2D\\s+(\\w+)\\s*;").matcher(clean);
        var names = new ArrayList<String>();
        while (samplers.find()) {
            int location = GL46C.glGetUniformLocation(original, samplers.group(1));
            if (location >= 0 && GL46C.glGetUniformi(original, location) == 0) names.add(samplers.group(1));
        }
        if (names.size() != 1) throw new IllegalArgumentException("阴影主纹理语义不唯一");
        var entry = Pattern.compile("\\b(?:void|float|int|bool|vec[234]|mat[234])\\s+\\w+\\s*\\([^;{}]*\\)\\s*\\{").matcher(clean);
        if (!entry.find()) throw new IllegalArgumentException("阴影入口不支持");
        int start = entry.start();
        var calls = Pattern.compile("\\b(texture2D|texture)\\s*\\(\\s*" + Pattern.quote(names.getFirst()) + "\\s*,").matcher(clean);
        StringBuilder modified = new StringBuilder(); int end = 0, count = 0;
        while (calls.find()) {
            if (calls.start() < start) throw new IllegalArgumentException("阴影全局采样尚未适配");
            int depth = 1, cursor = calls.end();
            while (cursor < clean.length() && depth > 0) {
                char c = clean.charAt(cursor++);
                if (c == '(') depth++;
                if (c == ')') depth--;
                if (c == ',' && depth == 1) throw new IllegalArgumentException("阴影 LOD/bias 采样尚未适配");
            }
            if (depth != 0) throw new IllegalArgumentException("阴影采样语法不完整");
            modified.append(fragment, end, calls.start()).append("mmdShadowFxSample(").append(names.getFirst()).append(',');
            end = calls.end(); count++;
        }
        if (count == 0) throw new IllegalArgumentException("未找到阴影主纹理采样");
        modified.append(fragment.substring(end));
        String code = modified.toString();
        var version = Pattern.compile("(?m)^\\s*#\\s*version\\s+(\\d+)").matcher(clean);
        String sample = !version.find() || Integer.parseInt(version.group(1)) < 130 ? "texture2D" : "texture";
        String helper = """
                uniform bool MmdShadowFxEnabled;
                uniform sampler2D MmdShadowFxMap;
                uniform vec2 MmdShadowFxRepeat;
                uniform ivec2 MmdShadowFxOptions;
                vec4 mmdShadowFxSample(sampler2D original, vec2 uv) {
                    vec4 c = SAMPLE(original, uv);
                    if (MmdShadowFxEnabled) {
                        vec2 q = uv;
                        if (MmdShadowFxOptions.x == 1 || MmdShadowFxOptions.x == 3) q.x = 1.0-q.x;
                        if (MmdShadowFxOptions.x == 2 || MmdShadowFxOptions.x == 3) q.y = 1.0-q.y;
                        c.a = SAMPLE(MmdShadowFxMap, q * MmdShadowFxRepeat)[MmdShadowFxOptions.y];
                    }
                    return c;
                }
                """.replace("SAMPLE", sample);
        return code.substring(0, start) + helper + code.substring(start);
    }

    @Override public void close() {
        if (enabled >= 0) GL46C.glUniform1i(enabled, 0);
        if (unit < 0) return;
        int previous = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture); GL46C.glBindSampler(unit, sampler);
        GL46C.glActiveTexture(previous);
    }
}
