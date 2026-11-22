package com.shiroha.mmdskin.render.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EntityLocomotionInputTest {
    @Test
    void acceptsSingleHalfStepsInEitherDirection() {
        assertTrue(EntityLocomotionInput.isContinuousMotion(0.17, 0.5, 0.17));
        assertTrue(EntityLocomotionInput.isContinuousMotion(-0.22, -0.5, 0.0));
    }

    @Test
    void ignoresWarpAndInvalidMovementBeforeScanningTerrain() {
        assertFalse(EntityLocomotionInput.isContinuousMotion(0.7, 0.0, 0.7));
        assertFalse(EntityLocomotionInput.isContinuousMotion(0.03, 10.0, 0.02));
        assertFalse(EntityLocomotionInput.isContinuousMotion(0.0, Double.NaN, 0.0));
    }
}
