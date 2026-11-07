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
        assertFalse(saved.originalJson().contains(dir.toString()));
        var broken=new PropTimelineFiles.Document(motion,"fan.pmx",saved.originalJson().replace(signature.actor(),"invalid"),timeline);
        assertNull(PropTimelineCalibration.read(broken));
    }
}
