package com.shiroha.mmdskin.render.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaperDollPoseStateTest {
    @Test void onlyTheEntityThatCompletedTheWorldDrawCanReuseIt() {
        var state = new PaperDollPoseState<Object>();
        Object player = new Object();
        assertFalse(state.matches(player, 1, 10));
        state.capture(player, 1, 10, false);
        assertTrue(state.matches(player, 1, 10));
        assertFalse(state.matches(new Object(), 1, 10));
        assertFalse(state.matches(null, 1, 10));
    }

    @Test void nextFrameAndChangedNativeInputsRequireANewWorldDraw() {
        var state = new PaperDollPoseState<Object>();
        Object player = new Object();
        state.capture(player, 1, 10, false);
        assertFalse(state.matches(player, 2, 10));
        assertFalse(state.matches(player, 1, 11));
        state.capture(player, 2, 11, false);
        assertTrue(state.matches(player, 2, 11));
        assertFalse(state.matches(player, 2, 10));
    }

    @Test void unknownEpochCannotAuthorizeReuse() {
        var state = new PaperDollPoseState<Object>();
        Object player = new Object();
        state.capture(player, 0, 10, false);
        assertFalse(state.matches(player, 0, 10));
        state.capture(player, 1, Long.MIN_VALUE, false);
        assertFalse(state.matches(player, 1, Long.MIN_VALUE));
    }

    @Test void latestDrawDeterminesWhetherFullBodyMeshSupportIsRequired() {
        var state = new PaperDollPoseState<Object>();
        Object player = new Object();
        state.capture(player, 1, 10, true);
        assertTrue(state.isFirstPerson());
        state.capture(player, 1, 10, false);
        assertFalse(state.isFirstPerson());
    }
}
