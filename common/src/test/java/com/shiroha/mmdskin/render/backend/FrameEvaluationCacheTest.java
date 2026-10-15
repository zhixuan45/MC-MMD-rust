package com.shiroha.mmdskin.render.backend;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameEvaluationCacheTest {
    @Test void onlyAnEvaluatedFrameWithTheSameInputsCanBeReused() {
        var cache = new FrameEvaluationCache();
        assertFalse(cache.matches(1, 0));
        cache.capture(1, 7);
        assertTrue(cache.matches(1, 7));
        assertFalse(cache.matches(2, 7));
        assertFalse(cache.matches(1, 8));
        cache.capture(2, 8);
        assertFalse(cache.matches(1, 7));
        assertTrue(cache.matches(2, 8));
    }

    @Test void unknownPortsAndUnstartedFramesNeverReuse() {
        var cache = new FrameEvaluationCache();
        cache.capture(1, Long.MIN_VALUE);
        assertFalse(cache.matches(1, Long.MIN_VALUE));
        cache.capture(0, 3);
        assertFalse(cache.matches(0, 3));
    }
}
