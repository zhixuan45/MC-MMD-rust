package com.shiroha.mmdskin.render.shader;

import com.shiroha.mmdskin.compat.iris.IrisTerrainShadow;
import com.shiroha.mmdskin.render.material.ProgramSamplerLayout;
import org.lwjgl.opengl.GL46C;

/** One temporary texture binding shared by successive body and outline draws. */
final class TerrainShadowBinding {
    private int unit = -1, previousTexture, previousSampler;
    private int softSampler;

    void bind(int program, boolean enabled, float softness) {
        close();
        int flag = GL46C.glGetUniformLocation(program, "ToonTerrainShadowEnabled");
        int sampler = GL46C.glGetUniformLocation(program, "ToonTerrainShadowDepth");
        if (flag >= 0) GL46C.glUniform1i(flag, 0);
        if (sampler < 0) return;
        int[] units = ProgramSamplerLayout.effectUnits(program, new int[]{sampler}, 1);
        int originalUnit = ProgramSamplerLayout.comparisonShadowUnit(program, sampler);
        if (units.length == 0) {
            if (originalUnit >= 0) GL46C.glUniform1i(sampler, originalUnit);
            return;
        }
        // Even the disabled branch needs a distinct unit for the shadow sampler type.
        GL46C.glUniform1i(sampler, units[0]);
        int depth = enabled ? IrisTerrainShadow.texture() : 0;
        if (depth == 0) {
            if (enabled) IrisTerrainShadow.warnUnavailable();
            return;
        }
        unit = units[0];
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
        previousTexture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        previousSampler = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, unit);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, depth);
        // Preserve Iris hardware filtering when the pack uses a separate comparison sampler.
        int comparisonSampler = originalUnit < 0 ? 0 : GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, originalUnit);
        if (softness > 0) {
            if (softSampler == 0) {
                softSampler = GL46C.glGenSamplers();
                GL46C.glSamplerParameteri(softSampler, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_LINEAR);
                GL46C.glSamplerParameteri(softSampler, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_LINEAR);
                GL46C.glSamplerParameteri(softSampler, GL46C.GL_TEXTURE_WRAP_S, GL46C.GL_CLAMP_TO_EDGE);
                GL46C.glSamplerParameteri(softSampler, GL46C.GL_TEXTURE_WRAP_T, GL46C.GL_CLAMP_TO_EDGE);
                GL46C.glSamplerParameteri(softSampler, GL46C.GL_TEXTURE_COMPARE_MODE, GL46C.GL_COMPARE_REF_TO_TEXTURE);
            }
            GL46C.glSamplerParameteri(softSampler, GL46C.GL_TEXTURE_COMPARE_FUNC,
                    comparisonSampler == 0 ? GL46C.GL_LEQUAL
                            : GL46C.glGetSamplerParameteri(comparisonSampler, GL46C.GL_TEXTURE_COMPARE_FUNC));
            comparisonSampler = softSampler;
        }
        GL46C.glBindSampler(unit, comparisonSampler);
        GL46C.glActiveTexture(active);
        if (flag >= 0) GL46C.glUniform1i(flag, 1);
    }

    void close() {
        if (unit < 0) return;
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, previousTexture);
        GL46C.glBindSampler(unit, previousSampler);
        GL46C.glActiveTexture(active);
        unit = -1;
    }

    void release() {
        close();
        if (softSampler != 0) GL46C.glDeleteSamplers(softSampler);
        softSampler = 0;
    }
}
