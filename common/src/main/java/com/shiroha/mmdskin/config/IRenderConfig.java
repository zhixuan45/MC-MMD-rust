package com.shiroha.mmdskin.config;

/**
 * 渲染相关配置子接口
 */

public interface IRenderConfig {
    boolean isOpenGLLightingEnabled();
    int getModelPoolMaxCount();
    boolean isMMDShaderEnabled();

    default boolean isGpuSkinningEnabled() { return false; }

    default boolean isGpuMorphEnabled() { return false; }

    default int getMaxBones() { return 2048; }

    default boolean isPerformanceProfilingEnabled() { return false; }

    default int getPerformanceLogIntervalSeconds() { return 5; }

    default int getMaxVisibleModelsPerFrame() { return 10; }

    default float getAnimationLodMediumDistance() { return 24.0f; }

    default float getAnimationLodFarDistance() { return 48.0f; }

    default int getAnimationLodMediumUpdateInterval() { return 2; }

    default int getAnimationLodFarUpdateInterval() { return 4; }

    default boolean isFirstPersonModelEnabled() { return false; }

    default float getFirstPersonCameraForwardOffset() { return 0.0f; }

    default float getFirstPersonCameraVerticalOffset() { return 0.0f; }

    default boolean isDebugHudEnabled() { return false; }

    default int getTextureCacheBudgetMB() { return 256; }

    default boolean isAntiPeekModeEnabled() { return false; }

    default float getAntiPeekThresholdAngle() { return 25.0f; }

    default float getAntiPeekHideAngle() { return 10.0f; }

    default boolean isPaperDollEnabled() { return true; }

    default PaperDollPosition getPaperDollPosition() { return PaperDollPosition.TOP_LEFT; }

    default int getPaperDollOffsetX() { return 20; }

    default int getPaperDollOffsetY() { return 20; }

    default float getPaperDollScale() { return 30.0f; }

    default PaperDollDisplayMode getPaperDollDisplayMode() { return PaperDollDisplayMode.ALWAYS; }

    default PaperDollRotationMode getPaperDollRotationMode() { return PaperDollRotationMode.FIXED; }

    default boolean isPaperDollShowInScreens() { return true; }
}
