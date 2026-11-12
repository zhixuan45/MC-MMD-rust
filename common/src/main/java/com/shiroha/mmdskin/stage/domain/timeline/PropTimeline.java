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

    /** A null stage attachment uses independent model coordinates; ordinary held items use their configured grip. */
    public record Attachment(String bone, Offset offset) {
        public Attachment {
            Objects.requireNonNull(bone, "bone");
            Objects.requireNonNull(offset, "offset");
            if (bone.isBlank() || bone.length() > 256 || bone.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid attachment bone");
            }
        }
    }

    /** Column-major outside-parent compensation, before the static origin is removed at runtime. */
    public record BoneBinding(String childBone,String bone,Offset offset,List<Float> compensation,
                              boolean keepWorld,boolean unbind,Offset placement) {
        public BoneBinding(String childBone,String bone,Offset offset,List<Float> compensation,
                           boolean keepWorld,boolean unbind) {
            this(childBone,bone,offset,compensation,keepWorld,unbind,null);
        }
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
            if(placement!=null && (!keepWorld || unbind)) {
                throw new IllegalArgumentException("Placed bones must register with pose compensation");
            }
        }
        public static BoneBinding detached(String child) {
            return new BoneBinding(child,"",Offset.IDENTITY,identityCompensation(),false,true);
        }
    }

    public static List<Float> identityCompensation() {
        return List.of(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f);
    }

    /**
     * MMD outside-parent VMD switch window for one prop bone.
     *
     * <p>{@code previousFrame} is the start of the current binding segment, or the
     * first future binding start when the sampled frame is still unbound. {@code
     * nextFrame} is the next frame whose target differs, or zero when no switch
     * follows. {@link #DEFAULT} keeps ordinary VMD sampling.
     */
    public record BoneWindow(int previousFrame, int nextFrame) {
        public static final BoneWindow DEFAULT = new BoneWindow(0, 0);

        public BoneWindow {
            if (previousFrame < 0 || nextFrame < 0) {
                throw new IllegalArgumentException("Negative outside-parent window frame");
            }
            if (nextFrame != 0 && previousFrame >= nextFrame) {
                throw new IllegalArgumentException("Invalid outside-parent window");
            }
        }

        public boolean active() { return previousFrame != 0 || nextFrame != 0; }
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
    private final Map<String,List<TargetChange>> targetChanges;
    private final Map<String,List<IndexedBoneKey>> boneTracks;

    private record TargetChange(int frame,String target) {}
    private record IndexedBoneKey(int insertionOrder,Key<BoneBinding> key) {}
    private record EvaluatedBoneBinding(int insertionOrder,String childBone,BoneBinding binding) {}

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
        var tracks = new LinkedHashMap<String,List<IndexedBoneKey>>();
        var bound = new java.util.HashMap<String,Boolean>();
        var insertionOrders = new java.util.HashMap<String,Integer>();
        int initialIndex = 0;
        for (String childBone : initial.boneBindings().keySet()) {
            bound.put(childBone, true);
            insertionOrders.put(childBone, initialIndex++ - initial.boneBindings().size());
        }
        for (int i = 0; i < this.boneKeys.size(); i++) {
            Key<BoneBinding> key = this.boneKeys.get(i);
            String childBone = key.value().childBone();
            if (key.value().unbind()) {
                bound.put(childBone, false);
            } else {
                if (!bound.getOrDefault(childBone, false)) insertionOrders.put(childBone, i);
                bound.put(childBone, true);
            }
            tracks.computeIfAbsent(key.value().childBone(), ignored -> new ArrayList<>())
                    .add(new IndexedBoneKey(insertionOrders.getOrDefault(childBone, i), key));
        }
        var immutableTracks = new LinkedHashMap<String,List<IndexedBoneKey>>();
        tracks.forEach((bone, track) -> immutableTracks.put(bone, List.copyOf(track)));
        this.boneTracks = java.util.Collections.unmodifiableMap(immutableTracks);
        this.targetChanges = buildTargetChanges(initial, this.boneKeys);
    }

    public State evaluate(float frame) {
        if (!Float.isFinite(frame) || frame < 0) throw new IllegalArgumentException("Invalid timeline frame");
        var activeKeys = new LinkedHashMap<String,IndexedBoneKey>();
        for (List<IndexedBoneKey> track : boneTracks.values()) {
            int index = lastBoneKeyAtOrBefore(track, frame);
            if (index >= 0) {
                IndexedBoneKey key = track.get(index);
                activeKeys.put(key.key().value().childBone(), key);
            }
        }
        var evaluated = new ArrayList<EvaluatedBoneBinding>(initial.boneBindings().size() + activeKeys.size());
        int initialOrder = 0;
        for (var entry : initial.boneBindings().entrySet()) {
            IndexedBoneKey indexed = activeKeys.remove(entry.getKey());
            if (indexed == null) {
                evaluated.add(new EvaluatedBoneBinding(initialOrder - initial.boneBindings().size(),
                        entry.getKey(), entry.getValue()));
            } else if (!indexed.key().value().unbind()) {
                evaluated.add(new EvaluatedBoneBinding(indexed.insertionOrder(), entry.getKey(), indexed.key().value()));
            }
            initialOrder++;
        }
        for (IndexedBoneKey indexed : activeKeys.values()) {
            if (!indexed.key().value().unbind()) {
                evaluated.add(new EvaluatedBoneBinding(indexed.insertionOrder(),
                        indexed.key().value().childBone(), indexed.key().value()));
            }
        }
        evaluated.sort(Comparator.comparingInt(EvaluatedBoneBinding::insertionOrder));
        var bindings = new LinkedHashMap<String,BoneBinding>();
        for (EvaluatedBoneBinding entry : evaluated) bindings.put(entry.childBone(), entry.binding());
        return new State(valueAt(visibility, frame, initial.visible()),
                valueAt(attachments, frame, initial.attachment()),bindings);
    }

    public State initial() { return initial; }
    public List<Key<Boolean>> visibility() { return visibility; }
    public List<Key<Attachment>> attachments() { return attachments; }
    public List<Key<BoneBinding>> boneKeys() { return boneKeys; }

    /**
     * Window for one child bone. Bones without any binding history have no window.
     * The timeline instance is already owner-scoped, so the target bone name is
     * sufficient; no owner UUID is added to the timeline protocol.
     */
    public BoneWindow outsideParentWindow(String childBone, float frame) {
        Objects.requireNonNull(childBone, "childBone");
        validateFrame(frame);
        var changes = targetChanges.get(childBone);
        if (changes == null) return BoneWindow.DEFAULT;
        int index = lastChangeAtOrBefore(changes, frame);
        if (index >= 0) {
            int previous = changes.get(index).frame();
            int next = index + 1 < changes.size() ? changes.get(index + 1).frame() : 0;
            return new BoneWindow(previous, next);
        }
        // Before the first binding, the future start must still affect earlier frames.
        int previous = changes.get(0).frame();
        int next = changes.size() > 1 ? changes.get(1).frame() : 0;
        return new BoneWindow(previous, next);
    }

    /** Windows for every child with a historical or future outside parent. */
    public Map<String,BoneWindow> outsideParentWindows(float frame) {
        validateFrame(frame);
        var result = new LinkedHashMap<String,BoneWindow>();
        for (String childBone : targetChanges.keySet()) {
            result.put(childBone, outsideParentWindow(childBone, frame));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * Windows for timeline bindings plus any binding currently present in {@code
     * state}. State-only bindings keep {@link BoneWindow#DEFAULT} because they have
     * no timeline switch history yet.
     */
    public Map<String,BoneWindow> outsideParentWindows(float frame, State state) {
        Objects.requireNonNull(state, "state");
        var result = new LinkedHashMap<>(outsideParentWindows(frame));
        for (String childBone : state.boneBindings().keySet()) {
            result.putIfAbsent(childBone, BoneWindow.DEFAULT);
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Map<String,List<TargetChange>> buildTargetChanges(State initial,List<Key<BoneBinding>> keys) {
        var changes = new LinkedHashMap<String,List<TargetChange>>();
        for (var entry : initial.boneBindings().entrySet()) {
            if (!entry.getValue().unbind()) {
                changes.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(new TargetChange(0, entry.getValue().bone()));
            }
        }
        for (Key<BoneBinding> key : keys) {
            BoneBinding binding = key.value();
            String target = binding.unbind() ? null : binding.bone();
            var childChanges = changes.computeIfAbsent(binding.childBone(), ignored -> new ArrayList<>());
            if (!childChanges.isEmpty()
                    && childChanges.get(childChanges.size() - 1).frame() == key.frame()) {
                // A frame-zero key overrides an initial binding at the same frame.
                childChanges.set(childChanges.size() - 1, new TargetChange(key.frame(), target));
            } else {
                String previous = childChanges.isEmpty()
                        ? null
                        : childChanges.get(childChanges.size() - 1).target();
                if (!Objects.equals(previous,target)) childChanges.add(new TargetChange(key.frame(), target));
            }
        }
        var result = new LinkedHashMap<String,List<TargetChange>>();
        for (var entry : changes.entrySet()) {
            if (entry.getValue().stream().anyMatch(change -> change.target() != null)) {
                result.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static int lastChangeAtOrBefore(List<TargetChange> changes, float frame) {
        int low = 0, high = changes.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (changes.get(middle).frame() <= frame) low = middle + 1;
            else high = middle;
        }
        return low - 1;
    }

    private static int lastBoneKeyAtOrBefore(List<IndexedBoneKey> keys, float frame) {
        int low = 0, high = keys.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (keys.get(middle).key().frame() <= (double) frame) low = middle + 1;
            else high = middle;
        }
        return low - 1;
    }

    private static void validateFrame(float frame) {
        if (!Float.isFinite(frame) || frame < 0) throw new IllegalArgumentException("Invalid timeline frame");
    }

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
