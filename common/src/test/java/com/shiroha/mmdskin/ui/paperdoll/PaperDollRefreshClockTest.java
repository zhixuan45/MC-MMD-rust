package com.shiroha.mmdskin.ui.paperdoll;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperDollRefreshClockTest {
    @Test void elapsedTimeAndSettingsControlRefresh() {
        var clock = new PaperDollRefreshClock();
        assertTrue(clock.due("a", 30, 1_000_000_000L));
        clock.rendered("a", 30, 1_000_000_000L);
        assertFalse(clock.due("a", 30, 1_020_000_000L));
        assertTrue(clock.due("a", 30, 1_034_000_000L));
        assertTrue(clock.due("b", 30, 1_001_000_000L));
        assertTrue(clock.due("a", 60, 1_001_000_000L));
        assertTrue(clock.due("a", 30, 999_000_000L));
        clock.invalidate();
        assertTrue(clock.due("a", 30, 1_001_000_000L));
    }
}
