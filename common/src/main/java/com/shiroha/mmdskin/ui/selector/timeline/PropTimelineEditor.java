package com.shiroha.mmdskin.ui.selector.timeline;

import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Attachment;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Key;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.State;

import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.ArrayList;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.BoneBinding;

/** Editable keyframes and playback cursor; contains no Minecraft or native state. */
public final class PropTimelineEditor {
    public enum Track { VISIBILITY, ATTACHMENT }

    private final NavigableMap<Integer,Boolean> visibility = new TreeMap<>();
    private final NavigableMap<Integer,Attachment> attachments = new TreeMap<>();
    private State initial;
    private PropTimeline saved, snapshot;
    private int frame, motionEnd;
    private long revision;
    private double playbackFrame;
    private boolean playing;
    private final List<Key<BoneBinding>> boneKeys = new ArrayList<>();
    private String selectedBone = "";
    private boolean compensationStale;

    public PropTimelineEditor(PropTimeline timeline) {
        saved = snapshot = timeline;
        initial = timeline.initial();
        timeline.visibility().forEach(key -> visibility.put(key.frame(),key.value()));
        timeline.attachments().forEach(key -> attachments.put(key.frame(),key.value()));
        boneKeys.addAll(timeline.boneKeys());
        if (!boneKeys.isEmpty()) selectedBone=boneKeys.get(0).value().childBone();
    }

    public PropTimeline snapshot() {
        if (snapshot == null) {
            snapshot = new PropTimeline(initial,visibility.entrySet().stream().map(e -> new Key<>(e.getKey(),e.getValue())).toList(),
                    attachments.entrySet().stream().map(e -> new Key<>(e.getKey(),e.getValue())).toList(),boneKeys);
        }
        return snapshot;
    }

    public boolean dirty() {
        PropTimeline value = snapshot();
        return compensationStale || !value.initial().equals(saved.initial()) || !value.visibility().equals(saved.visibility())
                || !value.attachments().equals(saved.attachments()) || !value.boneKeys().equals(saved.boneKeys());
    }

    public void markSaved() { saved = snapshot(); compensationStale=false; }
    public long revision() { return revision; }
    public int frame() { return frame; }
    public State state() { return snapshot().evaluate(frame); }
    public boolean playing() { return playing; }
    public boolean initialVisible() { return initial.visible(); }
    public boolean hasKey(Track track) {
        return track==Track.ATTACHMENT && !selectedBone.isEmpty()
                ? boneKeys.stream().anyMatch(k -> k.frame()==frame && k.value().childBone().equals(selectedBone))
                : keys(track).containsKey(frame);
    }
    public List<Integer> frames(Track track) {
        return track==Track.ATTACHMENT && !selectedBone.isEmpty()
                ? boneKeys.stream().filter(k -> k.value().childBone().equals(selectedBone)).map(Key::frame).distinct().sorted().toList()
                : List.copyOf(keys(track).navigableKeySet());
    }
    public String selectedBone() { return selectedBone; }
    public void selectedBone(String bone) { selectedBone=bone==null?"":bone; }
    public List<String> bindingBones() { return boneKeys.stream().map(k -> k.value().childBone()).distinct().toList(); }
    public boolean compensationStale() { return compensationStale; }
    public void invalidateCompensation() { if (!boneKeys.isEmpty()) compensationStale=true; }

    public void boneBinding(BoneBinding binding) {
        int existing=-1;
        for (int i=0;i<boneKeys.size();i++) if (boneKeys.get(i).frame()==frame
                && boneKeys.get(i).value().childBone().equals(binding.childBone())) { existing=i; break; }
        if (existing>=0) boneKeys.set(existing,new Key<>(frame,binding));
        else boneKeys.add(new Key<>(frame,binding));
        boneKeys.sort(java.util.Comparator.comparingInt(Key::frame));
        selectedBone=binding.childBone();
        int position=boneKeys.indexOf(new Key<>(frame,binding));
        compensationStale=compensationStale || position<boneKeys.size()-1;
        changed();
    }

    /** Excludes the edited key and later same-frame registrations while calculating its baseline. */
    public PropTimeline beforeRegistration(int target,String child) {
        var previous=new ArrayList<Key<BoneBinding>>();
        for (var key:boneKeys) {
            if (key.frame()>target) break;
            if (key.frame()==target && key.value().childBone().equals(child)) break;
            previous.add(key);
        }
        return new PropTimeline(initial,snapshot().visibility(),snapshot().attachments(),previous);
    }
    public void replaceCalibrated(List<Key<BoneBinding>> keys) {
        boneKeys.clear(); boneKeys.addAll(keys); compensationStale=false; changed();
    }

    public void motionEnd(float end) {
        if (!Float.isFinite(end) || end < 0 || end > Integer.MAX_VALUE - 1) return;
        motionEnd = (int)Math.ceil(end);
        if (frame > endFrame()) seek(endFrame());
    }

    public int endFrame() {
        return Math.max(boneKeys.stream().mapToInt(Key::frame).max().orElse(0),
                Math.max(motionEnd,Math.max(visibility.isEmpty()?0:visibility.lastKey(),
                attachments.isEmpty()?0:attachments.lastKey())));
    }

    public void seek(int target) {
        frame = Math.max(0,Math.min(endFrame(),target));
        playbackFrame = frame;
    }

    public void playing(boolean value) {
        playing = value && endFrame() > 0;
        if (playing && frame >= endFrame()) seek(0);
    }

    public void advance(double deltaSeconds) {
        if (!playing || !Double.isFinite(deltaSeconds) || deltaSeconds <= 0) return;
        playbackFrame = Math.min(endFrame(),playbackFrame + Math.min(deltaSeconds,.1) * 30);
        frame = (int)playbackFrame;
        if (playbackFrame >= endFrame()) playing = false;
    }

    public void initialVisible(boolean visible) {
        initial = new State(visible,initial.attachment(),initial.boneBindings());
        changed();
    }

    public void visibility(boolean visible) { visibility.put(frame,visible); changed(); }
    public void attachment(Attachment attachment) { attachments.put(frame,java.util.Objects.requireNonNull(attachment)); invalidateCompensation(); changed(); }
    public void delete(Track track) {
        if (track==Track.ATTACHMENT && !selectedBone.isEmpty()) {
            if (boneKeys.removeIf(k -> k.frame()==frame && k.value().childBone().equals(selectedBone))) {
                invalidateCompensation(); changed();
            }
        } else if (keys(track).remove(frame) != null) { if(track==Track.ATTACHMENT) invalidateCompensation(); changed(); }
    }

    public void adjacent(Track track,boolean next) {
        var frames=new java.util.TreeSet<>(frames(track));
        Integer target = next ? frames.higher(frame) : frames.lower(frame);
        if (target != null) seek(target);
    }

    private NavigableMap<Integer,?> keys(Track track) { return track == Track.VISIBILITY ? visibility : attachments; }
    private void changed() { snapshot = null; revision++; }
}
