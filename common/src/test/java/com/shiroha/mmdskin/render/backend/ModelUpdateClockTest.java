package com.shiroha.mmdskin.render.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelUpdateClockTest {
    @Test
    void preservesContinuousSubMillisecondSteps() {
        ModelUpdateClock clock = new ModelUpdateClock();
        long now = 8_000_000_000L;

        assertEquals(0.0f, clock.sampleDeltaSeconds(now));
        assertEquals(0.00025f, clock.sampleDeltaSeconds(now += 250_000L), 0.000001f);
        assertEquals(0.00025f, clock.sampleDeltaSeconds(now += 250_000L), 0.000001f);
        assertEquals(0.00025f, clock.sampleDeltaSeconds(now += 250_000L), 0.000001f);
    }

    @Test
    void repeatedSamplesAreZeroWithoutDiscardingLaterProgress() {
        ModelUpdateClock clock = new ModelUpdateClock();
        long now = 42L;

        assertEquals(0.0f, clock.sampleDeltaSeconds(now));
        assertEquals(0.0f, clock.sampleDeltaSeconds(now));
        assertEquals(0.0002f, clock.sampleDeltaSeconds(now += 200_000L), 0.000001f);
        assertEquals(0.0f, clock.sampleDeltaSeconds(now));
        assertEquals(0.0002f, clock.sampleDeltaSeconds(now += 200_000L), 0.000001f);
    }

    @Test
    void acceptsNegativeNanoTimeOrigin() {
        ModelUpdateClock clock = new ModelUpdateClock();
        long now = -9_000_000_000L;

        assertEquals(0.0f, clock.sampleDeltaSeconds(now));
        assertEquals(0.001f, clock.sampleDeltaSeconds(now + 1_000_000L), 0.000001f);
    }

    @Test
    void clampsLongStepsWithoutCarryingExcessTime() {
        ModelUpdateClock clock = new ModelUpdateClock();
        long now = 100L;

        assertEquals(0.0f, clock.sampleDeltaSeconds(now));
        assertEquals(0.25f, clock.sampleDeltaSeconds(now += 1_000_000_000L));
        assertEquals(0.01f, clock.sampleDeltaSeconds(now += 10_000_000L), 0.000001f);
    }
}
