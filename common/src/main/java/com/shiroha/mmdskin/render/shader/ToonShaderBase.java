package com.shiroha.mmdskin.render.shader;

import com.shiroha.mmdskin.util.AssetsUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL46C;

import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Toon 着色器抽象基类。
 */
public abstract class ToonShaderBase {
    protected static final Logger logger = LogManager.getLogger();

    protected int mainProgram = 0;
    protected int outlineProgram = 0;
    protected boolean initialized = false;
    private final Map<String, Programs> variants = new LinkedHashMap<>();
    private final Set<String> failedVariants = new HashSet<>();
    private String activeProfile;

    private record Programs(int main, int outline) {}

    protected static final String MAIN_FRAGMENT_SHADER_BODY =
            AssetsUtil.getAssetsAsString("shader/toon_main_body.frag.glsl");

    protected static final String OUTLINE_FRAGMENT_SHADER_BODY =
            AssetsUtil.getAssetsAsString("shader/toon_outline_body.frag.glsl");

    protected int projMatLocation = -1;
    protected int modelViewMatLocation = -1;
    protected int sampler0Location = -1;
    protected int lightIntensityLocation = -1;
    protected int toonLevelsLocation = -1;
    protected int rimPowerLocation = -1;
    protected int rimIntensityLocation = -1;
    protected int shadowColorLocation = -1;
    protected int specularPowerLocation = -1;
    protected int specularIntensityLocation = -1;
    protected int lightDirLocation = -1;
    protected int alphaCutoffLocation = -1;
    protected int globalAlphaLocation = -1;

    protected int outlineProjMatLocation = -1;
    protected int outlineModelViewMatLocation = -1;
    protected int outlineWidthLocation = -1;
    protected int outlineColorLocation = -1;
    protected int outlineAlphaLocation = -1;

    protected int positionLocation = -1;
    protected int normalLocation = -1;
    protected int uv0Location = -1;
    protected int outlinePositionLocation = -1;
    protected int outlineNormalLocation = -1;

    protected abstract String getMainVertexShader();

    protected abstract String getOutlineVertexShader();

    protected abstract void onInitialized();

    protected abstract String getShaderName();

    public boolean init() {
        return initialized || selectOutputProfile(ToonOutputProfile.vanilla());
    }

    public boolean selectOutputProfile(ToonOutputProfile profile) {
        if (!profile.isSupported()) return false;
        String key = profile.id();
        if (initialized && key.equals(activeProfile)) return true;
        if (failedVariants.contains(key)) return false;
        try {
            Programs programs = variants.get(key);
            if (programs == null) {
                int main = compileProgram(getMainVertexShader(), outputSource(MAIN_FRAGMENT_SHADER_BODY, profile),
                        getShaderName() + "主着色器/" + key);
                if (main == 0) {
                    failedVariants.add(key);
                    return false;
                }
                int outline = compileProgram(getOutlineVertexShader(), outputSource(OUTLINE_FRAGMENT_SHADER_BODY, profile),
                        getShaderName() + "描边着色器/" + key);
                if (outline == 0) {
                    GL46C.glDeleteProgram(main);
                    failedVariants.add(key);
                    return false;
                }
                programs = new Programs(main, outline);
                variants.put(key, programs);
            }
            mainProgram = programs.main();
            outlineProgram = programs.outline();
            activeProfile = key;
            // 每个变体的 uniform 位置独立，切换后重新绑定。
            initCommonUniforms();
            initCommonAttributes();
            onInitialized();
            initialized = true;
            trimVariants();
            return true;
        } catch (Exception e) {
            failedVariants.add(key);
            logger.error("{} 输出策略初始化异常: {}", getShaderName(), key, e);
            return false;
        }
    }

    static String outputSource(String body, ToonOutputProfile profile) {
        return body.replace("/* TOON_OUTPUT_DECLARATIONS */", profile.fragmentDeclarations())
                .replace("/* TOON_OUTPUT_WRITER */", profile.fragmentWriter());
    }

