package com.shiroha.mmdskin.render.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenePoseSnapshotTest {
    @Test void worldFlagDoesNotChangeTheCurrentNativeHeadSemantics() {
        var world = new ScenePoseSnapshot(null);
        var gui = new ScenePoseSnapshot(null);
        world.setHeadAngle(1, .2f, .3f, 0, true);
        gui.setHeadAngle(2, .2f, .3f, 0, false);
        assertTrue(world.sameInputs(gui));
        gui.setEyeTrackingEnabled(2, false);
        assertFalse(world.sameInputs(gui));
    }

    @Test void allPoseInputsAndTheirRawValuesMustAgree() {
        var source = pose(.3f, 4);
        assertTrue(source.sameInputs(pose(.3f, 4)));
        assertFalse(source.sameInputs(pose(.31f, 4)));
        assertFalse(source.sameInputs(pose(.3f, 5)));
        assertFalse(source.sameInputs(null));
        var positive = new ScenePoseSnapshot(null);
        var negative = new ScenePoseSnapshot(null);
        positive.setEyeAngle(1, 0.0f, 0);
        negative.setEyeAngle(1, -0.0f, 0);
        assertFalse(positive.sameInputs(negative));
    }

    private static ScenePoseSnapshot pose(float eye, float root) {
        var result = new ScenePoseSnapshot(null);
        for (int i = 0; i < 8; i++) {
            result.setHeadAngle(1, .1f, .2f, .3f, true);
            result.setEyeTrackingEnabled(1, true);
            result.setEyeMaxAngle(1, .4f);
            result.setEyeAngle(1, .2f, eye);
            result.setAutoBlinkEnabled(1, true);
            result.setTailPhysicsOptions(1, true, false);
            result.setModelPositionAndYaw(1, root, 2, 3, .4f);
        }
        return result;
    }
}
