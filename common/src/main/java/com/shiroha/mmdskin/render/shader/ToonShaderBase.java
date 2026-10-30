package com.shiroha.mmdskin.render.shader;

import com.shiroha.mmdskin.util.AssetsUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL46C;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

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
    private ToonUniformBridge lightingBridge;
    private final GenericToonLighting genericLighting = new GenericToonLighting();
    private final ToonOutlineLighting outlineLighting = new ToonOutlineLighting();
    private int packLightingLocation = -1;
    private int lightmapLocation = -1;
    private boolean lightingSyncWarned;
    private final Matrix4f outputViewToWorld = new Matrix4f();
    private float outputFar = 1.0f;
    private int mainOutputMatrixLocation = -1, mainOutputFarLocation = -1;
    private int eyeEmissionLocation = -1;
    private int eyeMaterialMaskLocation = -1;
    private int outlinePremultipliedLocation = -1;
    private int outlineOcclusionLocation = -1;
    private int outlineColorSlot;
    private int disableTransparencyLocation = -1, outlineDisableTransparencyLocation = -1;

    private record Programs(int main, int outline) {}

    protected static final String MAIN_FRAGMENT_SHADER_BODY =
            AssetsUtil.getAssetsAsString("shader/toon_main_body.frag.glsl");

    protected static final String OUTLINE_FRAGMENT_SHADER_BODY =
            AssetsUtil.getAssetsAsString("shader/toon_outline_body.frag.glsl");
    private static final String SURFACE_LIGHTING =
            AssetsUtil.getAssetsAsString("shader/toon_surface_lighting.glsl");

    protected int projMatLocation = -1;
    protected int modelViewMatLocation = -1;
    protected int sampler0Location = -1;
    protected int lightIntensityLocation = -1;
    protected int toonLevelsLocation = -1;
    protected int toonTransitionSoftnessLocation = -1;
    private int shaderFlatnessLocation = -1;
    private int shaderSphereSuppressionLocation = -1;
    protected int rimPowerLocation = -1;
    protected int rimIntensityLocation = -1;
    protected int shadowColorLocation = -1;
    protected int specularPowerLocation = -1;
    protected int specularIntensityLocation = -1;
    protected int lightDirLocation = -1;
    protected int alphaCutoffLocation = -1;
    private int hairAlphaCutoffLocation = -1;
    protected int globalAlphaLocation = -1;

    protected int outlineProjMatLocation = -1;
    protected int outlineModelViewMatLocation = -1;
    protected int outlineWidthLocation = -1;
    protected int outlineColorLocation = -1;
    protected int outlineAlphaLocation = -1;
    protected int outlineSamplerLocation = -1;
    protected int outlineAlphaCutoffLocation = -1;
    protected int outlineUseTextureAlphaLocation = -1;

    protected int positionLocation = -1;
    protected int normalLocation = -1;
    protected int uv0Location = -1;
    protected int outlinePositionLocation = -1;
    protected int outlineNormalLocation = -1;
    protected int outlineUvLocation = -1;

    protected abstract String getMainVertexShader();

    protected abstract String getOutlineVertexShader();

    protected abstract void onInitialized();

    protected abstract String getShaderName();

    public boolean init() {
        return initialized || selectOutputProfile(ToonOutputProfile.vanilla());
    }

    public boolean selectOutputProfile(ToonOutputProfile profile) {
        boolean knownFailure = failedVariants.contains(profile.id());
        if (selectProfileVariant(profile)) return true;
        if (!profile.isSupported() || profile.lightingSource().isEmpty()) return false;
        if (!knownFailure) logger.warn("Toon 原包照明编译失败，保留输出协议并使用通用环境光: {}", profile.id());
        return selectProfileVariant(profile.withoutPackLighting());
    }

    private boolean selectProfileVariant(ToonOutputProfile profile) {
        if (!profile.isSupported()) return false;
        var effects = com.shiroha.mmdskin.render.effectpack.EffectPackRuntime.active();
        String key = profile.id() + (effects == null ? "" : effects.variantKey());
        if (initialized && key.equals(activeProfile)) return true;
        if (failedVariants.contains(key)) return false;
        try {
            Programs programs = variants.get(key);
            if (programs == null) {
                String fragment = outputSource(MAIN_FRAGMENT_SHADER_BODY
                        .replace("/* MMD_FX_MATERIAL */", com.shiroha.mmdskin.render.effectpack.FxShaderSource.material(effects == null ? null : effects.current()))
                        .replace("/* MMD_FX_COLOR */", effects == null ? com.shiroha.mmdskin.render.effectpack.FxEffect.IDENTITY : effects.colorFunction()), profile);
                int main = 0;
                if (supportsBatchProfile(profile.id()) && TranslucentBatchShader.supported()) {
                    main = compileProgram(getMainVertexShader(), TranslucentBatchShader.source(fragment),
                            getShaderName() + "主着色器/材质批量/" + key);
                    if (main != 0) TranslucentBatchShader.register(main);
                    else logger.warn("Toon 批量材质编译失败，使用兼容提交路径");
                }
                if (main == 0) main = compileProgram(getMainVertexShader(), fragment,
                        getShaderName() + "主着色器/" + key);
                if (main == 0) {
                    if (effects != null && effects.enabled()) effects.problem("compile:" + key, "效果着色器编译失败: " + effects.current().name() + "；详见客户端日志");
                    failedVariants.add(key);
                    return false;
                }
                int outline = compileProgram(getOutlineVertexShader(), outlineSource(OUTLINE_FRAGMENT_SHADER_BODY, profile),
                        getShaderName() + "描边着色器/" + key);
                if (outline == 0) {
                    TranslucentBatchShader.forget(main);
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
            outlineColorSlot = profile.colorOutputSlot();
            lightingBridge = profile.lightingSource().isEmpty() ? null : new ToonUniformBridge(profile.lightingSource());
            lightingSyncWarned = false;
            // 每个变体的 uniform 位置独立，切换后重新绑定。
            initCommonUniforms();
            initCommonAttributes();
            outlineLighting.select(outlineProgram, profile.lightingSource());
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
        return body.replace("/* MMD_FX_MATERIAL */", com.shiroha.mmdskin.render.effectpack.FxShaderSource.DISABLED)
                .replace("/* MMD_FX_COLOR */", com.shiroha.mmdskin.render.effectpack.FxEffect.IDENTITY)
                .replace("/* TOON_SURFACE_LIGHTING */", SURFACE_LIGHTING)
                .replace("/* TOON_OUTPUT_DECLARATIONS */", profile.fragmentDeclarations())
                .replace("/* TOON_OUTPUT_WRITER */", profile.fragmentWriter())
                .replace("/* TOON_LIGHTING */", profile.lightingSource().isEmpty()
                        ? "vec3 applyPackLighting(vec3 raw, vec3 n, vec3 p) { return raw; }"
                        : profile.lightingSource());
    }

    private static boolean supportsBatchProfile(String key) {
        // BSL 只在输出协议已通过 ToonOutputProfile 校验后参与批量变体。
        return key.equals(ToonOutputProfile.vanilla().id()) || key.startsWith("bsl:");
    }

    static String outlineSource(String body, ToonOutputProfile profile) {
        return body.replace("/* TOON_SURFACE_LIGHTING */", SURFACE_LIGHTING)
                .replace("/* TOON_OUTPUT_DECLARATIONS */", "layout(location = 0) out vec4 outlineColor;\n")
                .replace("/* TOON_OUTPUT_WRITER */", profile.outlineWriter())
                .replace("/* TOON_LIGHTING */", profile.lightingSource().isEmpty()
                        ? "vec3 applyPackLighting(vec3 raw, vec3 n, vec3 p) { return raw; }"
                        : profile.lightingSource());
    }

    public int getOutlineColorSlot() { return outlineColorSlot; }

    public void setOutlinePremultiplied(boolean value) {
        if (outlinePremultipliedLocation >= 0) GL46C.glUniform1i(outlinePremultipliedLocation, value ? 1 : 0);
    }

    public void setOutlineOcclusion(boolean value) {
        if (outlineOcclusionLocation >= 0) GL46C.glUniform1i(outlineOcclusionLocation, value ? 1 : 0);
    }

    public void setOutlineFollowLighting(boolean value) {
        outlineLighting.setEnabled(value);
    }

    public void setEyeEmission(float value) {
        if (eyeEmissionLocation >= 0) GL46C.glUniform1f(eyeEmissionLocation, value);
    }

    /** 设置当前材质的眼部发光遮罩模式。 */
    public void setEyeMaterialMask(float value) {
        if (eyeMaterialMaskLocation >= 0) GL46C.glUniform1f(eyeMaterialMaskLocation, value);
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
        TranslucentBatchShader.forget(programs.main());
        GL46C.glDeleteProgram(programs.main());
        GL46C.glDeleteProgram(programs.outline());
    }
    private void initCommonUniforms() {

        projMatLocation = GL46C.glGetUniformLocation(mainProgram, "ProjMat");
        modelViewMatLocation = GL46C.glGetUniformLocation(mainProgram, "ModelViewMat");
        sampler0Location = GL46C.glGetUniformLocation(mainProgram, "Sampler0");
        lightIntensityLocation = GL46C.glGetUniformLocation(mainProgram, "LightIntensity");
        toonLevelsLocation = GL46C.glGetUniformLocation(mainProgram, "ToonLevels");
        toonTransitionSoftnessLocation = GL46C.glGetUniformLocation(mainProgram, "ToonTransitionSoftness");
        shaderFlatnessLocation = GL46C.glGetUniformLocation(mainProgram, "ToonShaderFlatness");
        shaderSphereSuppressionLocation = GL46C.glGetUniformLocation(mainProgram, "ToonShaderSphereSuppression");
        rimPowerLocation = GL46C.glGetUniformLocation(mainProgram, "RimPower");
        rimIntensityLocation = GL46C.glGetUniformLocation(mainProgram, "RimIntensity");
        shadowColorLocation = GL46C.glGetUniformLocation(mainProgram, "ShadowColor");
        specularPowerLocation = GL46C.glGetUniformLocation(mainProgram, "SpecularPower");
        specularIntensityLocation = GL46C.glGetUniformLocation(mainProgram, "SpecularIntensity");
        lightDirLocation = GL46C.glGetUniformLocation(mainProgram, "LightDir");
        alphaCutoffLocation = GL46C.glGetUniformLocation(mainProgram, "AlphaCutoff");
        hairAlphaCutoffLocation = GL46C.glGetUniformLocation(mainProgram, "HairAlphaCutoff");
        disableTransparencyLocation = GL46C.glGetUniformLocation(mainProgram, "DisableTransparency");
        outlineDisableTransparencyLocation = GL46C.glGetUniformLocation(outlineProgram, "DisableTransparency");
        globalAlphaLocation = GL46C.glGetUniformLocation(mainProgram, "GlobalAlpha");
        packLightingLocation = GL46C.glGetUniformLocation(mainProgram, "UsePackLighting");
        lightmapLocation = GL46C.glGetUniformLocation(mainProgram, "ToonLightmap");
        mainOutputMatrixLocation = GL46C.glGetUniformLocation(mainProgram, "ToonViewToWorld");
        mainOutputFarLocation = GL46C.glGetUniformLocation(mainProgram, "ToonFar");
        eyeEmissionLocation = GL46C.glGetUniformLocation(mainProgram, "EyeEmission");
        genericLighting.select(mainProgram);
        eyeMaterialMaskLocation = GL46C.glGetUniformLocation(mainProgram, "EyeMaterialMask");
        outlinePremultipliedLocation = GL46C.glGetUniformLocation(outlineProgram, "Premultiplied");
        outlineOcclusionLocation = GL46C.glGetUniformLocation(outlineProgram, "OcclusionPass");

        outlineProjMatLocation = GL46C.glGetUniformLocation(outlineProgram, "ProjMat");
        outlineModelViewMatLocation = GL46C.glGetUniformLocation(outlineProgram, "ModelViewMat");
        outlineWidthLocation = GL46C.glGetUniformLocation(outlineProgram, "OutlineWidth");
        outlineColorLocation = GL46C.glGetUniformLocation(outlineProgram, "OutlineColor");
        outlineAlphaLocation = GL46C.glGetUniformLocation(outlineProgram, "OutlineAlpha");
        outlineSamplerLocation = GL46C.glGetUniformLocation(outlineProgram, "Sampler0");
        outlineAlphaCutoffLocation = GL46C.glGetUniformLocation(outlineProgram, "AlphaCutoff");
        outlineUseTextureAlphaLocation = GL46C.glGetUniformLocation(outlineProgram, "UseTextureAlpha");
    }

    private void initCommonAttributes() {

        positionLocation = GL46C.glGetAttribLocation(mainProgram, "Position");
        normalLocation = GL46C.glGetAttribLocation(mainProgram, "Normal");
        uv0Location = GL46C.glGetAttribLocation(mainProgram, "UV0");

        outlinePositionLocation = GL46C.glGetAttribLocation(outlineProgram, "Position");
        outlineNormalLocation = GL46C.glGetAttribLocation(outlineProgram, "Normal");
        outlineUvLocation = GL46C.glGetAttribLocation(outlineProgram, "UV0");
    }

    protected int compileProgram(String vertexSource, String fragmentSource, String name) {
        return ShaderCompiler.compileRenderProgram(vertexSource, fragmentSource, name);
    }

    public void useMain() {
        if (mainProgram > 0) {
            GL46C.glUseProgram(mainProgram);
            ToonConfig config = ToonConfig.getInstance();
            setAlphaCutoff(config.getAlphaCutoff());
            setHairAlphaCutoff(config.getHairAlphaCutoff());
            setDisableTransparency(ToonConfig.getInstance().isTransparencyDisabled());
            uploadOutputUniforms(mainOutputMatrixLocation, mainOutputFarLocation);
        }
    }

    public void useOutline() {
        if (outlineProgram > 0) {
            GL46C.glUseProgram(outlineProgram);
            if (outlineDisableTransparencyLocation >= 0) GL46C.glUniform1i(outlineDisableTransparencyLocation,
                    ToonConfig.getInstance().isTransparencyDisabled() ? 1 : 0);
            outlineLighting.upload();
            setOutlinePremultiplied(false);
            if (outlineSamplerLocation >= 0) GL46C.glUniform1i(outlineSamplerLocation, 0);
        }
    }

    public boolean setEnvironmentLighting(int originalProgram, int blockLight, int skyLight) {
        return setEnvironmentLighting(originalProgram, null, blockLight, skyLight);
    }

    public boolean setEnvironmentLighting(int originalProgram, Object shaderIdentity, int blockLight, int skyLight) {
        // 5.x 的主体反射法线在世界空间；描边通过独立颜色 pass 合成，不写材质缓冲。
        if (mainOutputMatrixLocation >= 0 || mainOutputFarLocation >= 0) {
            readOutputUniforms(originalProgram);
            uploadOutputUniforms(mainOutputMatrixLocation, mainOutputFarLocation);
        }
        boolean copied = lightingBridge != null && lightingBridge.copy(originalProgram, mainProgram, shaderIdentity);
        genericLighting.upload(copied ? 0 : originalProgram, shaderIdentity);
        if (!copied && lightingBridge != null && originalProgram > 0 && packLightingLocation >= 0 && !lightingSyncWarned) {
            lightingSyncWarned = true;
            logger.warn("Toon 环境光 uniform 同步失败，保留 Toon 并使用通用环境光: profile={}, sourceProgram={}, reason={}",
                    activeProfile, originalProgram, lightingBridge.failureReason());
        }
        if (packLightingLocation >= 0) GL46C.glUniform1i(packLightingLocation, copied ? 1 : 0);
        // 与实体 UV2 的 (level * 16 + 8) / 256 约定一致。
        if (lightmapLocation >= 0) GL46C.glUniform2f(lightmapLocation,
                (blockLight * 16 + 8) / 256.0f, (skyLight * 16 + 8) / 256.0f);
        outlineLighting.syncEnvironment(originalProgram, shaderIdentity, blockLight, skyLight, copied, mainProgram);
        return copied;
    }

    private void readOutputUniforms(int source) {
        outputViewToWorld.identity();
        outputFar = 1.0f;
        if (source <= 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            int inverse = GL46C.glGetUniformLocation(source, "gbufferModelViewInverse");
            int view = GL46C.glGetUniformLocation(source, "gbufferModelView");
            if (inverse >= 0) {
                GL46C.glGetUniformfv(source, inverse, matrix);
                outputViewToWorld.set(matrix);
            } else if (view >= 0) {
                GL46C.glGetUniformfv(source, view, matrix);
                outputViewToWorld.set(matrix).invert();
            }
            int far = GL46C.glGetUniformLocation(source, "far");
            if (far >= 0) outputFar = Math.max(GL46C.glGetUniformf(source, far), 1.0f);
        }
    }

    private void uploadOutputUniforms(int matrix, int far) {
        if (matrix >= 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GL46C.glUniformMatrix4fv(matrix, false, outputViewToWorld.get(stack.mallocFloat(16)));
            }
        }
        if (far >= 0) GL46C.glUniform1f(far, outputFar);
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
        outlineLighting.intensity = intensity;
        if (lightIntensityLocation >= 0) {
            GL46C.glUniform1f(lightIntensityLocation, intensity);
        }
    }

    public void setToonLevels(int levels) {
        outlineLighting.levels = Math.max(2, Math.min(5, levels));
        if (toonLevelsLocation >= 0) {
            GL46C.glUniform1i(toonLevelsLocation, Math.max(2, Math.min(5, levels)));
        }
    }

    /** 0 保留原抗锯齿宽度，最高支持 200% 过渡柔和度。 */
    public void setTransitionSoftness(float softness) {
        outlineLighting.softness = Float.isFinite(softness) ? Math.max(0, Math.min(2, softness)) : 1.2f;
        if (toonTransitionSoftnessLocation >= 0) {
            float value = Float.isFinite(softness) ? Math.max(0, Math.min(2, softness)) : 1.2f;
            GL46C.glUniform1f(toonTransitionSoftnessLocation, value);
        }
    }

    public void setShaderStyle(float flatness, float sphereSuppression) {
        outlineLighting.flatness = finiteUnit(flatness);
        GL46C.glUniform1f(shaderFlatnessLocation, outlineLighting.flatness);
        GL46C.glUniform1f(shaderSphereSuppressionLocation, finiteUnit(sphereSuppression));
    }

    private static float finiteUnit(float value) {
        return Float.isFinite(value) ? Math.max(0.0f, Math.min(1.0f, value)) : 0.0f;
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
        outlineLighting.shadowR = r; outlineLighting.shadowG = g; outlineLighting.shadowB = b;
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
        outlineLighting.lightX = x; outlineLighting.lightY = y; outlineLighting.lightZ = z;
        if (lightDirLocation >= 0) {
            GL46C.glUniform3f(lightDirLocation, x, y, z);
        }
    }

    public void setAlphaCutoff(float cutoff) {
        if (alphaCutoffLocation >= 0) {
            GL46C.glUniform1f(alphaCutoffLocation, cutoff);
        }
    }

    public void setHairAlphaCutoff(float cutoff) {
        if (hairAlphaCutoffLocation >= 0) GL46C.glUniform1f(hairAlphaCutoffLocation, cutoff);
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

    public void setOutlineAlphaCutoff(float cutoff) {
        if (outlineAlphaCutoffLocation >= 0) GL46C.glUniform1f(outlineAlphaCutoffLocation, cutoff);
    }

    public void setOutlineUseTextureAlpha(boolean enabled) {
        if (outlineUseTextureAlphaLocation >= 0) GL46C.glUniform1i(outlineUseTextureAlphaLocation, enabled ? 1 : 0);
    }

    public void setDisableTransparency(boolean disabled) {
        if (disableTransparencyLocation >= 0) GL46C.glUniform1i(disableTransparencyLocation, disabled ? 1 : 0);
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
    public int getOutlineUvLocation() { return outlineUvLocation; }

    public boolean isInitialized() { return initialized; }

    public void cleanup() {
        com.shiroha.mmdskin.render.material.ToonTransparencyLayers.cleanup();
        com.shiroha.mmdskin.render.outline.OutlineCoverage.release();
        com.shiroha.mmdskin.ui.paperdoll.PaperDollRenderer.releaseCache();
        variants.values().forEach(ToonShaderBase::deletePrograms);
        variants.clear();
        failedVariants.clear();
        mainProgram = 0;
        outlineProgram = 0;
        activeProfile = null;
        initialized = false;
    }
}
