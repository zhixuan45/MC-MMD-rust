package com.shiroha.mmdskin.render.pipeline.transparency;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldTransparencyFallbackTest {
    @Test
    void rejectedModelFallsBackEvenWithActiveGlobalQueue() {
        assertTrue(WorldTransparencyPipeline.shouldUseImmediateFallback(true, true, false));
        assertTrue(WorldTransparencyPipeline.shouldUseImmediateFallback(true, false, false));
    }

    @Test
    void acceptedModelsAndNonWorldViewsDoNotFallback() {
        assertFalse(WorldTransparencyPipeline.shouldUseImmediateFallback(true, true, true));
        assertFalse(WorldTransparencyPipeline.shouldUseImmediateFallback(false, true, false));
        assertFalse(WorldTransparencyPipeline.shouldUseImmediateFallback(false, false, false));
        assertTrue(WorldTransparencyPipeline.shouldUseImmediateFallback(true, false, true));
    }

    @Test
    void jvmFlagsOverrideLiveSettingsOnlyWhenPresent() {
        assertTrue(WorldTransparencyPipeline.configuredOrOverride(null, true));
        assertFalse(WorldTransparencyPipeline.configuredOrOverride(null, false));
        assertFalse(WorldTransparencyPipeline.configuredOrOverride("false", true));
        assertTrue(WorldTransparencyPipeline.configuredOrOverride("true", false));
    }
}
