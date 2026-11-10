package com.shiroha.mmdskin.stage.domain.timeline;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
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

    private static LinkedHashMap<String,BoneBinding> linearBindings(PropTimeline timeline, float frame) {
        var bindings = new LinkedHashMap<>(timeline.initial().boneBindings());
        for (Key<BoneBinding> key : timeline.boneKeys()) {
            if (key.frame() > frame) break;
            if (key.value().unbind()) bindings.remove(key.value().childBone());
            else bindings.put(key.value().childBone(), key.value());
        }
        return bindings;
    }

    private static void assertMatchesLinearBindings(PropTimeline timeline, int lastFrame) {
        for (int frame = 0; frame <= lastFrame; frame++) {
            var expected = linearBindings(timeline, frame);
            var actual = timeline.evaluate(frame).boneBindings();
            assertEquals(expected, actual, "bindings at frame " + frame);
            assertEquals(List.copyOf(expected.keySet()), List.copyOf(actual.keySet()), "order at frame " + frame);
        }
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

    @Test void boneTrackIndexesPreserveRegistrationOrderAndUnbindRebindMapOrder() {
        var a = new BoneBinding("A", "Right", Offset.IDENTITY, identityCompensation(), true, false);
        var b = new BoneBinding("B", "Left", Offset.IDENTITY, identityCompensation(), true, false);
        var timeline = new PropTimeline(State.DEFAULT, List.of(), List.of(), List.of(
                new Key<>(20, b), new Key<>(20, a), new Key<>(30, BoneBinding.detached("A")),
                new Key<>(40, a)));

        assertEquals(List.of("B", "A"), List.copyOf(timeline.evaluate(20).boneBindings().keySet()));
        assertEquals(List.of("B"), List.copyOf(timeline.evaluate(35).boneBindings().keySet()));
        assertEquals(List.of("B", "A"), List.copyOf(timeline.evaluate(40).boneBindings().keySet()));
        assertEquals("Right", timeline.evaluate(40).boneBindings().get("A").bone());
        assertMatchesLinearBindings(timeline, 45);
    }

    @Test void updatingAnEarlierBindingKeepsItsOriginalInsertionPosition() {
        var a = new BoneBinding("A", "Right", Offset.IDENTITY, identityCompensation(), true, false);
        var aUpdated = new BoneBinding("A", "Right", new Offset(1, 0, 0, 0, 0, 0),
                identityCompensation(), true, false);
        var b = new BoneBinding("B", "Left", Offset.IDENTITY, identityCompensation(), true, false);
        var timeline = new PropTimeline(State.DEFAULT, List.of(), List.of(), List.of(
                new Key<>(0, a), new Key<>(10, b), new Key<>(20, aUpdated)));

        assertEquals(List.of("A"), List.copyOf(timeline.evaluate(0).boneBindings().keySet()));
        for (int frame : new int[]{10, 19, 20, 25}) {
            assertEquals(List.of("A", "B"), List.copyOf(timeline.evaluate(frame).boneBindings().keySet()));
        }
        assertEquals(aUpdated, timeline.evaluate(20).boneBindings().get("A"));
        assertMatchesLinearBindings(timeline, 25);
    }

    @Test void initialBindingsKeepTheirOrderWhenLaterKeysUpdateThem() {
        var initialBindings = new LinkedHashMap<String,BoneBinding>();
        initialBindings.put("A", new BoneBinding("A", "Right", Offset.IDENTITY,
                identityCompensation(), true, false));
        initialBindings.put("B", new BoneBinding("B", "Left", Offset.IDENTITY,
                identityCompensation(), true, false));
        var updatedA = new BoneBinding("A", "Right", new Offset(2, 0, 0, 0, 0, 0),
                identityCompensation(), true, false);
        var timeline = new PropTimeline(new State(true, null, initialBindings), List.of(), List.of(),
                List.of(new Key<>(10, updatedA)));

        assertEquals(List.of("A", "B"), List.copyOf(timeline.evaluate(0).boneBindings().keySet()));
        assertEquals(List.of("A", "B"), List.copyOf(timeline.evaluate(10).boneBindings().keySet()));
        assertMatchesLinearBindings(timeline, 15);
    }
}
