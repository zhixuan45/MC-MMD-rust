package com.shiroha.mmdskin.render.backend;

import com.shiroha.mmdskin.render.pipeline.RenderPerformanceProfiler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameEvaluationCacheTest {
    @Test void onlyAnEvaluatedFrameWithTheSameInputsCanBeReused() {
        var cache = new FrameEvaluationCache();
        assertFalse(cache.matches(1, 0));
        cache.capture(1, 7, RenderPerformanceProfiler.Scene.WORLD);
        assertTrue(cache.matches(1, 7));
        assertFalse(cache.matches(2, 7));
        assertFalse(cache.matches(1, 8));
        cache.capture(2, 8, RenderPerformanceProfiler.Scene.WORLD);
        assertFalse(cache.matches(1, 7));
        assertTrue(cache.matches(2, 8));
    }

    @Test void unknownPortsAndUnstartedFramesNeverReuse() {
        var cache = new FrameEvaluationCache();
        cache.capture(1, Long.MIN_VALUE, RenderPerformanceProfiler.Scene.WORLD);
        assertFalse(cache.matches(1, Long.MIN_VALUE));
        cache.capture(0, 3, RenderPerformanceProfiler.Scene.WORLD);
        assertFalse(cache.matches(0, 3));
    }

    @Test void onlyWorldShadowAndPreparedFirstPersonCanFeedAWorldPass() {
        var cache = new FrameEvaluationCache();
        cache.capture(4, 12, RenderPerformanceProfiler.Scene.SHADOW);
        assertTrue(cache.matchesWorld(4, 12, false, false));
        cache.capture(4, 12, RenderPerformanceProfiler.Scene.WORLD);
        assertTrue(cache.matchesWorld(4, 12, false, false));
        cache.capture(4, 12, RenderPerformanceProfiler.Scene.FIRST_PERSON);
        assertFalse(cache.matchesWorld(4, 12, false, false));
        assertTrue(cache.matchesWorld(4, 12, true, false));
        assertFalse(cache.matchesWorld(4, 12, true, true));
        cache.capture(4, 12, RenderPerformanceProfiler.Scene.PAPERDOLL);
        assertFalse(cache.matchesWorld(4, 12, false, false));
        cache.capture(4, 12, RenderPerformanceProfiler.Scene.INVENTORY);
        assertFalse(cache.matchesWorld(4, 12, false, false));
    }
}
