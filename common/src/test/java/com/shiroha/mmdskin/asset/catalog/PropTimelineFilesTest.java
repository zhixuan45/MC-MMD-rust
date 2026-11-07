package com.shiroha.mmdskin.asset.catalog;

import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PropTimelineFilesTest {
    @TempDir Path dir;

    private static final String FAN = """
            {
              "version": 1,
              "motion": "扇子动作.vmd",
              "objects": [{
                "id": "fan", "asset": "团扇.pmx",
                "initial": {"visible": false},
                "tracks": {
                  "visibility": [{"frame": 120, "value": true}, {"frame": 900, "value": false}],
                  "attachment": [
                    {"frame": 0, "parent": {"object": "actor", "bone": "右ダミー"}, "mode": "snap"},
                    {"frame": 480, "parent": {"object": "actor", "bone": "左ダミー"},
                     "offset": [1, 2, 3], "rotation": [0, 0, 90], "mode": "snap"}
                  ]
                }
              }]
            }
            """;

    @Test void loadsUnicodeSidecarNextToTheResolvedVmdAndEvaluatesItsKeys() throws Exception {
        Path motion = dir.resolve("anims/扇子动作.vmd");
        Files.createDirectories(motion.getParent());
        Files.writeString(PropTimelineFiles.sidecar(motion), FAN);
        var timeline = PropTimelineFiles.load(motion, "团扇.pmx");
        assertFalse(timeline.evaluate(119).visible());
        assertEquals("右ダミー", timeline.evaluate(120).attachment().bone());
        var pose = timeline.evaluate(600);
        assertTrue(pose.visible());
        assertEquals("左ダミー", pose.attachment().bone());
        assertEquals(new PropTimeline.Offset(1, 2, 3, 0, 0, 90), pose.attachment().offset());
        assertFalse(timeline.evaluate(900).visible());
    }

    @Test void absentSidecarPreservesExistingBehaviorAndDoesNotCreateFiles() throws Exception {
        Path motion = dir.resolve("prop.VMD");
        assertSame(PropTimeline.EMPTY, PropTimelineFiles.load(motion, "fan.pmx"));
        assertFalse(Files.exists(PropTimelineFiles.sidecar(motion)));
    }

    @Test void ignoresASidecarFromTheOtherMotionSearchDirectory() throws Exception {
        Files.createDirectories(dir.resolve("anims"));
        Files.write(dir.resolve("扇子动作.vmd"), new byte[]{1});
        Files.write(dir.resolve("anims/扇子动作.vmd"), new byte[]{2});
        Files.writeString(dir.resolve("扇子动作.timeline.json"), FAN);
        Path selected = HeldPmxMotionFiles.resolve(dir, "扇子动作.vmd");
        assertEquals(dir.resolve("anims/扇子动作.vmd"), selected);
        assertSame(PropTimeline.EMPTY, PropTimelineFiles.load(selected, "团扇.pmx"));
    }

    @Test void rejectsMalformedOrUnsupportedTracksInsteadOfPartiallyApplyingThem() {
        for (String json : new String[]{
                "{", FAN.replace("\"version\": 1", "\"version\": 2"),
                FAN.replace("\"frame\": 120", "\"frame\": 120.5"),
                FAN.replace("\"frame\": 120", "\"frame\": -1"),
                FAN.replace("\"frame\": 900", "\"frame\": 120"),
                FAN.replace("\"frame\": 120", "\"frame\": 2147483648"),
                FAN.replace("\"value\": true", "\"value\": \"true\""),
                FAN.replace("\"snap\"", "\"keepWorld\""),
                FAN.replace("\"actor\"", "\"otherActor\""),
                FAN.replace("\"visibility\":", "\"audio\":"),
                FAN.replace("[1, 2, 3]", "[1, 2]"),
                FAN.replace("[1, 2, 3]", "[1e100, 2, 3]"),
                FAN.replace("\"右ダミー\"", "\"\""),
                FAN.replace("\"mode\": \"snap\"", "\"childBone\": \"root\"")
        }) {
            assertThrows(RuntimeException.class,
                    () -> PropTimelineFiles.parse(json, "扇子动作.vmd", "团扇.pmx"), json);
        }
    }

    @Test void validatesMotionAndPropIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> PropTimelineFiles.parse(FAN, "other.vmd", "团扇.pmx"));
        assertThrows(IllegalArgumentException.class,
                () -> PropTimelineFiles.parse(FAN, "扇子动作.vmd", "other.pmx"));
    }

    @Test void supportsInitialBindingBeforeTheFirstKey() {
        String json = """
                {"version":1,"objects":[{"id":"fan","asset":"fan.pmx",
                  "initial":{"attachment":{"parent":{"object":"actor","bone":"頭"}}}}]}
                """;
        var timeline = PropTimelineFiles.parse(json, "prop.vmd", "fan.pmx");
        assertEquals(new PropTimeline.State(true,
                new PropTimeline.Attachment("頭", PropTimeline.Offset.IDENTITY)), timeline.evaluate(0));
    }

    @Test void invalidSidecarReportsItsPathAndOversizedFilesAreRejected() throws Exception {
        Path motion = dir.resolve("prop.vmd");
        Path sidecar = PropTimelineFiles.sidecar(motion);
        Files.writeString(sidecar, "{");
        IOException failure = assertThrows(IOException.class, () -> PropTimelineFiles.load(motion, "fan.pmx"));
        assertTrue(failure.getMessage().contains(sidecar.toString()));
        Files.write(sidecar, new byte[1_048_577]);
        assertThrows(IOException.class, () -> PropTimelineFiles.load(motion, "fan.pmx"));
    }

    @Test void savesEditedKeysAtomicallyAndPreservesObjectIdentityAndMetadata() throws Exception {
        Path motion = dir.resolve("扇子动作.vmd");
        Files.writeString(PropTimelineFiles.sidecar(motion),FAN.replace("\"version\": 1","\"notes\":\"keep me\", \"version\": 1"));
        var document = PropTimelineFiles.open(motion,"团扇.pmx");
        var editor = new com.shiroha.mmdskin.ui.selector.timeline.PropTimelineEditor(document.timeline());
        editor.seek(480);
        editor.attachment(new PropTimeline.Attachment("頭",new PropTimeline.Offset(1,2,3,10,20,30)));
        editor.seek(900); editor.delete(com.shiroha.mmdskin.ui.selector.timeline.PropTimelineEditor.Track.VISIBILITY);
        var saved = PropTimelineFiles.save(document,editor.snapshot());
        assertTrue(saved.originalJson().contains("keep me"));
        assertTrue(saved.originalJson().contains("\"id\": \"fan\""));
        var reopened = PropTimelineFiles.open(motion,"团扇.pmx");
        assertEquals(editor.snapshot().attachments(),reopened.timeline().attachments());
        assertEquals(editor.snapshot().visibility(),reopened.timeline().visibility());
        assertEquals(editor.snapshot().initial(),reopened.timeline().initial());
        try (var paths = Files.list(dir)) { assertEquals(1,paths.count()); }
        PropTimelineFiles.save(saved,editor.snapshot());
    }

    @Test void externalModificationOrDeletionIsNeverOverwritten() throws Exception {
        Path motion = dir.resolve("扇子动作.vmd");
        Path sidecar = PropTimelineFiles.sidecar(motion);
        var absent = PropTimelineFiles.open(motion,"团扇.pmx");
        Files.writeString(sidecar,FAN);
        assertThrows(IOException.class,() -> PropTimelineFiles.save(absent,PropTimeline.EMPTY));
        assertEquals(FAN,Files.readString(sidecar));
        var opened = PropTimelineFiles.open(motion,"团扇.pmx");
        Files.delete(sidecar);
        assertThrows(IOException.class,() -> PropTimelineFiles.save(opened,PropTimeline.EMPTY));
        assertFalse(Files.exists(sidecar));
    }

    @Test void newSidecarRoundTripsInitialAndBothTracks() throws Exception {
        Path motion = dir.resolve("prop.vmd");
        var document = PropTimelineFiles.open(motion,"fan.pmx");
        var binding = new PropTimeline.Attachment("左手首",PropTimeline.Offset.IDENTITY);
        var timeline = new PropTimeline(new PropTimeline.State(false,binding),
                java.util.List.of(new PropTimeline.Key<>(30,true)),java.util.List.of(new PropTimeline.Key<>(60,binding)));
        PropTimelineFiles.save(document,timeline);
        var reopened = PropTimelineFiles.load(motion,"fan.pmx");
        assertEquals(timeline.initial(),reopened.initial());
        assertEquals(timeline.visibility(),reopened.visibility());
        assertEquals(timeline.attachments(),reopened.attachments());
    }
}
