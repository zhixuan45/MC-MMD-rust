package com.shiroha.mmdskin.stage.application;

import com.shiroha.mmdskin.asset.catalog.PropTimelineCalibration;
import com.shiroha.mmdskin.asset.catalog.PropTimelineFiles;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropTimelineApplicationServiceTest {
    @TempDir Path directory;

    @Test
    void openMarksMissingReferenceAsStaleBeforeCalibrationCapture() throws Exception {
        Path motion=directory.resolve("prop.vmd");
        Files.writeString(motion,"vmd");
        Path modelDir=directory.resolve("models"),stageDir=directory.resolve("stage");
        Files.createDirectories(modelDir);
        Files.createDirectories(stageDir);
        PropTimeline timeline=timeline();
        String reference="EntityPlayer/missing.vmd";
        PropTimelineCalibration.Signature saved=signature('a','b','c','d');
        PropTimelineFiles.save(PropTimelineFiles.open(motion,"fan.pmx"),timeline,reference,saved);

        var service=new PropTimelineApplicationService(modelDir,stageDir,(motionFile,referenceFile) -> {
            throw new AssertionError("Missing reference must be reported before hashing");
        });
        var opened=service.open(motion,"fan.pmx");

        assertTrue(opened.compensationStale());
        assertTrue(service.referenceMissing(opened.referenceLabel(),opened.referenceMotion()));
        assertFalse(service.isNewDocument());
    }

    @Test
    void openTracksNewDocumentStateWithoutExposingTheCodecDocument() throws Exception {
        Path motion=directory.resolve("new.vmd");
        Files.writeString(motion,"vmd");
        Path modelDir=directory.resolve("models"),stageDir=directory.resolve("stage");
        Files.createDirectories(modelDir);
        Files.createDirectories(stageDir);
        var service=new PropTimelineApplicationService(
                modelDir,stageDir,(motionFile,referenceFile) -> signature('a','b','c','d'));

        var opened=service.open(motion,"fan.pmx");

        assertTrue(service.isOpen());
        assertTrue(service.isNewDocument());
        assertTrue(opened.timeline().boneKeys().isEmpty());
    }

    @Test
    void saveRecalculatesWhenCalibrationConflictsAndPersistsNewSource() throws Exception {
        Path motion=directory.resolve("prop.vmd"),reference=directory.resolve("reference.vmd");
        Files.writeString(motion,"vmd");
        Files.writeString(reference,"reference");
        Path modelDir=directory.resolve("models"),stageDir=directory.resolve("stage");
        Files.createDirectories(modelDir);
        Files.createDirectories(stageDir);
        PropTimeline timeline=timeline();
        PropTimelineCalibration.Signature old=signature('a','b','c','d');
        PropTimelineCalibration.Signature current=signature('e','f','a','b');
        PropTimelineFiles.Document document=PropTimelineFiles.open(motion,"fan.pmx");
        PropTimelineFiles.save(document,timeline,"EntityPlayer/reference.vmd",old);
        var service=new PropTimelineApplicationService(modelDir,stageDir,(motionFile,referenceFile) -> current);
        service.open(motion,"fan.pmx");

        var saved=service.save(timeline,"EntityPlayer/reference.vmd",reference,false,
                (frame,baseline,child,target,offset,keepWorld,placement) -> new PropTimeline.BoneBinding(
                        child,target,offset,List.of(
                        1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 2f,0f,0f,1f),
                        keepWorld,false,placement));

        assertTrue(saved.recalculated());
        assertEquals(2f,saved.timeline().boneKeys().get(0).value().compensation().get(12));
        PropTimelineFiles.Document persisted=PropTimelineFiles.open(motion,"fan.pmx");
        assertEquals(current,PropTimelineCalibration.read(persisted));
        assertEquals("EntityPlayer/reference.vmd",PropTimelineFiles.referenceMotion(persisted));
    }

    @Test
    void saveRecalculatesStaleCompensationEvenWhenCalibrationMatches() throws Exception {
        Path motion=directory.resolve("prop.vmd"),reference=directory.resolve("reference.vmd");
        Files.writeString(motion,"vmd");
        Files.writeString(reference,"reference");
        Path modelDir=directory.resolve("models"),stageDir=directory.resolve("stage");
        Files.createDirectories(modelDir);
        Files.createDirectories(stageDir);
        PropTimeline timeline=timeline();
        PropTimelineCalibration.Signature current=signature('a','b','c','d');
        PropTimelineFiles.Document document=PropTimelineFiles.open(motion,"fan.pmx");
        PropTimelineFiles.save(document,timeline,"EntityPlayer/reference.vmd",current);
        var service=new PropTimelineApplicationService(modelDir,stageDir,(motionFile,referenceFile) -> current);
        service.open(motion,"fan.pmx");

        var saved=service.save(timeline,"EntityPlayer/reference.vmd",reference,true,
                (frame,baseline,child,target,offset,keepWorld,placement) -> new PropTimeline.BoneBinding(
                        child,target,offset,PropTimeline.identityCompensation(),keepWorld,false,placement));

        assertTrue(saved.recalculated());
        PropTimelineFiles.Document persisted=PropTimelineFiles.open(motion,"fan.pmx");
        assertFalse(service.referenceMissing("EntityPlayer/reference.vmd",reference));
        assertEquals(current,PropTimelineCalibration.read(persisted));
    }

    @Test
    void playbackStripsBoneParentsWhenCalibrationConflicts() throws Exception {
        Path motion=directory.resolve("playback.vmd");
        Files.writeString(motion,"vmd");
        PropTimeline source=playbackTimeline();
        PropTimelineFiles.Document document=PropTimelineFiles.open(motion,"fan.pmx");
        PropTimelineFiles.save(document,source,"",signature('a','b','c','d'));

        var loaded=PropTimelineApplicationService.loadForPlayback(
                motion,"fan.pmx",motionFile -> signature('e','f','a','b'),List.of());

        assertTrue(loaded.timeline().boneKeys().isEmpty());
        assertEquals(source.initial().attachment(),loaded.timeline().initial().attachment());
        assertEquals(source.visibility(),loaded.timeline().visibility());
        assertEquals(source.attachments(),loaded.timeline().attachments());
        assertTrue(loaded.warning().contains("recalculate bone parenting"));
    }

    @Test
    void playbackCapturesCalibrationIoAndKeepsOtherTracks() throws Exception {
        Path motion=directory.resolve("playback.vmd");
        Files.writeString(motion,"vmd");
        PropTimeline source=playbackTimeline();
        PropTimelineFiles.Document document=PropTimelineFiles.open(motion,"fan.pmx");
        PropTimelineFiles.save(document,source,"",signature('a','b','c','d'));

        var loaded=PropTimelineApplicationService.loadForPlayback(
                motion,"fan.pmx",motionFile -> { throw new IOException("hash failed"); },List.of());

        assertTrue(loaded.timeline().boneKeys().isEmpty());
        assertEquals(source.visibility(),loaded.timeline().visibility());
        assertEquals(source.attachments(),loaded.timeline().attachments());
        assertEquals("hash failed; prop VMD remains active",loaded.warning());
    }

    private static PropTimeline timeline() {
        return new PropTimeline(PropTimeline.State.DEFAULT,List.of(),List.of(),List.of(
                new PropTimeline.Key<>(10,new PropTimeline.BoneBinding(
                        "fan","rightHand",PropTimeline.Offset.IDENTITY,
                        PropTimeline.identityCompensation(),true,false))));
    }

    private static PropTimeline playbackTimeline() {
        var initial=new PropTimeline.Attachment(
                "leftHand",new PropTimeline.Offset(1,2,3,4,5,6));
        return new PropTimeline(new PropTimeline.State(true,initial),
                List.of(new PropTimeline.Key<>(3,false)),
                List.of(new PropTimeline.Key<>(4,new PropTimeline.Attachment(
                        "rightHand",PropTimeline.Offset.IDENTITY))),
                List.of(new PropTimeline.Key<>(10,new PropTimeline.BoneBinding(
                        "fan","rightHand",PropTimeline.Offset.IDENTITY,
                        PropTimeline.identityCompensation(),true,false))));
    }

    private static PropTimelineCalibration.Signature signature(
            char actor,char prop,char motion,char reference) {
        return new PropTimelineCalibration.Signature(
                String.valueOf(actor).repeat(64),String.valueOf(prop).repeat(64),
                String.valueOf(motion).repeat(64),String.valueOf(reference).repeat(64),
                "rightHand",List.of(1f,1f,1f,0f,0f,0f,0f,0f,0f,1f));
    }
}
