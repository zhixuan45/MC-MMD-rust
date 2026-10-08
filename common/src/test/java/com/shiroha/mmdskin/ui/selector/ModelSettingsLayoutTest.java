package com.shiroha.mmdskin.ui.selector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSettingsLayoutTest {
    private static final int SMALL_WIDTH = 400;
    private static final int SMALL_HEIGHT = 240;
    private static final int GUI_TEXT_HEIGHT = 9;

    @Test
    void scrollingMakesTailTogglesAndLastQuickSlotReachableWhileHeaderAndFooterStayFixed() {
        ModelSettingsLayout top = ModelSettingsLayout.create(SMALL_WIDTH, SMALL_HEIGHT, 0);
        assertTrue(top.maxScroll > 0);
        assertFalse(hitCenter(top, top.tailIdleToggle));
        assertFalse(hitCenter(top, top.tailMovementToggle));
        assertFalse(hitCenter(top, top.quickSlotButtons[3]));

        ModelSettingsLayout bottom = top;
        while (bottom.scrollOffset < bottom.maxScroll) {
            double nextOffset = bottom.scrollBy(-1);
            assertTrue(nextOffset > bottom.scrollOffset);
            bottom = ModelSettingsLayout.create(SMALL_WIDTH, SMALL_HEIGHT, nextOffset);
        }

        assertTrue(hitCenter(bottom, bottom.tailIdleToggle));
        assertTrue(hitCenter(bottom, bottom.tailMovementToggle));
        assertTrue(hitCenter(bottom, bottom.quickSlotButtons[3]));
        assertSameRect(top.header, bottom.header);
        assertSameRect(top.saveButton, bottom.saveButton);
        assertSameRect(top.resetButton, bottom.resetButton);
        assertSameRect(top.animButton, bottom.animButton);
        assertSameRect(top.doneButton, bottom.doneButton);
    }

    @Test
    void contentHitsRespectViewportClippingAndExcludeFooter() {
        ModelSettingsLayout top = ModelSettingsLayout.create(SMALL_WIDTH, SMALL_HEIGHT, 0);
        assertFalse(hitCenter(top, top.tailIdleToggle));
        assertFalse(hitCenter(top, top.quickSlotButtons[3]));
        assertFalse(hitCenter(top, top.saveButton));

        // 让尾巴开关末端恰好越过视口边界，裁剪区外即使仍在控件矩形内也不可命中。
        double partialOffset = top.tailIdleToggle.bottom() - top.viewport.bottom() - 1;
        ModelSettingsLayout partial = ModelSettingsLayout.create(SMALL_WIDTH, SMALL_HEIGHT, partialOffset);
        double clippedX = partial.tailIdleToggle.centerX();
        double clippedY = partial.viewport.bottom();
        assertTrue(partial.tailIdleToggle.contains(clippedX, clippedY));
        assertFalse(partial.contentHit(partial.tailIdleToggle, clippedX, clippedY));
    }

    @Test
    void wheelAndResizeClampOffsetToTheCurrentContentRange() {
        ModelSettingsLayout small = ModelSettingsLayout.create(SMALL_WIDTH, SMALL_HEIGHT, 0);
        assertEquals(0, small.scrollBy(1_000), 0.0);
        assertEquals(small.maxScroll, small.scrollBy(-1_000), 0.0);
        assertEquals(0, small.scrollBy(Double.NaN), 0.0);

        ModelSettingsLayout resized = ModelSettingsLayout.create(
                SMALL_WIDTH, SMALL_HEIGHT + 20, small.scrollBy(-1_000));
        assertTrue(resized.maxScroll < small.maxScroll);
        assertEquals(resized.maxScroll, resized.scrollOffset, 0.0);

        ModelSettingsLayout expanded = ModelSettingsLayout.create(600, 800, resized.scrollOffset);
        assertEquals(0, expanded.maxScroll);
        assertEquals(0, expanded.scrollOffset, 0.0);
        assertEquals(0, expanded.scrollTrack.w);
        assertEquals(0, expanded.scrollThumb.h);
    }

    @Test
    void scrollbarThumbEndpointsMapToContentEndpoints() {
        ModelSettingsLayout layout = ModelSettingsLayout.create(SMALL_WIDTH, SMALL_HEIGHT, 0);
        assertTrue(layout.maxScroll > 0);
        assertEquals(0, layout.scrollToThumb(layout.scrollTrack.y), 0.0);
        assertEquals(layout.maxScroll,
                layout.scrollToThumb(layout.scrollTrack.bottom() - layout.scrollThumb.h), 0.0);
    }

    @Test
    void eyeLabelsHaveSeparateTextRowsAndControlsDoNotOverlapTheirRows() {
        ModelSettingsLayout layout = ModelSettingsLayout.create(600, 800, 0);
        int titleTop = layout.eyeCard.y + 3;
        int enabledTop = layout.eyeToggle.y;
        int angleTop = layout.eyeSlider.y - 9;

        assertTrue(titleTop + GUI_TEXT_HEIGHT <= enabledTop);
        assertTrue(enabledTop + GUI_TEXT_HEIGHT <= angleTop);
        assertTrue(layout.eyeToggle.bottom() <= angleTop);
        assertTrue(angleTop + GUI_TEXT_HEIGHT <= layout.eyeSlider.y);
    }

    private static boolean hitCenter(ModelSettingsLayout layout, ModelSettingsLayout.UiRect rect) {
        return layout.contentHit(rect, rect.centerX(), rect.centerY());
    }

    private static void assertSameRect(ModelSettingsLayout.UiRect expected, ModelSettingsLayout.UiRect actual) {
        assertEquals(expected.x, actual.x);
        assertEquals(expected.y, actual.y);
        assertEquals(expected.w, actual.w);
        assertEquals(expected.h, actual.h);
    }
}
