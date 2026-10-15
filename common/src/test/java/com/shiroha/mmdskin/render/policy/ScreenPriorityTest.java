package com.shiroha.mmdskin.render.policy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenPriorityTest {
    @Test void visibleAndLargeModelsWinEqualDistanceBudgets() {
        double center = ScreenPriority.score(100, 0, 0, 10, 2, 1, 1.8, false);
        assertTrue(center < ScreenPriority.score(100, 30, 0, 10, 2, 1, 1.8, false));
        assertTrue(center < ScreenPriority.score(100, 0, 0, -10, 2, 1, 1.8, false));
        assertTrue(center > ScreenPriority.score(100, 0, 0, 10, 4, 1, 1.8, false));
    }
    @Test void edgeOverlapAndPreviousSelectionRemainStable() {
        assertEquals(ScreenPriority.score(100, 0, 0, 10, 2, 1, 1, false),
                ScreenPriority.score(100, 11, 0, 10, 2, 1, 1, false));
        assertEquals(ScreenPriority.score(100, 0, 0, 10, 2, 1, 1, false) * .85,
                ScreenPriority.score(100, 0, 0, 10, 2, 1, 1, true));
    }
    @Test void nearPlaneAndBadCameraNeverProduceInvalidSortKeys() {
        assertTrue(Double.isFinite(ScreenPriority.score(0, 0, 0, 0, 2, 1, 1, false)));
        assertTrue(Double.isFinite(ScreenPriority.score(100, 0, 0, 0, 2, Double.NaN, 1, false)));
        assertEquals(Double.MAX_VALUE, ScreenPriority.score(Double.NaN, 0, 0, 0, 2, 1, 1, false));
    }
}
