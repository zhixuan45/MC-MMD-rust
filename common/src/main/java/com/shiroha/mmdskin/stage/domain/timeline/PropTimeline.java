package com.shiroha.mmdskin.stage.domain.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;

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

    /** Compensation is a column-major parent-frame matrix, baked once at registration. */
    public record BoneBinding(String childBone,String bone,Offset offset,List<Float> compensation,
                              boolean keepWorld,boolean unbind) {
        public BoneBinding {
            if (childBone == null || childBone.isBlank() || childBone.length()>256
                    || childBone.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid prop bone");
            Objects.requireNonNull(offset);
            compensation = List.copyOf(compensation);
            if (compensation.size()!=16 || compensation.stream().anyMatch(v -> !Float.isFinite(v))) {
                throw new IllegalArgumentException("Invalid registration compensation");
            }
            float a=compensation.get(0),b=compensation.get(4),c=compensation.get(8);
            float d=compensation.get(1),e=compensation.get(5),f=compensation.get(9);
            float g=compensation.get(2),h=compensation.get(6),i=compensation.get(10);
            float determinant=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
            if(!Float.isFinite(determinant) || determinant<=1e-8f
                    || Math.abs(compensation.get(3))>1e-5f || Math.abs(compensation.get(7))>1e-5f
                    || Math.abs(compensation.get(11))>1e-5f || Math.abs(compensation.get(15)-1)>1e-5f) {
                throw new IllegalArgumentException("Compensation must be an invertible affine transform");
            }
            if (!unbind) new Attachment(bone,offset);
        }
        public static BoneBinding detached(String child) {
            return new BoneBinding(child,"",Offset.IDENTITY,identityCompensation(),false,true);
        }
    }

    public static List<Float> identityCompensation() {
        return List.of(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f);
    }

    public record State(boolean visible, Attachment attachment,Map<String,BoneBinding> boneBindings) {
        public State(boolean visible,Attachment attachment) { this(visible,attachment,Map.of()); }
        public State { boneBindings = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(boneBindings)); }
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
    private final List<Key<BoneBinding>> boneKeys;

    public PropTimeline(State initial, List<Key<Boolean>> visibility, List<Key<Attachment>> attachments) {
        this(initial,visibility,attachments,List.of());
    }

    public PropTimeline(State initial,List<Key<Boolean>> visibility,List<Key<Attachment>> attachments,
                        List<Key<BoneBinding>> boneKeys) {
        this.initial = Objects.requireNonNull(initial, "initial");
        this.visibility = sorted(visibility);
        this.attachments = sorted(attachments);
        var keys = new ArrayList<>(boneKeys);
        keys.sort(Comparator.comparingInt(Key::frame));
        var unique = new java.util.HashSet<String>();
        for (Key<BoneBinding> key : keys) {
            if (!unique.add(key.frame()+"\0"+key.value().childBone())) {
                throw new IllegalArgumentException("Duplicate prop bone key: "+key.value().childBone()+" at "+key.frame());
            }
        }
        this.boneKeys = List.copyOf(keys);
    }

    public State evaluate(float frame) {
        if (!Float.isFinite(frame) || frame < 0) throw new IllegalArgumentException("Invalid timeline frame");
        var bindings = new LinkedHashMap<>(initial.boneBindings());
        for (Key<BoneBinding> key : boneKeys) {
            if (key.frame()>frame) break;
            if (key.value().unbind()) bindings.remove(key.value().childBone());
            else bindings.put(key.value().childBone(),key.value());
        }
        return new State(valueAt(visibility, frame, initial.visible()),
                valueAt(attachments, frame, initial.attachment()),bindings);
    }

    public State initial() { return initial; }
    public List<Key<Boolean>> visibility() { return visibility; }
    public List<Key<Attachment>> attachments() { return attachments; }
    public List<Key<BoneBinding>> boneKeys() { return boneKeys; }

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
