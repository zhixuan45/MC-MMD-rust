package com.shiroha.mmdskin.render.material;

import org.lwjgl.opengl.GL46C;

/** 绘制作用域内绑定球面层，保留 Iris 采样器及纹理状态。 */
public final class SphereTextureBinding implements AutoCloseable {
    private final int mode, mul, add, sampler;
    private final int unit;
    private final int previousTexture, previousSampler, previousUniform;
    private int lastTexture = -1;
    private int lastMode = -1;
    private final float[] lastMul = new float[4], lastAdd = new float[4];
    private boolean factorsValid;

    public SphereTextureBinding(boolean shadowPass) {
        int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        mode = shadowPass ? -1 : GL46C.glGetUniformLocation(program, "SphereMode");
        mul = mode < 0 ? -1 : GL46C.glGetUniformLocation(program, "SphereMul");
        add = mode < 0 ? -1 : GL46C.glGetUniformLocation(program, "SphereAdd");
        sampler = mode < 0 ? -1 : GL46C.glGetUniformLocation(program, "SphereTexture");
        unit = sampler < 0 ? -1 : ProgramSamplerLayout.sphereUnit(program, sampler);
        if (unit < 0) {
            previousTexture = previousSampler = previousUniform = 0;
            if (mode >= 0) GL46C.glUniform1i(mode, 0);
            return;
        }
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
        previousTexture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        previousSampler = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, unit);
        previousUniform = GL46C.glGetUniformi(program, sampler);
        // 边界裁切在 GLSL 中完成，无需改共享纹理的 wrap/filter。
        GL46C.glBindSampler(unit, 0);
        GL46C.glUniform1i(sampler, unit);
        GL46C.glActiveTexture(active);
    }

    public void apply(ModelMaterial material, MaterialRenderState state) {
        if (mode < 0) return;
        boolean enabled = unit >= 0 && material.sphereTex > 0
                && (material.sphereMode == 1 || material.sphereMode == 2);
        int nextMode = enabled ? material.sphereMode : 0;
        if (lastMode != nextMode) {
            GL46C.glUniform1i(mode, nextMode);
            lastMode = nextMode;
        }
        if (!enabled) return;
        if (material.sphereTex != lastTexture) {
            int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, material.sphereTex);
            GL46C.glActiveTexture(active);
            lastTexture = material.sphereTex;
        }
        if (mul >= 0 && (!factorsValid || !java.util.Arrays.equals(lastMul, state.sphereMul))) {
            GL46C.glUniform4f(mul, state.sphereMul[0], state.sphereMul[1], state.sphereMul[2], state.sphereMul[3]);
            System.arraycopy(state.sphereMul, 0, lastMul, 0, 4);
        }
        if (add >= 0 && (!factorsValid || !java.util.Arrays.equals(lastAdd, state.sphereAdd))) {
            GL46C.glUniform4f(add, state.sphereAdd[0], state.sphereAdd[1], state.sphereAdd[2], state.sphereAdd[3]);
            System.arraycopy(state.sphereAdd, 0, lastAdd, 0, 4);
        }
        factorsValid = true;
    }

    @Override public void close() {
        if (mode >= 0) GL46C.glUniform1i(mode, 0);
        if (unit < 0) return;
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, previousTexture);
        GL46C.glBindSampler(unit, previousSampler);
        GL46C.glUniform1i(sampler, previousUniform);
        GL46C.glActiveTexture(active);
    }
}
