package com.shiroha.mmdskin.render.shader;

import com.shiroha.mmdskin.render.material.ProgramSamplerLayout;
import com.shiroha.mmdskin.render.port.IrisBackend;
import org.lwjgl.opengl.GL46C;

/** One temporary texture binding shared by successive body and outline draws. */
final class TerrainShadowBinding {
    private static final IrisBackend IRIS = IrisBackend.current();

    private int[] boundUnits = new int[0], previousTextures = new int[0], previousSamplers = new int[0];
    private int softSampler;
    private int uniformProgram = -1;
    private ProgramUniforms uniforms;

    private record ProgramUniforms(int flag, int transmissionFlag, int sampler,
                                   int translucent, int color, int albedo) {}

    void bind(int program, boolean enabled, float softness) {
        close();
        ProgramUniforms programUniforms = uniforms(program);
        int flag = programUniforms.flag;
        int sampler = programUniforms.sampler;
        int transmissionFlag = programUniforms.transmissionFlag;
        int translucent = programUniforms.translucent;
        int color = programUniforms.color;
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
                GL46C.glUniform1i(color, programUniforms.albedo < 0 ? 0
                        : GL46C.glGetUniformi(program, programUniforms.albedo));
            }
            return;
        }
        // Even the disabled branch needs a distinct unit for the shadow sampler type.
        for (int i = 0; i < locations.length; i++) GL46C.glUniform1i(locations[i], units[i]);
        IrisBackend.TerrainShadowTextures textures = IRIS.terrainShadowTextures();
        int depth = enabled ? textures.depth() : 0;
        if (depth == 0) {
            if (enabled) IRIS.warnTerrainShadowUnavailable();
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
        boolean transmission = translucent >= 0 && color >= 0 && textures.hasTransmission();
        boundUnits = units;
        previousTextures = new int[units.length];
        previousSamplers = new int[units.length];
        for (int i = 0; i < units.length; i++) {
            int unit = units[i], location = locations[i];
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
            previousTextures[i] = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
            previousSamplers[i] = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, unit);
            int target = location == color ? (transmission ? textures.transmissionColor() : 0)
                    : location == translucent && transmission ? textures.transmissionDepth() : depth;
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, target);
            GL46C.glBindSampler(unit, location == color ? 0 : comparisonSampler);
        }
        GL46C.glActiveTexture(active);
        if (flag >= 0) GL46C.glUniform1i(flag, 1);
        if (transmissionFlag >= 0) GL46C.glUniform1i(transmissionFlag, transmission ? 1 : 0);
    }

    private ProgramUniforms uniforms(int program) {
        if (uniformProgram != program || uniforms == null) {
            uniformProgram = program;
            uniforms = new ProgramUniforms(
                    GL46C.glGetUniformLocation(program, "ToonTerrainShadowEnabled"),
                    GL46C.glGetUniformLocation(program, "ToonTerrainShadowTransmissionEnabled"),
                    GL46C.glGetUniformLocation(program, "ToonTerrainShadowDepth"),
                    GL46C.glGetUniformLocation(program, "ToonTerrainShadowTranslucentDepth"),
                    GL46C.glGetUniformLocation(program, "ToonTerrainShadowColor"),
                    GL46C.glGetUniformLocation(program, "Sampler0"));
        }
        return uniforms;
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
        uniformProgram = -1;
        uniforms = null;
    }
}
