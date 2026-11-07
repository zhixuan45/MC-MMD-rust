package com.shiroha.mmdskin.stage.domain.timeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.*;
import static org.junit.jupiter.api.Assertions.*;

class PropTimelineTest {
    private static final Attachment RIGHT = new Attachment("右ダミー", Offset.IDENTITY);
    private static final Attachment LEFT = new Attachment("左ダミー", new Offset(1, 2, 3, 0, 0, 90));

    private static PropTimeline fan() {
        return new PropTimeline(new State(false, null),
                List.of(new Key<>(900, false), new Key<>(120, true)),
                List.of(new Key<>(480, LEFT), new Key<>(0, RIGHT)));
    }

    @Test void fractionalFramesUseTheLastKeyIncludingTheExactBoundary() {
        var timeline = fan();
        assertEquals(new State(false, RIGHT), timeline.evaluate(0));
        assertFalse(timeline.evaluate(119.99f).visible());
        assertTrue(timeline.evaluate(120).visible());
        assertEquals(RIGHT, timeline.evaluate(479.99f).attachment());
        assertEquals(new State(true, LEFT), timeline.evaluate(480));
        assertEquals(new State(false, LEFT), timeline.evaluate(900));
        assertEquals(new State(false, LEFT), timeline.evaluate(1000));
    }

    @Test void directSeekRewindAndLoopDoNotDependOnPreviousEvaluations() {
        var timeline = fan();
        assertEquals(new State(true, LEFT), timeline.evaluate(600));
        timeline.evaluate(1000);
        assertEquals(new State(true, RIGHT), timeline.evaluate(300));
        assertEquals(new State(false, RIGHT), timeline.evaluate(0));
        assertEquals(new State(true, LEFT), fan().evaluate(600));
    }

    @Test void emptyTracksInheritInitialStateAndCopyInputCollections() {
        assertEquals(State.DEFAULT, EMPTY.evaluate(123));
        var keys = new java.util.ArrayList<Key<Boolean>>();
        keys.add(new Key<>(10, true));
        var timeline = new PropTimeline(new State(false, RIGHT), keys, List.of());
        keys.clear();
        assertEquals(new State(false, RIGHT), timeline.evaluate(9));
        assertEquals(new State(true, RIGHT), timeline.evaluate(10));
    }

    @Test void invalidFramesAndAmbiguousDuplicateKeysAreRejected() {
        for (float frame : new float[]{-1, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> EMPTY.evaluate(frame));
        }
        assertThrows(IllegalArgumentException.class, () -> new Key<>(-1, true));
        assertThrows(IllegalArgumentException.class, () -> new PropTimeline(State.DEFAULT,
                List.of(new Key<>(10, true), new Key<>(10, false)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Offset(Float.NaN, 0, 0, 0, 0, 0));
    }
}
