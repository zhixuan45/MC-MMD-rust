package com.shiroha.mmdskin.render.shader;

import com.shiroha.mmdskin.compat.iris.IrisTerrainShadow;
import com.shiroha.mmdskin.compat.iris.IrisTerrainTransmission;
import com.shiroha.mmdskin.render.material.ProgramSamplerLayout;
import org.lwjgl.opengl.GL46C;

/** One temporary texture binding shared by successive body and outline draws. */
final class TerrainShadowBinding {
    private int[] boundUnits = new int[0], previousTextures = new int[0], previousSamplers = new int[0];
    private int softSampler;

    void bind(int program, boolean enabled, float softness) {
        close();
        int flag = GL46C.glGetUniformLocation(program, "ToonTerrainShadowEnabled");
        int sampler = GL46C.glGetUniformLocation(program, "ToonTerrainShadowDepth");
        int transmissionFlag = GL46C.glGetUniformLocation(program, "ToonTerrainShadowTransmissionEnabled");
        int translucent = GL46C.glGetUniformLocation(program, "ToonTerrainShadowTranslucentDepth");
        int color = GL46C.glGetUniformLocation(program, "ToonTerrainShadowColor");
        if (flag >= 0) GL46C.glUniform1i(flag, 0);
        if (transmissionFlag >= 0) GL46C.glUniform1i(transmissionFlag, 0);
        if (sampler < 0) return;
        int[] locations = java.util.stream.IntStream.of(sampler, translucent, color).filter(value -> value >= 0).toArray();
        int[] units = ProgramSamplerLayout.effectUnits(program, locations, locations.length);
        int originalUnit = ProgramSamplerLayout.comparisonShadowUnit(program, sampler, translucent);
        if (units.length == 0) {
            if (originalUnit >= 0) GL46C.glUniform1i(sampler, originalUnit);
            if (originalUnit >= 0 && translucent >= 0) GL46C.glUniform1i(translucent, originalUnit);
            if (color >= 0) {
                int albedo = GL46C.glGetUniformLocation(program, "Sampler0");
                GL46C.glUniform1i(color, albedo < 0 ? 0 : GL46C.glGetUniformi(program, albedo));
            }
            return;
        }
        // Even the disabled branch needs a distinct unit for the shadow sampler type.
        for (int i = 0; i < locations.length; i++) GL46C.glUniform1i(locations[i], units[i]);
        int depth = enabled ? IrisTerrainShadow.texture() : 0;
        if (depth == 0) {
            if (enabled) IrisTerrainShadow.warnUnavailable();
            return;
        }
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
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
        boolean transmission = translucent >= 0 && color >= 0 && IrisTerrainTransmission.depthTexture() != 0
                && IrisTerrainTransmission.colorTexture() != 0;
        boundUnits = units;
        previousTextures = new int[units.length];
        previousSamplers = new int[units.length];
        for (int i = 0; i < units.length; i++) {
            int unit = units[i], location = locations[i];
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
            previousTextures[i] = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
            previousSamplers[i] = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, unit);
            int target = location == color ? (transmission ? IrisTerrainTransmission.colorTexture() : 0)
                    : location == translucent && transmission ? IrisTerrainTransmission.depthTexture() : depth;
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, target);
            GL46C.glBindSampler(unit, location == color ? 0 : comparisonSampler);
        }
        GL46C.glActiveTexture(active);
        if (flag >= 0) GL46C.glUniform1i(flag, 1);
        if (transmissionFlag >= 0) GL46C.glUniform1i(transmissionFlag, transmission ? 1 : 0);
    }

    void close() {
        if (boundUnits.length == 0) return;
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        for (int i = 0; i < boundUnits.length; i++) {
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + boundUnits[i]);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, previousTextures[i]);
            GL46C.glBindSampler(boundUnits[i], previousSamplers[i]);
        }
        GL46C.glActiveTexture(active);
        boundUnits = new int[0];
    }

    void release() {
        close();
        if (softSampler != 0) GL46C.glDeleteSamplers(softSampler);
        softSampler = 0;
    }
}
