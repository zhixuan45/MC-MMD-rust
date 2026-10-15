package com.shiroha.mmdskin.render.policy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnimationUpdateScheduleTest {
    @Test void disabledScheduleUsesDistanceThresholdsAndRelativeCadence() {
        var schedule = new AnimationUpdateSchedule();
        assertTrue(schedule.due(1, 1, 23 * 23, false, 24, 48, 2, 4, false));
        assertFalse(schedule.due(1, 2, 25 * 25, false, 24, 48, 2, 4, false));
        assertTrue(schedule.due(1, 3, 25 * 25, false, 24, 48, 2, 4, false));
        assertTrue(schedule.due(1, 3, 25 * 25, false, 24, 48, 2, 4, false));
        assertFalse(schedule.due(1, 4, 80 * 80, false, 24, 48, 2, 4, false));
        assertTrue(schedule.due(1, 5, 80 * 80, true, 24, 48, 2, 4, false));
    }

    private static boolean due(AnimationUpdateSchedule schedule, long handle, long frame, double distance, boolean local) {
        return schedule.due(handle, frame, distance * distance, local, 24, 48, 2, 4);
    }

    @Test void stableDistantInstancesAreSpreadAcrossFramesWithoutReducingTheirRate() {
        int[] counts = new int[4];
        for (long handle = 1; handle <= 64; handle++) {
            var schedule = new AnimationUpdateSchedule();
            assertTrue(due(schedule, handle, 1, 80, false));
            int updates = 0;
            for (int frame = 2; frame <= 9; frame++) {
                if (due(schedule, handle, frame, 80, false)) {
                    updates++;
                    counts[frame % 4]++;
                    assertTrue(due(schedule, handle, frame, 80, false), "same-frame passes must reach the evaluation cache");
                }
            }
            assertEquals(2, updates);
        }
        for (int count : counts) assertTrue(count > 0 && count < 128, "work must occupy every phase");
    }

    @Test void nearAndLocalModelsAlwaysUpdate() {
        var near = new AnimationUpdateSchedule();
        var local = new AnimationUpdateSchedule();
        for (int frame = 1; frame <= 12; frame++) {
            assertTrue(due(near, 7, frame, 2, false));
            assertTrue(due(local, 8, frame, 100, true));
        }
    }

    @Test void distanceHysteresisKeepsBoundaryNoiseInOneBandAndTransitionsUpdateImmediately() {
        var schedule = new AnimationUpdateSchedule();
        assertTrue(due(schedule, 1, 1, 23, false));
        for (int frame = 2; frame <= 8; frame++) assertTrue(due(schedule, 1, frame, 25, false));
        assertTrue(due(schedule, 1, 9, 27, false));
        int updates = 0;
        for (int frame = 10; frame <= 17; frame++) if (due(schedule, 1, frame, 23, false)) updates++;
        assertEquals(4, updates, "23 is inside the medium band's hysteresis");
        assertTrue(due(schedule, 1, 18, 21, false));
        assertTrue(due(schedule, 1, 19, 23, false));
    }
}
