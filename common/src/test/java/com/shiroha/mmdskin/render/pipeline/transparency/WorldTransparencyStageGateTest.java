package com.shiroha.mmdskin.render.pipeline.transparency;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldTransparencyStageGateTest {
    @Test void neverRefreshesOpaqueDepthAfterWaterOrOutsideTheFrame() {
        var gate = new WorldTransparencyStageGate();
        assertFalse(gate.beforeTranslucent());
        gate.beginFrame();
        assertTrue(gate.beforeTranslucent());
        gate.afterTranslucent();
        assertFalse(gate.beforeTranslucent());
        gate.endFrame();
        assertFalse(gate.beforeTranslucent());
    }

    @Test void onlyDefersAfterARealPublicStageEvent() {
        var gate = new WorldTransparencyStageGate();
        assertFalse(gate.beginFrame());
        assertTrue(gate.afterTranslucent());
        assertFalse(gate.afterTranslucent());
        assertFalse(gate.endFrame());
        assertTrue(gate.beginFrame());
        assertTrue(gate.afterTranslucent());
        assertFalse(gate.endFrame());
    }
    @Test void fallsBackWhenTheStageDisappears() {
        var gate = new WorldTransparencyStageGate();
        assertFalse(gate.beginFrame());
        assertTrue(gate.afterTranslucent());
        gate.endFrame();
        assertTrue(gate.beginFrame());
        assertTrue(gate.endFrame());
        assertFalse(gate.beginFrame());
        assertTrue(gate.afterTranslucent());
        gate.endFrame();
        assertTrue(gate.beginFrame());
    }
    @Test void ignoresOutOfFrameCallbacks() {
        var gate = new WorldTransparencyStageGate();
        assertFalse(gate.afterTranslucent());
        assertFalse(gate.endFrame());
        assertFalse(gate.beginFrame());
        assertFalse(gate.endFrame());
        assertFalse(gate.beginFrame());
    }
}
