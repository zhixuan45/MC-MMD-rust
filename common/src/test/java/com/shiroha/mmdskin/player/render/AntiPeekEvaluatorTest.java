package com.shiroha.mmdskin.player.render;

import com.shiroha.mmdskin.render.effect.PelvisVeilMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AntiPeekEvaluatorTest {
    @Test void antiPeekAutoEnablesSwimmingAndElytraPelvisVeil() {
        var swimming = AntiPeekEvaluator.specialState(false, true, false, false, true, false, false);
        assertFalse(swimming.fullyHidden());
        assertEquals(1.0f, swimming.alpha());
        assertEquals(PelvisVeilMode.SWIMMING_WATER, swimming.veilMode());

        var elytra = AntiPeekEvaluator.specialState(false, false, true, false, true, false, false);
        assertFalse(elytra.fullyHidden());
        assertEquals(1.0f, elytra.alpha());
        assertEquals(PelvisVeilMode.ELYTRA_WIND, elytra.veilMode());

        assertSame(AntiPeekEvaluator.Result.NORMAL,
                AntiPeekEvaluator.specialState(false, false, false, false, true, false, false));
    }

    @Test void standaloneSwimmingAndElytraTogglesWorkIndependently() {
        var swimOnly = AntiPeekEvaluator.specialState(false, true, false, false, false, true, false);
        assertEquals(PelvisVeilMode.SWIMMING_WATER, swimOnly.veilMode());
        assertSame(AntiPeekEvaluator.Result.NORMAL,
                AntiPeekEvaluator.specialState(false, false, true, false, false, true, false));

        var elytraOnly = AntiPeekEvaluator.specialState(false, false, true, false, false, false, true);
        assertEquals(PelvisVeilMode.ELYTRA_WIND, elytraOnly.veilMode());
        assertSame(AntiPeekEvaluator.Result.NORMAL,
                AntiPeekEvaluator.specialState(false, true, false, false, false, false, true));

        assertSame(AntiPeekEvaluator.Result.NORMAL,
                AntiPeekEvaluator.specialState(false, true, false, false, false, false, false));
        assertSame(AntiPeekEvaluator.Result.NORMAL,
                AntiPeekEvaluator.specialState(false, false, true, false, false, false, false));
    }

    @Test void elytraTakesPrecedenceWhenBothStatesOverlap() {
        var result = AntiPeekEvaluator.specialState(false, true, true, false, false, true, true);
        assertEquals(PelvisVeilMode.ELYTRA_WIND, result.veilMode());
    }

    @Test void firstPersonSpecialStatesHideSwimmingAndPassengerAndSkipVeil() {
        for (boolean enabled : new boolean[]{false, true}) {
            assertSame(AntiPeekEvaluator.Result.HIDDEN,
                    AntiPeekEvaluator.specialState(true, true, false, false, enabled, enabled, enabled));
            assertSame(AntiPeekEvaluator.Result.HIDDEN,
                    AntiPeekEvaluator.specialState(true, false, false, true, enabled, enabled, enabled));
            assertSame(AntiPeekEvaluator.Result.NORMAL,
                    AntiPeekEvaluator.specialState(true, false, true, false, enabled, enabled, enabled));
        }
    }

    @Test void nonWorldAndOtherPlayersBypassVisibilityEffects() {
        assertSame(AntiPeekEvaluator.Result.NORMAL, AntiPeekEvaluator.evaluate(null, false, false, false, false));
        assertSame(AntiPeekEvaluator.Result.NORMAL, AntiPeekEvaluator.evaluate(null, true, false, true, false));
        assertSame(AntiPeekEvaluator.Result.NORMAL, AntiPeekEvaluator.evaluate(null, true, false, false, true));
    }
}
