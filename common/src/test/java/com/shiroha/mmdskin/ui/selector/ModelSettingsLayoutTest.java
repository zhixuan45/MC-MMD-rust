package com.shiroha.mmdskin.ui.selector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelSettingsLayoutTest {
    @Test
    void addedTransformControlsRemainReachableWithoutCoveringFixedButtons() {
        for (int height : new int[]{180, 240, 480, 900}) {
            ModelSettingsLayout top = ModelSettingsLayout.create(320, height, 0);
            ModelSettingsLayout bottom = ModelSettingsLayout.create(320, height, Double.MAX_VALUE);
            assertTrue(top.viewport.bottom() < top.saveButton.y);
            assertTrue(bottom.quickCard.bottom() <= bottom.viewport.bottom());
            if (bottom.maxScroll > 0) {
                assertEquals(bottom.viewport.bottom(), bottom.quickCard.bottom());
            }
            assertTrue(bottom.contentHit(bottom.quickSlotButtons[3],
                    bottom.quickSlotButtons[3].centerX(), bottom.quickSlotButtons[3].centerY()));

            for (HeldPmxTransformControl control : HeldPmxTransformControl.values()) {
                int i = control.ordinal();
                var original = top.heldPmxTransformSliders[i];
                var scrolled = ModelSettingsLayout.create(320, height,
                        original.y - top.viewport.y).heldPmxTransformSliders[i];
                assertTrue(scrolled.y >= top.viewport.y);
                assertTrue(scrolled.bottom() <= top.viewport.bottom());
                assertTrue(top.heldPmxTransformMinus[i].right() < original.x);
                assertTrue(original.right() < top.heldPmxTransformPlus[i].x);
            }
            assertTrue(top.heldPmxTransformReset.bottom() < top.tailCard.y);
        }
    }
    @Test
    void boneAndStageMotionChoicesCanBeScrolledIntoSmallWindows() {
        for (int height:new int[]{180,240,480}) {
            ModelSettingsLayout top=ModelSettingsLayout.create(320,height,0);
            for (boolean bone:new boolean[]{true,false}) {
                var original=bone?top.heldPmxBoneButton:top.heldPmxMotionButton;
                var layout=ModelSettingsLayout.create(320,height,original.y-top.viewport.y);
                var button=bone?layout.heldPmxBoneButton:layout.heldPmxMotionButton;
                assertTrue(layout.contentHit(button,button.centerX(),button.centerY()));
                assertTrue(button.bottom()<layout.saveButton.y);
            }
        }
    }
}
