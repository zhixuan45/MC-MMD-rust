package com.shiroha.mmdskin.render.backend;

import com.shiroha.mmdskin.render.pipeline.RenderFrameClock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FirstPersonPoseStateTest {
    @Test void anUnconsumedPreparedPoseCannotLeakIntoTheNextFrame() {
        var state = new FirstPersonPoseState();
        RenderFrameClock.begin();
        state.markPrepared();
        assertTrue(state.isPrepared());
        state.finishRender(false);
        assertTrue(state.isPrepared());
        RenderFrameClock.begin();
        assertFalse(state.isPrepared());
    }

    @Test void preparationAndTheFirstPersonDrawConsumePreviousState() {
        var state = new FirstPersonPoseState();
        state.markPrepared();
        state.beginPreparation();
        assertFalse(state.isPrepared());
        state.markPrepared();
        state.finishRender(true);
        assertFalse(state.isPrepared());
        state.markPrepared();
        state.discard();
        assertFalse(state.isPrepared());
    }
}
