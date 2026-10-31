package com.shiroha.mmdskin.render.material;

import org.lwjgl.opengl.GL46C;

/** 给 Toon uniform 或原版/Iris 顶点颜色传递每个 draw 的材质，退出时恢复 GL 状态。 */
final class MaterialShaderBinding implements AutoCloseable {
    private final int materialColor;
    private final int materialSpecular;
    private final int materialPower;
    private final int fxMaterialPower;
    private final int alphaPass;
    private final int hairMaterial;
    private final boolean disableTransparency;
    private final boolean toon;
    private final int colorModulator;
    private final float[] previousModulator = new float[4];
    private final int[] attributes;
    private final boolean[] enabled;
    private final float[][] previousColors;
    private final float[] lastColor = new float[4];
    private final float[] lastSpecular = new float[3];
    private float lastPower;
    private int lastPass = -1;
    private int lastHair = -1;
    private boolean colorValid, specularValid, powerValid;
    private boolean modulatorValid;

    MaterialShaderBinding() {
        int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        materialColor = GL46C.glGetUniformLocation(program, "MaterialColor");
        materialSpecular = GL46C.glGetUniformLocation(program, "MaterialSpecular");
        materialPower = GL46C.glGetUniformLocation(program, "MaterialSpecularPower");
        fxMaterialPower = GL46C.glGetUniformLocation(program, "FxMaterialPower");
        alphaPass = GL46C.glGetUniformLocation(program, "AlphaPass");
        hairMaterial = GL46C.glGetUniformLocation(program, "HairMaterial");
        int opaque = GL46C.glGetUniformLocation(program, "DisableTransparency");
        toon = materialColor >= 0 && opaque >= 0;
        disableTransparency = materialColor >= 0 && opaque >= 0 && GL46C.glGetUniformi(program, opaque) != 0;
        int modulator = GL46C.glGetUniformLocation(program, "ColorModulator");
        colorModulator = modulator >= 0 ? modulator : GL46C.glGetUniformLocation(program, "iris_ColorModulator");
        if (colorModulator >= 0) GL46C.glGetUniformfv(program, colorModulator, previousModulator);
        int color = GL46C.glGetAttribLocation(program, "Color");
        int irisColor = GL46C.glGetAttribLocation(program, "iris_Color");
        attributes = materialColor >= 0 ? new int[0]
                : color == irisColor ? new int[] {color} : new int[] {color, irisColor};
        enabled = new boolean[attributes.length];
        previousColors = new float[attributes.length][4];
        for (int i = 0; i < attributes.length; i++) {
            int location = attributes[i];
            if (location < 0) continue;
            enabled[i] = GL46C.glGetVertexAttribi(location, GL46C.GL_VERTEX_ATTRIB_ARRAY_ENABLED) != 0;
            GL46C.glGetVertexAttribfv(location, GL46C.GL_CURRENT_VERTEX_ATTRIB, previousColors[i]);
            GL46C.glDisableVertexAttribArray(location);
        }
    }

    boolean splitsTextureAlpha() { return alphaPass >= 0; }
    boolean disablesTransparency() { return disableTransparency; }
    boolean isToon() { return toon; }

    void apply(MaterialRenderState state, int pass) {
        apply(state, pass, state.alpha);
    }

    void apply(MaterialRenderState state, int pass, float alpha) {
        if (fxMaterialPower >= 0) GL46C.glUniform1f(fxMaterialPower, state.fxSpecularPower);
        int hair = state.hair ? 1 : 0;
        if (hairMaterial >= 0 && lastHair != hair) {
            GL46C.glUniform1i(hairMaterial, hair);
            lastHair = hair;
        }
        boolean colorChanged = !colorValid || lastColor[0] != state.color[0]
                || lastColor[1] != state.color[1] || lastColor[2] != state.color[2] || lastColor[3] != alpha;
        if (materialColor >= 0) {
            if (colorChanged) GL46C.glUniform4f(materialColor, state.color[0], state.color[1], state.color[2], alpha);
            if (materialSpecular >= 0 && (!specularValid || lastSpecular[0] != state.specular[0]
                    || lastSpecular[1] != state.specular[1] || lastSpecular[2] != state.specular[2])) {
                GL46C.glUniform3f(materialSpecular,
                    state.specular[0], state.specular[1], state.specular[2]);
                System.arraycopy(state.specular, 0, lastSpecular, 0, 3);
                specularValid = true;
            }
            if (materialPower >= 0 && (!powerValid || lastPower != state.specularPower)) {
                GL46C.glUniform1f(materialPower, state.specularPower);
                lastPower = state.specularPower;
                powerValid = true;
            }
        } else {
            boolean hasColorAttribute = false;
            for (int location : attributes) {
                if (location < 0) continue;
                hasColorAttribute = true;
                if (colorChanged) GL46C.glVertexAttrib4f(location, state.color[0], state.color[1], state.color[2], alpha);
            }
            // 全局 alpha 已在 state.alpha 中，不能再被 ColorModulator 重复相乘。
            if (colorModulator >= 0 && (!modulatorValid || (!hasColorAttribute && colorChanged))) {
                GL46C.glUniform4f(colorModulator,
                    previousModulator[0] * (hasColorAttribute ? 1.0f : state.color[0]),
                    previousModulator[1] * (hasColorAttribute ? 1.0f : state.color[1]),
                    previousModulator[2] * (hasColorAttribute ? 1.0f : state.color[2]),
                    hasColorAttribute ? 1.0f : alpha);
                modulatorValid = true;
            }
        }
        if (colorChanged) {
            System.arraycopy(state.color, 0, lastColor, 0, 3);
            lastColor[3] = alpha;
            colorValid = true;
        }
        if (alphaPass >= 0 && lastPass != pass) {
            GL46C.glUniform1i(alphaPass, pass);
            lastPass = pass;
        }
    }

    @Override
    public void close() {
        for (int i = 0; i < attributes.length; i++) {
            int location = attributes[i];
            if (location < 0) continue;
            float[] color = previousColors[i];
            GL46C.glVertexAttrib4f(location, color[0], color[1], color[2], color[3]);
            if (enabled[i]) GL46C.glEnableVertexAttribArray(location);
        }
        if (colorModulator >= 0) GL46C.glUniform4f(colorModulator,
                previousModulator[0], previousModulator[1], previousModulator[2], previousModulator[3]);
        if (alphaPass >= 0) GL46C.glUniform1i(alphaPass, 0);
        if (hairMaterial >= 0) GL46C.glUniform1i(hairMaterial, 0);
    }
}
