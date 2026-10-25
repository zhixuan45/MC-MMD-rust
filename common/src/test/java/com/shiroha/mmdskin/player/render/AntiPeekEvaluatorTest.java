package com.shiroha.mmdskin.player.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AntiPeekEvaluatorTest {
    @Test void swimmingKeepsBodyVisibleAndUsesLocalVeil() {
        var result = AntiPeekEvaluator.specialState(false, true, false, true);
        assertFalse(result.fullyHidden());
        assertEquals(1.0f, result.alpha());
        assertTrue(result.swimmingVeil());
        assertSame(AntiPeekEvaluator.Result.NORMAL, AntiPeekEvaluator.specialState(false, true, false, false));
        assertSame(AntiPeekEvaluator.Result.NORMAL, AntiPeekEvaluator.specialState(false, false, false, true));
    }

    @Test void firstPersonSpecialStatesStillHideTheModel() {
        for (boolean enabled : new boolean[]{false, true}) {
            assertSame(AntiPeekEvaluator.Result.HIDDEN, AntiPeekEvaluator.specialState(true, true, false, enabled));
            assertSame(AntiPeekEvaluator.Result.HIDDEN, AntiPeekEvaluator.specialState(true, false, true, enabled));
        }
    }

    @Test void nonWorldAndOtherPlayersBypassVisibilityEffects() {
        assertSame(AntiPeekEvaluator.Result.NORMAL, AntiPeekEvaluator.evaluate(null, false, false, false, false));
        assertSame(AntiPeekEvaluator.Result.NORMAL, AntiPeekEvaluator.evaluate(null, true, false, true, false));
        assertSame(AntiPeekEvaluator.Result.NORMAL, AntiPeekEvaluator.evaluate(null, true, false, false, true));
    }
}
