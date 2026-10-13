package com.shiroha.mmdskin.render.material;

import org.lwjgl.opengl.GL46C;

/** 给 Toon uniform 或原版/Iris 顶点颜色传递每个 draw 的材质，退出时恢复 GL 状态。 */
final class MaterialShaderBinding implements AutoCloseable {
    private final int materialColor;
    private final int materialSpecular;
    private final int materialPower;
    private final int alphaPass;
    private final int colorModulator;
    private final float[] previousModulator = new float[4];
    private final int[] attributes;
    private final boolean[] enabled;
    private final float[][] previousColors;

    MaterialShaderBinding() {
        int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        materialColor = GL46C.glGetUniformLocation(program, "MaterialColor");
        materialSpecular = GL46C.glGetUniformLocation(program, "MaterialSpecular");
        materialPower = GL46C.glGetUniformLocation(program, "MaterialSpecularPower");
        alphaPass = GL46C.glGetUniformLocation(program, "AlphaPass");
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

    void apply(MaterialRenderState state, int pass) {
        if (materialColor >= 0) {
            GL46C.glUniform4f(materialColor, state.color[0], state.color[1], state.color[2], state.alpha);
            if (materialSpecular >= 0) GL46C.glUniform3f(materialSpecular,
                    state.specular[0], state.specular[1], state.specular[2]);
            if (materialPower >= 0) GL46C.glUniform1f(materialPower, state.specularPower);
        } else {
            boolean hasColorAttribute = false;
            for (int location : attributes) {
                if (location < 0) continue;
                hasColorAttribute = true;
                GL46C.glVertexAttrib4f(location, state.color[0], state.color[1], state.color[2], state.alpha);
            }
            // 全局 alpha 已在 state.alpha 中，不能再被 ColorModulator 重复相乘。
            if (colorModulator >= 0) GL46C.glUniform4f(colorModulator,
                    previousModulator[0] * (hasColorAttribute ? 1.0f : state.color[0]),
                    previousModulator[1] * (hasColorAttribute ? 1.0f : state.color[1]),
                    previousModulator[2] * (hasColorAttribute ? 1.0f : state.color[2]),
                    hasColorAttribute ? 1.0f : state.alpha);
        }
        if (alphaPass >= 0) GL46C.glUniform1i(alphaPass, pass);
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
    }
}
