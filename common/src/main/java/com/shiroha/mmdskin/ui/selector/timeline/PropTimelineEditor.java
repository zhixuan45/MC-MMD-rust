package com.shiroha.mmdskin.ui.selector.timeline;

import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Attachment;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Key;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.State;

import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

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

    public PropTimelineEditor(PropTimeline timeline) {
        saved = snapshot = timeline;
        initial = timeline.initial();
        timeline.visibility().forEach(key -> visibility.put(key.frame(),key.value()));
        timeline.attachments().forEach(key -> attachments.put(key.frame(),key.value()));
    }

    public PropTimeline snapshot() {
        if (snapshot == null) {
            snapshot = new PropTimeline(initial,visibility.entrySet().stream().map(e -> new Key<>(e.getKey(),e.getValue())).toList(),
                    attachments.entrySet().stream().map(e -> new Key<>(e.getKey(),e.getValue())).toList());
        }
        return snapshot;
    }

    public boolean dirty() {
        PropTimeline value = snapshot();
        return !value.initial().equals(saved.initial()) || !value.visibility().equals(saved.visibility())
                || !value.attachments().equals(saved.attachments());
    }

    public void markSaved() { saved = snapshot(); }
    public long revision() { return revision; }
    public int frame() { return frame; }
    public State state() { return snapshot().evaluate(frame); }
    public boolean playing() { return playing; }
    public boolean initialVisible() { return initial.visible(); }
    public boolean hasKey(Track track) { return keys(track).containsKey(frame); }
    public List<Integer> frames(Track track) { return List.copyOf(keys(track).navigableKeySet()); }

    public void motionEnd(float end) {
        if (!Float.isFinite(end) || end < 0 || end > Integer.MAX_VALUE - 1) return;
        motionEnd = (int)Math.ceil(end);
        if (frame > endFrame()) seek(endFrame());
    }

    public int endFrame() {
        return Math.max(motionEnd,Math.max(visibility.isEmpty()?0:visibility.lastKey(),
                attachments.isEmpty()?0:attachments.lastKey()));
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
        initial = new State(visible,initial.attachment());
        changed();
    }

    public void visibility(boolean visible) { visibility.put(frame,visible); changed(); }
    public void attachment(Attachment attachment) { attachments.put(frame,java.util.Objects.requireNonNull(attachment)); changed(); }
    public void delete(Track track) { if (keys(track).remove(frame) != null) changed(); }

    public void adjacent(Track track,boolean next) {
        Integer target = next ? keys(track).higherKey(frame) : keys(track).lowerKey(frame);
        if (target != null) seek(target);
    }

    private NavigableMap<Integer,?> keys(Track track) { return track == Track.VISIBILITY ? visibility : attachments; }
    private void changed() { snapshot = null; revision++; }
}
