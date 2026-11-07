package com.shiroha.mmdskin.stage.domain.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Step tracks are evaluated at an absolute stage frame, independently of playback history. */
public final class PropTimeline {
    public record Offset(float x, float y, float z, float rotationX, float rotationY, float rotationZ) {
        public static final Offset IDENTITY = new Offset(0, 0, 0, 0, 0, 0);

        public Offset {
            for (float value : new float[]{x, y, z, rotationX, rotationY, rotationZ}) {
                if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite attachment offset");
            }
        }
    }

    /** A null attachment in State inherits the user's configured prop attachment. */
    public record Attachment(String bone, Offset offset) {
        public Attachment {
            Objects.requireNonNull(bone, "bone");
            Objects.requireNonNull(offset, "offset");
            if (bone.isBlank() || bone.length() > 256 || bone.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid attachment bone");
            }
        }
    }

    public record State(boolean visible, Attachment attachment) {
        public static final State DEFAULT = new State(true, null);
    }

    public record Key<T>(int frame, T value) {
        public Key {
            if (frame < 0) throw new IllegalArgumentException("Negative timeline frame");
            Objects.requireNonNull(value, "value");
        }
    }

    public static final PropTimeline EMPTY = new PropTimeline(State.DEFAULT, List.of(), List.of());

    private final State initial;
    private final List<Key<Boolean>> visibility;
    private final List<Key<Attachment>> attachments;

    public PropTimeline(State initial, List<Key<Boolean>> visibility, List<Key<Attachment>> attachments) {
        this.initial = Objects.requireNonNull(initial, "initial");
        this.visibility = sorted(visibility);
        this.attachments = sorted(attachments);
    }

    public State evaluate(float frame) {
        if (!Float.isFinite(frame) || frame < 0) throw new IllegalArgumentException("Invalid timeline frame");
        return new State(valueAt(visibility, frame, initial.visible()),
                valueAt(attachments, frame, initial.attachment()));
    }

    public State initial() { return initial; }
    public List<Key<Boolean>> visibility() { return visibility; }
    public List<Key<Attachment>> attachments() { return attachments; }

    private static <T> List<Key<T>> sorted(List<Key<T>> keys) {
        var result = new ArrayList<>(keys);
        result.sort(Comparator.comparingInt(Key::frame));
        for (int i = 1; i < result.size(); i++) {
            if (result.get(i - 1).frame() == result.get(i).frame()) {
                throw new IllegalArgumentException("Duplicate keyframe at " + result.get(i).frame());
            }
        }
        return List.copyOf(result);
    }

    private static <T> T valueAt(List<Key<T>> keys, float frame, T initial) {
        int low = 0, high = keys.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (keys.get(middle).frame() <= (double) frame) low = middle + 1;
            else high = middle;
        }
        return low == 0 ? initial : keys.get(low - 1).value();
    }
}
