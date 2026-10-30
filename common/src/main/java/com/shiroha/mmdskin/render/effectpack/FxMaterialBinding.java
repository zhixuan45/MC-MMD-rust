package com.shiroha.mmdskin.render.effectpack;

import com.shiroha.mmdskin.render.material.ProgramSamplerLayout;
import org.lwjgl.opengl.GL46C;

/** 模型级材质层保持在整个透明排序/批量提交作用域内，逐材质 PMX 状态继续由宿主管理。 */
public final class FxMaterialBinding implements AutoCloseable {
    private static final String[] CHANNELS = {"ALBEDO", "NORMAL", "SMOOTHNESS", "METALNESS", "SPECULAR", "OCCLUSION", "EMISSIVE"};
    private final int program, enabled;
    private final int[] samplers = new int[7], previousUniforms = new int[7];
    private int[] units = new int[0], previousTextures = new int[0], previousSamplers = new int[0];

    public FxMaterialBinding(boolean shadow) {
        program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        enabled = GL46C.glGetUniformLocation(program, "FxMaterialEnabled");
        if (enabled < 0) return;
        GL46C.glUniform1i(enabled, 0);
        FxEffect effect = EffectPackRuntime.get().current();
        if (shadow || effect == null || effect.kind() != FxEffect.Kind.RAY_MATERIAL) return;
        for (int i = 0; i < 7; i++) {
            samplers[i] = GL46C.glGetUniformLocation(program, "FxMap" + i);
            previousUniforms[i] = samplers[i] < 0 ? 0 : GL46C.glGetUniformi(program, samplers[i]);
        }
        long externalCount = effect.channels().values().stream().filter(c -> c.source() == 1).count();
        units = ProgramSamplerLayout.effectUnits(program, samplers, (int) Math.max(1, externalCount));
        if (units.length == 0) {
            EffectPackRuntime.get().problem("samplers:" + effect.id(), "当前渲染程序没有足够的空闲 sampler: " + effect.name());
            return;
        }
        previousTextures = new int[units.length];
        previousSamplers = new int[units.length];
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        try {
            for (int i = 0; i < units.length; i++) {
                GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + units[i]);
                previousTextures[i] = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
                previousSamplers[i] = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, units[i]);
            }
            int next = 0;
            boolean valid = true;
            for (int i = 0; i < CHANNELS.length; i++) {
                FxEffect.Channel channel = effect.channels().get(CHANNELS[i]);
                int source = channel == null ? 0 : channel.source();
                int unit = units[0];
                if (source == 1) {
                    int texture = EffectPackRuntime.get().texture(effect, channel);
                    if (texture == 0) valid = false;
                    unit = units[next++];
                    GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
                    GL46C.glBindSampler(unit, 0);
                    GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
                }
                if (samplers[i] >= 0) GL46C.glUniform1i(samplers[i], unit);
                uniform1i("FxSources[" + i + "]", source);
                float[] value = channel == null ? new float[]{0} : channel.value();
                if (i == 1 && source == 0) value = new float[]{0};
                float x = value[0], y = value.length > 1 ? value[1] : x, z = value.length > 2 ? value[2] : x;
                GL46C.glUniform4f(GL46C.glGetUniformLocation(program, "FxValues[" + i + "]"), x, y, z, 1);
                GL46C.glUniform4i(GL46C.glGetUniformLocation(program, "FxOptions[" + i + "]"),
                        channel == null ? 0 : channel.uvFlip(), channel == null ? 0 : channel.type(),
                        channel == null ? 0 : channel.component(), channel == null ? 0 : channel.scaleMode());
                GL46C.glUniform2f(GL46C.glGetUniformLocation(program, "FxRepeat[" + i + "]"),
                        channel == null ? 1 : channel.repeat()[0], channel == null ? 1 : channel.repeat()[1]);
            }
            GL46C.glUniform1f(GL46C.glGetUniformLocation(program, "FxAlpha"), effect.alpha());
            GL46C.glUniform1i(enabled, valid ? 1 : 0);
        } catch (RuntimeException | Error e) {
            close();
            throw e;
        } finally { GL46C.glActiveTexture(active); }
    }
    private void uniform1i(String name, int value) { GL46C.glUniform1i(GL46C.glGetUniformLocation(program, name), value); }

    @Override public void close() {
        if (enabled < 0) return;
        GL46C.glUniform1i(enabled, 0);
        if (units.length == 0) return;
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        for (int i = 0; i < units.length; i++) {
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + units[i]);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, previousTextures[i]);
            GL46C.glBindSampler(units[i], previousSamplers[i]);
        }
        for (int i = 0; i < 7; i++) if (samplers[i] >= 0) GL46C.glUniform1i(samplers[i], previousUniforms[i]);
        GL46C.glActiveTexture(active);
    }
}
