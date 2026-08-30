package com.shiroha.mmdskin.config;

/**
 * MMD 皮肤配置抽象基类。
 */
public abstract class AbstractMmdSkinConfig implements ConfigManager.IConfigProvider {
    protected ConfigData data;

    protected AbstractMmdSkinConfig(ConfigData data) {
        this.data = data;
    }

    @Override public boolean isOpenGLLightingEnabled() { return data.openGLEnableLighting; }
    @Override public int getModelPoolMaxCount() { return data.modelPoolMaxCount; }
    @Override public boolean isMMDShaderEnabled() { return data.mmdShaderEnabled; }

    @Override public boolean isGpuSkinningEnabled() { return data.gpuSkinningEnabled; }
    @Override public boolean isGpuMorphEnabled() { return data.gpuMorphEnabled; }
    @Override public int getMaxBones() { return data.maxBones; }
    @Override public boolean isPerformanceProfilingEnabled() { return data.performanceProfilingEnabled; }
    @Override public int getPerformanceLogIntervalSeconds() { return data.performanceLogIntervalSeconds; }
    @Override public int getMaxVisibleModelsPerFrame() { return data.maxVisibleModelsPerFrame; }
    @Override public float getAnimationLodMediumDistance() { return data.animationLodMediumDistance; }
    @Override public float getAnimationLodFarDistance() { return data.animationLodFarDistance; }
    @Override public int getAnimationLodMediumUpdateInterval() { return data.animationLodMediumUpdateInterval; }
    @Override public int getAnimationLodFarUpdateInterval() { return data.animationLodFarUpdateInterval; }

    @Override public boolean isToonRenderingEnabled() { return data.toonRenderingEnabled; }
    @Override public int getToonLevels() { return data.toonLevels; }
    @Override public float getToonRimPower() { return data.toonRimPower; }
    @Override public float getToonRimIntensity() { return data.toonRimIntensity; }
    @Override public float getToonShadowR() { return data.toonShadowR; }
    @Override public float getToonShadowG() { return data.toonShadowG; }
    @Override public float getToonShadowB() { return data.toonShadowB; }
    @Override public float getToonSpecularPower() { return data.toonSpecularPower; }
    @Override public float getToonSpecularIntensity() { return data.toonSpecularIntensity; }
    @Override public boolean isToonOutlineEnabled() { return data.toonOutlineEnabled; }
    @Override public float getToonOutlineWidth() { return data.toonOutlineWidth; }
    @Override public float getToonOutlineR() { return data.toonOutlineR; }
    @Override public float getToonOutlineG() { return data.toonOutlineG; }
    @Override public float getToonOutlineB() { return data.toonOutlineB; }

    @Override public float getPhysicsGravityY() { return data.physicsGravityY; }
    @Override public float getPhysicsFps() { return data.physicsFps; }
    @Override public int getPhysicsMaxSubstepCount() { return data.physicsMaxSubstepCount; }
    @Override public float getPhysicsInertiaStrength() { return data.physicsInertiaStrength; }
    @Override public float getPhysicsMaxLinearVelocity() { return data.physicsMaxLinearVelocity; }
    @Override public float getPhysicsMaxAngularVelocity() { return data.physicsMaxAngularVelocity; }
    @Override public boolean isPhysicsJointsEnabled() { return data.physicsJointsEnabled; }
    @Override public boolean isPhysicsKinematicFilter() { return data.physicsKinematicFilter; }
    @Override public boolean isPhysicsCollisionEnabled() { return data.physicsCollisionEnabled; }
    @Override public PhysicsCollisionStabilityMode getPhysicsCollisionStabilityMode() {
        return data.physicsCollisionStabilityMode;
    }
    @Override public boolean isPhysicsDebugLog() { return data.physicsDebugLog; }
    @Override public int getMaxPhysicsModelsPerFrame() { return data.maxPhysicsModelsPerFrame; }
    @Override public float getPhysicsLodMaxDistance() { return data.physicsLodMaxDistance; }

    @Override public boolean isFirstPersonModelEnabled() { return data.firstPersonModelEnabled; }
    @Override public float getFirstPersonCameraForwardOffset() { return data.firstPersonCameraForwardOffset; }
    @Override public float getFirstPersonCameraVerticalOffset() { return data.firstPersonCameraVerticalOffset; }
    @Override public boolean isAntiPeekModeEnabled() { return data.antiPeekModeEnabled; }
    @Override public float getAntiPeekThresholdAngle() { return data.antiPeekThresholdAngle; }
    @Override public float getAntiPeekHideAngle() { return data.antiPeekHideAngle; }
    @Override public boolean isPaperDollEnabled() { return data.paperDollEnabled; }
    @Override public PaperDollPosition getPaperDollPosition() { return data.paperDollPosition; }
    @Override public int getPaperDollOffsetX() { return data.paperDollOffsetX; }
    @Override public int getPaperDollOffsetY() { return data.paperDollOffsetY; }
    @Override public float getPaperDollScale() { return data.paperDollScale; }
    @Override public PaperDollDisplayMode getPaperDollDisplayMode() { return data.paperDollDisplayMode; }
    @Override public PaperDollRotationMode getPaperDollRotationMode() { return data.paperDollRotationMode; }
    @Override public boolean isPaperDollShowInScreens() { return data.paperDollShowInScreens; }
    @Override public boolean isDebugHudEnabled() { return data.debugHudEnabled; }
    @Override public int getTextureCacheBudgetMB() { return data.textureCacheBudgetMB; }
    @Override public boolean hasMobModelReplacements() {
        return data != null && data.mobModelReplacements != null && !data.mobModelReplacements.isEmpty();
    }
    @Override public String getMobModelReplacement(String entityTypeId) {
        return data.mobModelReplacements.getOrDefault(entityTypeId, "");
    }

    @Override public boolean isVREnabled() { return data.vrEnabled; }
    @Override public float getVRArmIKStrength() { return data.vrArmIKStrength; }
}
