package com.shiroha.mmdskin.render.effectpack;

import com.shiroha.mmdskin.render.material.ProgramSamplerLayout;
import org.lwjgl.opengl.GL46C;

/** 一次绘制中复用纹理单元；普通提交逐材质切换，批量提交由材质表选择效果。 */
public final class FxMaterialBinding implements AutoCloseable {
    private final int program, enabled, selection;
    private final int[] samplers = new int[FxMaterialData.CHANNELS.length], previousUniforms = new int[FxMaterialData.CHANNELS.length];
    private final int[] sources = new int[FxMaterialData.CHANNELS.length], values = new int[FxMaterialData.CHANNELS.length], options = new int[FxMaterialData.CHANNELS.length], repeats = new int[FxMaterialData.CHANNELS.length];
    private int[] units = new int[0], previousTextures = new int[0], previousSamplers = new int[0];
    private FxEffect applied;
    private boolean appliedOnce;

    public FxMaterialBinding(boolean shadow) {
        program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        enabled = GL46C.glGetUniformLocation(program, "FxMaterialEnabled");
        selection = GL46C.glGetUniformLocation(program, "FxSelection");
        if (enabled >= 0) GL46C.glUniform1i(enabled, 0);
        if (selection >= 0) GL46C.glUniform1i(selection, 0);
        var runtime = EffectPackRuntime.active();
        if (shadow || runtime == null || (enabled < 0 && selection < 0)) return;
        if (enabled >= 0) {
            for (int i = 0; i < FxMaterialData.CHANNELS.length; i++) {
                samplers[i] = GL46C.glGetUniformLocation(program, "FxMap" + i);
                previousUniforms[i] = samplers[i] < 0 ? 0 : GL46C.glGetUniformi(program, samplers[i]);
                sources[i] = GL46C.glGetUniformLocation(program, "FxSources[" + i + "]");
                values[i] = GL46C.glGetUniformLocation(program, "FxValues[" + i + "]");
                options[i] = GL46C.glGetUniformLocation(program, "FxOptions[" + i + "]");
                repeats[i] = GL46C.glGetUniformLocation(program, "FxRepeat[" + i + "]");
            }
            int count = runtime.program().effects().stream().filter(e -> e.kind() == FxEffect.Kind.RAY_MATERIAL)
                    .mapToInt(e -> (int)e.channels().values().stream().filter(c -> c.source() == 1).count()).max().orElse(0);
            units = ProgramSamplerLayout.effectUnits(program, samplers, Math.max(1, count));
            if (units.length == 0) runtime.problem("samplers", "当前程序没有足够的空闲 sampler，普通提交的 Ray 效果未应用。");
            previousTextures = new int[units.length]; previousSamplers = new int[units.length];
            int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
            for (int i = 0; i < units.length; i++) {
                GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + units[i]);
                previousTextures[i] = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
                previousSamplers[i] = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, units[i]);
            }
            GL46C.glActiveTexture(active);
        }
        try { apply(FxMaterialData.capture(-1, "")); }
        catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    public void apply(FxMaterialData data) {
        FxEffect effect = data.effect();
        if (appliedOnce && effect == applied) return;
        appliedOnce = true; applied = effect;
        if (selection >= 0) GL46C.glUniform1i(selection, data.selection());
        if (enabled < 0) return;
        GL46C.glUniform1i(enabled, 0);
        if (effect == null || effect.kind() != FxEffect.Kind.RAY_MATERIAL || units.length == 0) return;
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        try {
            int next = 0;
            for (int i = 0; i < FxMaterialData.CHANNELS.length; i++) {
                FxEffect.Channel channel = effect.channels().get(FxMaterialData.CHANNELS[i]);
                int from = channel == null ? 0 : channel.source(), unit = units[0];
                if (from == 1) {
                    unit = units[next++];
                    GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
                    GL46C.glBindSampler(unit, 0);
                    GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, data.textures()[i]);
                }
                if (samplers[i] >= 0) GL46C.glUniform1i(samplers[i], unit);
                GL46C.glUniform1i(sources[i], from);
                float[] value = channel == null ? new float[]{0} : channel.value();
                float x = value[0];
                GL46C.glUniform4f(values[i], x, value.length > 1 ? value[1] : x, value.length > 2 ? value[2] : x, 1);
                GL46C.glUniform4i(options[i], channel == null ? 0 : channel.uvFlip(), channel == null ? 0 : channel.type(),
                        channel == null ? 0 : channel.component(), channel == null ? 0 : channel.scaleMode());
                GL46C.glUniform2f(repeats[i], channel == null ? 1 : channel.repeat()[0], channel == null ? 1 : channel.repeat()[1]);
            }
            GL46C.glUniform1i(enabled, 1);
        } finally { GL46C.glActiveTexture(active); }
    }

    @Override public void close() {
        if (enabled >= 0) GL46C.glUniform1i(enabled, 0);
        if (selection >= 0) GL46C.glUniform1i(selection, 0);
        if (units.length == 0) return;
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        for (int i = 0; i < units.length; i++) {
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + units[i]);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, previousTextures[i]);
            GL46C.glBindSampler(units[i], previousSamplers[i]);
        }
        for (int i = 0; i < FxMaterialData.CHANNELS.length; i++) if (samplers[i] >= 0) GL46C.glUniform1i(samplers[i], previousUniforms[i]);
        GL46C.glActiveTexture(active);
    }
}
