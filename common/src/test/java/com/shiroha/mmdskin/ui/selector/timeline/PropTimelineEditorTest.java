package com.shiroha.mmdskin.ui.selector.timeline;

import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.*;
import static com.shiroha.mmdskin.ui.selector.timeline.PropTimelineEditor.Track.*;
import static org.junit.jupiter.api.Assertions.*;

class PropTimelineEditorTest {
    private static final Attachment RIGHT = new Attachment("右ダミー",Offset.IDENTITY);
    private static final Attachment LEFT = new Attachment("左ダミー",new Offset(1,2,3,10,20,30));

    @Test void addsUpdatesAndDeletesOnlyTheSelectedFramesProperty() {
        var editor = new PropTimelineEditor(EMPTY);
        editor.motionEnd(1000);
        editor.initialVisible(false);
        editor.seek(120); editor.visibility(true); editor.attachment(RIGHT);
        editor.seek(480); editor.attachment(LEFT);
        editor.seek(900); editor.visibility(false);
        assertEquals(new State(true,LEFT),editor.snapshot().evaluate(600));
        editor.seek(480); editor.attachment(RIGHT);
        assertEquals(2,editor.frames(ATTACHMENT).size());
        assertEquals(RIGHT,editor.state().attachment());
        editor.delete(ATTACHMENT);
        assertFalse(editor.hasKey(ATTACHMENT));
        assertEquals(List.of(120),editor.frames(ATTACHMENT));
        assertEquals(List.of(120,900),editor.frames(VISIBILITY));
        editor.seek(119);
        assertEquals(new State(false,null),editor.state());
    }

    @Test void saveDirtyStateCanReturnToCleanAfterUndoingAnEdit() {
        var editor = new PropTimelineEditor(EMPTY);
        editor.motionEnd(300);
        assertFalse(editor.dirty());
        editor.seek(42); editor.visibility(false);
        assertTrue(editor.dirty());
        editor.delete(VISIBILITY);
        assertFalse(editor.dirty());
        editor.attachment(RIGHT); editor.markSaved();
        assertFalse(editor.dirty());
        editor.attachment(LEFT); assertTrue(editor.dirty());
        editor.attachment(RIGHT); assertFalse(editor.dirty());
    }

    @Test void timelineExtentRetainsKeysBeyondShortMotionsAndNavigatesEachTrack() {
        var editor = new PropTimelineEditor(new PropTimeline(State.DEFAULT,
                List.of(new Key<>(120,false),new Key<>(900,true)),List.of(new Key<>(480,LEFT))));
        editor.motionEnd(100);
        assertEquals(900,editor.endFrame());
        editor.adjacent(VISIBILITY,true); assertEquals(120,editor.frame());
        editor.adjacent(ATTACHMENT,true); assertEquals(480,editor.frame());
        editor.adjacent(VISIBILITY,false); assertEquals(120,editor.frame());
        editor.seek(Integer.MAX_VALUE); assertEquals(900,editor.frame());
        editor.seek(-1); assertEquals(0,editor.frame());
        editor.motionEnd(Float.NaN); assertEquals(900,editor.endFrame());
    }

    @Test void playbackUsesThirtyFramesPerSecondAndStopsAtTheEndWithoutChangingKeys() {
        var editor = new PropTimelineEditor(EMPTY);
        editor.motionEnd(60);
        editor.playing(true);
        for (int i=0;i<10;i++) editor.advance(.1);
        assertEquals(30,editor.frame()); assertTrue(editor.playing());
        editor.seek(59); editor.playing(true); editor.advance(.1);
        assertEquals(60,editor.frame()); assertFalse(editor.playing());
        editor.playing(true); assertEquals(0,editor.frame());
        editor.advance(Double.NaN); assertEquals(0,editor.frame());
        assertFalse(editor.dirty());
    }
}
