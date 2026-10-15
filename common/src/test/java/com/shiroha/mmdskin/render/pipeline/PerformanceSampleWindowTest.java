package com.shiroha.mmdskin.render.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerformanceSampleWindowTest {
    @Test
    void computesMedianAndNearestRankPercentileFromBoundedWindow() {
        PerformanceSampleWindow window = new PerformanceSampleWindow(4);
        window.record(10);
        window.record(20);
        window.record(30);
        window.record(40);

        PerformanceSampleWindow.Snapshot snapshot = window.snapshot();
        assertEquals(25, snapshot.averageNanos());
        assertEquals(20, snapshot.medianNanos());
        assertEquals(40, snapshot.p95Nanos());

        window.record(50);
        assertEquals(4, window.snapshot().count());
        assertEquals(30, window.snapshot().medianNanos());
        window.clear();
        assertEquals(0, window.snapshot().count());
    }
}
