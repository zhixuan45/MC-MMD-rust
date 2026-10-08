package com.shiroha.mmdskin.ui.wheel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelTextGeometryTest {
    @Test
    void handlesZeroAndSingleSlotWithoutApplyingAChord() {
        WheelTextGeometry.Rect bottomLabel = new WheelTextGeometry.Rect(90, 140, 110, 150);

        assertFalse(fits(bottomLabel, 0, -90, 0));
        assertTrue(fits(bottomLabel, 1, -90, 358.2f));
    }

    @Test
    void checksTwoEightAndTwelveSlotWedgesByTheirActualAngles() {
        WheelTextGeometry.Rect rightOfTwoSlotWheel = new WheelTextGeometry.Rect(165, 97, 170, 103);
        assertTrue(fits(rightOfTwoSlotWheel, 2, -90, 178.2f));

        // 8 槽和 12 槽使用各自的扇区中线位置，矩形仍需落在弧线以内。
        assertTrue(fits(new WheelTextGeometry.Rect(116, 51, 120, 56), 8, -90, 43.2f));
        assertTrue(fits(new WheelTextGeometry.Rect(110, 49, 116, 55), 12, -90, 28.2f));
    }

    @Test
    void acceptsSectorsThatCrossZeroDegrees() {
        WheelTextGeometry.Rect nearPositiveXAxis = new WheelTextGeometry.Rect(146, 98, 150, 102);

        assertTrue(fits(nearPositiveXAxis, 12, 350, 20));
    }

    @Test
    void accountsForShadowAndAnimatedGrowthAtScreenEdges() {
        WheelTextGeometry.Rect visibleWithoutShadow = new WheelTextGeometry.Rect(178, 140, 194, 150);

        assertTrue(WheelTextGeometry.fitsSector(visibleWithoutShadow, 200, 200,
                170, 100, 30, 80, 0, 360, 1));
        assertTrue(WheelTextGeometry.fitsSector(visibleWithoutShadow.expand(2), 200, 200,
                170, 100, 30, 80, 0, 360, 1));
        assertFalse(WheelTextGeometry.fitsSector(visibleWithoutShadow.expand(8), 200, 200,
                170, 100, 30, 80, 0, 360, 1));
    }

    @Test
    void rejectsSmallRingsAndRectanglesWhoseInteriorCutsThroughTheHub() {
        WheelTextGeometry.Rect tooLargeForThinRing = new WheelTextGeometry.Rect(120, 60, 124, 64);
        WheelTextGeometry.Rect spanningTheHub = new WheelTextGeometry.Rect(70, 90, 130, 110);

        assertFalse(WheelTextGeometry.fitsSector(tooLargeForThinRing, 200, 200,
                100, 100, 38, 42, -90, 88.2f, 4));
        assertFalse(fits(spanningTheHub, 1, 0, 360));
    }

    private static boolean fits(WheelTextGeometry.Rect rect, int slots, float start, float sweep) {
        return WheelTextGeometry.fitsSector(rect, 200, 200, 100, 100,
                30, 80, start, sweep, slots);
    }
}
