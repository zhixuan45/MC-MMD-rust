package com.shiroha.mmdskin.render.shader;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToonRenderStrategyTest {
    @Test void autoKeepsVerifiedIntegrationAndAttemptsUnknownOutput() {
        assertFalse(ToonRenderStrategy.AUTO.attemptsIndependent(true));
        assertTrue(ToonRenderStrategy.AUTO.attemptsIndependent(false));
    }

    @Test void preferenceDoesNotEncodeCapabilities() {
        assertTrue(ToonRenderStrategy.TOON_PRIORITY.attemptsIndependent(true));
        assertTrue(ToonRenderStrategy.TOON_PRIORITY.attemptsIndependent(false));
        assertFalse(ToonRenderStrategy.SHADER_INTEGRATED.attemptsIndependent(true));
        assertFalse(ToonRenderStrategy.SHADER_INTEGRATED.attemptsIndependent(false));
    }
}
