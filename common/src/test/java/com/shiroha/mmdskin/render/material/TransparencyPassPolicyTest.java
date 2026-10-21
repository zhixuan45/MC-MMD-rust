package com.shiroha.mmdskin.render.material;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransparencyPassPolicyTest {
    @Test void plainShadersAlwaysUseAtMostTwoPasses() {
        assertEquals(2, TransparencyPassPolicy.passCount(false, false, false, true, false, false, false));
        assertEquals(2, TransparencyPassPolicy.passCount(false, false, false, false, false, false, false));
        assertEquals(2, TransparencyPassPolicy.passCount(false, false, false, false, false, false, true));
        assertEquals(1, TransparencyPassPolicy.passCount(false, true, false, false, false, false, false));
        assertEquals(1, TransparencyPassPolicy.passCount(true, false, false, false, false, false, false));
    }
    @Test void toonPathsKeepTheirPreviousDepthPolicies() {
        assertEquals(3, TransparencyPassPolicy.passCount(false, false, false, true, false, true, true));
        assertEquals(3, TransparencyPassPolicy.passCount(false, false, false, false, false, true, false));
        assertEquals(2, TransparencyPassPolicy.passCount(false, false, false, false, false, true, true));
        assertEquals(2, TransparencyPassPolicy.passCount(false, false, true, false, false, true, false));
        assertEquals(2, TransparencyPassPolicy.passCount(false, false, false, false, true, true, false));
    }
    @Test void gpuReadbackSortingIsExplicitlyOptInForPlainShaders() {
        assertFalse(TransparencyPassPolicy.useSortedSubmission(false, false, false));
        assertTrue(TransparencyPassPolicy.useSortedSubmission(false, false, true));
        assertTrue(TransparencyPassPolicy.useSortedSubmission(false, true, false));
        assertTrue(TransparencyPassPolicy.useSortedSubmission(true, false, false));
    }
}
