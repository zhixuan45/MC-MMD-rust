package com.shiroha.mmdskin.asset.catalog;

import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PropTimelineCalibrationTest {
    @TempDir Path dir;

    @Test void independentStageOnlyChecksInputsUsedByItsRegistration() throws Exception {
        var config=new ModelConfigData();
        Path actor=Files.writeString(dir.resolve("actor.pmx"),"actor");
        Path prop=Files.writeString(dir.resolve("fan.pmx"),"fan");
        Path motion=Files.writeString(dir.resolve("fan.vmd"),"motion");
        Path reference=Files.writeString(dir.resolve("dance.vmd"),"dance");
        var saved=PropTimelineCalibration.capture(actor,prop,motion,reference,config);
        config.heldPmxBone="OtherHand"; config.heldPmxOffsetX=30; config.heldPmxRotZ=80;
        var current=PropTimelineCalibration.capture(actor,prop,motion,null,config);
        for(boolean placed:List.of(false,true)) {
            var binding=new PropTimeline.BoneBinding("Center","Hand",PropTimeline.Offset.IDENTITY,
                    PropTimeline.identityCompensation(),placed,false,placed?PropTimeline.Offset.IDENTITY:null);
            var timeline=new PropTimeline(PropTimeline.State.DEFAULT,List.of(),List.of(),List.of(new PropTimeline.Key<>(0,binding)));
            assertNull(PropTimelineCalibration.mismatch(saved,current,List.of(dir.resolve("unused.vmd")),timeline),
                    "Placed and position bindings must be reusable without an unrelated character reference");
            assertTrue(PropTimelineCalibration.matches(saved,current,timeline));
            var attached=new PropTimeline(new PropTimeline.State(true,new PropTimeline.Attachment("Hand",PropTimeline.Offset.IDENTITY)),
                    List.of(),List.of(),timeline.boneKeys());
            assertNotNull(PropTimelineCalibration.mismatch(saved,current,List.of(reference),attached),
                    "Explicit whole-model attachment still depends on the grip transform");
            config.heldPmxScale=2;
            var resized=PropTimelineCalibration.capture(actor,prop,motion,null,config);
            assertNotNull(PropTimelineCalibration.mismatch(saved,resized,List.of(reference),timeline));
            config.heldPmxScale=1;
        }
    }

    @Test void scopeDetectsDifferentActorPropMotionReferenceAndAttachmentInputs() throws Exception {
        Path actor=Files.writeString(dir.resolve("actor.pmx"),"actor");
        Path prop=Files.writeString(dir.resolve("fan.pmx"),"fan");
        Path motion=Files.writeString(dir.resolve("fan.vmd"),"motion");
        Path reference=Files.writeString(dir.resolve("dance.vmd"),"dance");
        Path other=Files.writeString(dir.resolve("other.vmd"),"other");
        var config=new ModelConfigData();
        var saved=PropTimelineCalibration.capture(actor,prop,motion,reference,config);
        var current=PropTimelineCalibration.capture(actor,prop,motion,null,config);
        assertNull(PropTimelineCalibration.mismatch(saved,current,List.of(reference)));
        assertNotNull(PropTimelineCalibration.mismatch(saved,current,List.of(other)));
        assertNotNull(PropTimelineCalibration.mismatch(saved,current,List.of(reference,other)),"Merged clips must not reuse a single-clip calibration");
        assertNotNull(PropTimelineCalibration.mismatch(null,current,List.of(reference)),"Old unscoped compensation must be recalibrated");
        Files.writeString(actor,"different actor");
        assertEquals("Character PMX changed",PropTimelineCalibration.mismatch(saved,
                PropTimelineCalibration.capture(actor,prop,motion,null,config),List.of(reference)));
        Files.writeString(actor,"actor"); Files.writeString(prop,"different fan");
        assertEquals("Prop PMX changed",PropTimelineCalibration.mismatch(saved,
                PropTimelineCalibration.capture(actor,prop,motion,null,config),List.of(reference)));
        Files.writeString(prop,"fan"); Files.writeString(motion,"different motion");
        assertEquals("Prop VMD changed",PropTimelineCalibration.mismatch(saved,
                PropTimelineCalibration.capture(actor,prop,motion,null,config),List.of(reference)));
        Files.writeString(motion,"motion"); config.heldPmxOffsetX=2;
        assertEquals("Attachment configuration changed",PropTimelineCalibration.mismatch(saved,
                PropTimelineCalibration.capture(actor,prop,motion,null,config),List.of(reference)));
    }

    @Test void restPoseRegistrationIsReusableAndCalibrationRoundTripsWithoutAbsolutePaths() throws Exception {
        Path actor=Files.writeString(dir.resolve("actor.pmx"),"actor");
        Path prop=Files.writeString(dir.resolve("fan.pmx"),"fan");
        Path motion=Files.writeString(dir.resolve("fan.vmd"),"motion");
        var signature=PropTimelineCalibration.capture(actor,prop,motion,null,new ModelConfigData());
        assertNull(PropTimelineCalibration.mismatch(signature,signature,List.of(dir.resolve("unrelated.vmd"))));
        var binding=new PropTimeline.BoneBinding("Root","Hand",PropTimeline.Offset.IDENTITY,PropTimeline.identityCompensation(),true,false);
        var timeline=new PropTimeline(PropTimeline.State.DEFAULT,List.of(),List.of(),List.of(new PropTimeline.Key<>(0,binding)));
        var saved=PropTimelineFiles.save(PropTimelineFiles.open(motion,"fan.pmx"),timeline,"",signature);
        assertEquals(signature,PropTimelineCalibration.read(saved));
        var legacyJson=com.google.gson.JsonParser.parseString(saved.originalJson()).getAsJsonObject();
        legacyJson.getAsJsonObject("calibration").remove("outsideParentVersion");
        assertNull(PropTimelineCalibration.read(new PropTimelineFiles.Document(motion,"fan.pmx",legacyJson.toString(),timeline)),
                "Parent-frame compensation from before the MMD semantics fix must be recalculated");
        assertFalse(saved.originalJson().contains(dir.toString()));
        var broken=new PropTimelineFiles.Document(motion,"fan.pmx",saved.originalJson().replace(signature.actor(),"invalid"),timeline);
        assertNull(PropTimelineCalibration.read(broken));
    }
}
