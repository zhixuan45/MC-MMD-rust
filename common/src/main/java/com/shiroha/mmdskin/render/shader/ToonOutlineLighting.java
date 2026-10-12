package com.shiroha.mmdskin.render.shader;

import org.lwjgl.opengl.GL46C;

/** 描边沿用主体照明输入，独立缓存位置，不回读主体颜色。 */
final class ToonOutlineLighting {
    float intensity = 1, softness = 1.2f;
    int levels = 4;
    float shadowR = .78f, shadowG = .84f, shadowB = .94f;
    float lightX = .35f, lightY = .85f, lightZ = -.4f;
    private int program;
    private int enabled, lightIntensity, toonLevels, transition, shadow, direction, usePack, lightmap;
    private int specPower, specIntensity, rimPower, rimIntensity;
    private final GenericToonLighting generic = new GenericToonLighting();
    private ToonUniformBridge bridge;

    void select(int target, String source) {
        program = target;
        enabled = location("FollowLighting");
        lightIntensity = location("LightIntensity");
        toonLevels = location("ToonLevels");
        transition = location("ToonTransitionSoftness");
        shadow = location("ShadowColor");
        direction = location("LightDir");
        usePack = location("UsePackLighting");
        lightmap = location("ToonLightmap");
        specPower = location("SpecularPower");
        specIntensity = location("SpecularIntensity");
        rimPower = location("RimPower");
        rimIntensity = location("RimIntensity");
        generic.select(target);
        bridge = source.isEmpty() ? null : new ToonUniformBridge(source);
    }

    void syncEnvironment(int source, Object identity, int block, int sky, boolean mainCopied, int main) {
        GL46C.glUseProgram(program);
        try {
            // 主体回退时描边也回退，防止同一人物使用两套环境照明。
            boolean copied = mainCopied && bridge != null && bridge.copy(source, program, identity);
            generic.upload(copied ? 0 : source, identity);
            GL46C.glUniform1i(usePack, copied ? 1 : 0);
            GL46C.glUniform2f(lightmap, (block * 16 + 8) / 256f, (sky * 16 + 8) / 256f);
        } finally {
            GL46C.glUseProgram(main);
        }
    }

    void upload() {
        setEnabled(ToonConfig.getInstance().isOutlineFollowLighting());
        GL46C.glUniform1f(lightIntensity, intensity);
        GL46C.glUniform1i(toonLevels, levels);
        GL46C.glUniform1f(transition, softness);
        GL46C.glUniform3f(shadow, shadowR, shadowG, shadowB);
        GL46C.glUniform3f(direction, lightX, lightY, lightZ);
        // 描边只受照明与色阶调制，不增加白色高光或边缘光。
        GL46C.glUniform1f(specPower, 1);
        GL46C.glUniform1f(specIntensity, 0);
        GL46C.glUniform1f(rimPower, 1);
        GL46C.glUniform1f(rimIntensity, 0);
    }

    void setEnabled(boolean value) {
        GL46C.glUniform1i(enabled, value ? 1 : 0);
    }

    private int location(String name) {
        return GL46C.glGetUniformLocation(program, name);
    }
}
