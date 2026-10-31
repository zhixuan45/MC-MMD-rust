package com.shiroha.mmdskin.render.effectpack;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** 一行批量材质的效果快照；每个通道占三个 vec4。 */
public record FxMaterialData(FxEffect effect, int selection, int[] textures) {
    public static final String[] CHANNELS = {"ALBEDO", "NORMAL", "SMOOTHNESS", "METALNESS", "SPECULAR", "OCCLUSION", "EMISSIVE", "ALBEDO_SUB", "NORMAL_SUB", "ALPHA"};
    public static final int CHANNEL_BYTES = CHANNELS.length * 48;
    public static final FxMaterialData NONE = new FxMaterialData(null, 0, new int[CHANNELS.length]);

    public static FxMaterialData capture(int index, String name) {
        EffectPackRuntime runtime = EffectPackRuntime.active();
        if (runtime == null || !runtime.enabled()) return NONE;
        FxEffect effect = runtime.materialEffect(index, name);
        if (effect == null) return NONE;
        int[] textures = new int[CHANNELS.length];
        if (effect.kind() == FxEffect.Kind.RAY_MATERIAL) {
            for (int i = 0; i < CHANNELS.length; i++) {
                var channel = effect.channels().get(CHANNELS[i]);
                if (channel != null && channel.source() == 1) {
                    textures[i] = runtime.texture(effect, channel);
                    if (textures[i] == 0) return NONE;
                }
            }
        }
        return new FxMaterialData(effect, runtime.program().selection(effect), textures);
    }

    public int kind() { return effect == null ? 0 : effect.kind() == FxEffect.Kind.RAY_MATERIAL ? 1 : 2; }
    public boolean externalAlpha() { return effect != null && effect.channels().containsKey("ALPHA"); }
    public int[] requiredTextures() { return Arrays.stream(textures).filter(id -> id > 0).distinct().toArray(); }

    public void writeChannels(ByteBuffer target, int[] textureSlots) {
        writeChannels(target, textureSlots, 1.0f);
    }

    public void writeChannels(ByteBuffer target, int[] textureSlots, float fxSpecularPower) {
        for (int i = 0; i < CHANNELS.length; i++) {
            var channel = effect == null ? null : effect.channels().get(CHANNELS[i]);
            float[] value = channel == null ? new float[]{0} : channel.value();
            float x = value[0];
            target.putFloat(x).putFloat(value.length > 1 ? value[1] : x).putFloat(value.length > 2 ? value[2] : x).putFloat(i == 2 ? fxSpecularPower : 1);
            target.putFloat(channel == null ? 0 : channel.uvFlip()).putFloat(channel == null ? 0 : channel.type())
                    .putFloat(channel == null ? 0 : channel.component()).putFloat(channel == null ? 0 : channel.scaleMode());
            target.putFloat(channel == null ? 1 : channel.repeat()[0]).putFloat(channel == null ? 1 : channel.repeat()[1])
                    .putFloat(channel == null ? 0 : channel.source()).putFloat(i < textureSlots.length ? textureSlots[i] : -1);
        }
    }
}