    private void trimVariants() {
        // 限制不同布局占用的 GPU 程序数，保留无光影变体。
        var iterator = variants.entrySet().iterator();
        while (variants.size() > 16 && iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().equals(activeProfile) || entry.getKey().equals(ToonOutputProfile.vanilla().id())) continue;
            deletePrograms(entry.getValue());
            iterator.remove();
        }
    }

    private static void deletePrograms(Programs programs) {
        GL46C.glDeleteProgram(programs.main());
        GL46C.glDeleteProgram(programs.outline());
    }
    private void initCommonUniforms() {

        projMatLocation = GL46C.glGetUniformLocation(mainProgram, "ProjMat");
        modelViewMatLocation = GL46C.glGetUniformLocation(mainProgram, "ModelViewMat");
        sampler0Location = GL46C.glGetUniformLocation(mainProgram, "Sampler0");
        lightIntensityLocation = GL46C.glGetUniformLocation(mainProgram, "LightIntensity");
        toonLevelsLocation = GL46C.glGetUniformLocation(mainProgram, "ToonLevels");
        rimPowerLocation = GL46C.glGetUniformLocation(mainProgram, "RimPower");
        rimIntensityLocation = GL46C.glGetUniformLocation(mainProgram, "RimIntensity");
        shadowColorLocation = GL46C.glGetUniformLocation(mainProgram, "ShadowColor");
        specularPowerLocation = GL46C.glGetUniformLocation(mainProgram, "SpecularPower");
        specularIntensityLocation = GL46C.glGetUniformLocation(mainProgram, "SpecularIntensity");
        lightDirLocation = GL46C.glGetUniformLocation(mainProgram, "LightDir");
        alphaCutoffLocation = GL46C.glGetUniformLocation(mainProgram, "AlphaCutoff");
        globalAlphaLocation = GL46C.glGetUniformLocation(mainProgram, "GlobalAlpha");

        outlineProjMatLocation = GL46C.glGetUniformLocation(outlineProgram, "ProjMat");
        outlineModelViewMatLocation = GL46C.glGetUniformLocation(outlineProgram, "ModelViewMat");
        outlineWidthLocation = GL46C.glGetUniformLocation(outlineProgram, "OutlineWidth");
        outlineColorLocation = GL46C.glGetUniformLocation(outlineProgram, "OutlineColor");
        outlineAlphaLocation = GL46C.glGetUniformLocation(outlineProgram, "OutlineAlpha");
    }

    private void initCommonAttributes() {

        positionLocation = GL46C.glGetAttribLocation(mainProgram, "Position");
        normalLocation = GL46C.glGetAttribLocation(mainProgram, "Normal");
        uv0Location = GL46C.glGetAttribLocation(mainProgram, "UV0");

        outlinePositionLocation = GL46C.glGetAttribLocation(outlineProgram, "Position");
        outlineNormalLocation = GL46C.glGetAttribLocation(outlineProgram, "Normal");
    }

    protected int compileProgram(String vertexSource, String fragmentSource, String name) {
        return ShaderCompiler.compileRenderProgram(vertexSource, fragmentSource, name);
    }

    public void useMain() {
        if (mainProgram > 0) {
            GL46C.glUseProgram(mainProgram);
        }
    }

    public void useOutline() {
        if (outlineProgram > 0) {
            GL46C.glUseProgram(outlineProgram);
        }
    }

    public void setProjectionMatrix(FloatBuffer matrix) {
        if (projMatLocation >= 0) {
            matrix.position(0);
            GL46C.glUniformMatrix4fv(projMatLocation, false, matrix);
        }
    }

    public void setModelViewMatrix(FloatBuffer matrix) {
        if (modelViewMatLocation >= 0) {
            matrix.position(0);
            GL46C.glUniformMatrix4fv(modelViewMatLocation, false, matrix);
        }
    }

    public void setSampler0(int textureUnit) {
        if (sampler0Location >= 0) {
            GL46C.glUniform1i(sampler0Location, textureUnit);
        }
    }

    public void setLightIntensity(float intensity) {
        if (lightIntensityLocation >= 0) {
            GL46C.glUniform1f(lightIntensityLocation, intensity);
        }
    }

    public void setToonLevels(int levels) {
        if (toonLevelsLocation >= 0) {
            GL46C.glUniform1i(toonLevelsLocation, Math.max(2, Math.min(5, levels)));
        }
    }

    public void setRimLight(float power, float intensity) {
        if (rimPowerLocation >= 0) {
            GL46C.glUniform1f(rimPowerLocation, power);
        }
        if (rimIntensityLocation >= 0) {
            GL46C.glUniform1f(rimIntensityLocation, intensity);
        }
    }

    public void setShadowColor(float r, float g, float b) {
        if (shadowColorLocation >= 0) {
            GL46C.glUniform3f(shadowColorLocation, r, g, b);
        }
    }

    public void setSpecular(float power, float intensity) {
        if (specularPowerLocation >= 0) {
            GL46C.glUniform1f(specularPowerLocation, power);
        }
        if (specularIntensityLocation >= 0) {
            GL46C.glUniform1f(specularIntensityLocation, intensity);
        }
    }

    public void setLightDirection(float x, float y, float z) {
        if (lightDirLocation >= 0) {
            GL46C.glUniform3f(lightDirLocation, x, y, z);
        }
    }

    public void setAlphaCutoff(float cutoff) {
        if (alphaCutoffLocation >= 0) {
            GL46C.glUniform1f(alphaCutoffLocation, cutoff);
        }
    }

    public void setOutlineProjectionMatrix(FloatBuffer matrix) {
        if (outlineProjMatLocation >= 0) {
            matrix.position(0);
            GL46C.glUniformMatrix4fv(outlineProjMatLocation, false, matrix);
        }
    }

    public void setOutlineModelViewMatrix(FloatBuffer matrix) {
        if (outlineModelViewMatLocation >= 0) {
            matrix.position(0);
            GL46C.glUniformMatrix4fv(outlineModelViewMatLocation, false, matrix);
        }
    }

    public void setOutlineWidth(float width) {
        if (outlineWidthLocation >= 0) {
            GL46C.glUniform1f(outlineWidthLocation, width);
        }
    }

    public void setOutlineColor(float r, float g, float b) {
        if (outlineColorLocation >= 0) {
            GL46C.glUniform3f(outlineColorLocation, r, g, b);
        }
    }

    public void setOutlineAlpha(float alpha) {
        if (outlineAlphaLocation >= 0) {
            GL46C.glUniform1f(outlineAlphaLocation, Math.max(0.0f, Math.min(1.0f, alpha)));
        }
    }

    public void setGlobalAlpha(float alpha) {
        if (globalAlphaLocation >= 0) {
            GL46C.glUniform1f(globalAlphaLocation, alpha);
        }
    }

    public int getMainProgram() { return mainProgram; }
    public int getOutlineProgram() { return outlineProgram; }

    public int getPositionLocation() { return positionLocation; }
    public int getNormalLocation() { return normalLocation; }
    public int getUv0Location() { return uv0Location; }

    public int getOutlinePositionLocation() { return outlinePositionLocation; }
    public int getOutlineNormalLocation() { return outlineNormalLocation; }

    public boolean isInitialized() { return initialized; }

    public void cleanup() {
        variants.values().forEach(ToonShaderBase::deletePrograms);
        variants.clear();
        failedVariants.clear();
        mainProgram = 0;
        outlineProgram = 0;
        activeProfile = null;
        initialized = false;
    }
}
