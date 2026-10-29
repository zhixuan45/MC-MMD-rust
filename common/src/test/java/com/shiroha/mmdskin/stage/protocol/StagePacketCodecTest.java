package com.shiroha.mmdskin.stage.protocol;

import com.shiroha.mmdskin.stage.domain.model.StageCameraMode;
import com.shiroha.mmdskin.stage.domain.model.StageDescriptor;
import com.shiroha.mmdskin.stage.domain.model.StageInviteDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagePacketCodecTest {

    @Test
    void shouldRoundTripPacket() {
        StagePacket packet = new StagePacket(StagePacketType.PLAYBACK_START);
        packet.sessionId = "207716c5-6857-4190-a69b-f5937d90bf89";
        packet.targetPlayerId = "d8016232-9357-4339-8f82-5d11263ae4a5";
        packet.inviteDecision = StageInviteDecision.ACCEPT;
        packet.ready = true;
        packet.cameraMode = StageCameraMode.HOST_CAMERA;
        packet.frame = 15.0f;
        packet.heightOffset = 0.5f;
        packet.descriptor = new StageDescriptor("demo_pack", List.of("dance_a.vmd", "dance_b.vmd"), "camera.vmd", "music.ogg");
        packet.motionPackName = "guest_pack";
        packet.motionFiles = List.of("dance_b.vmd");
        packet.members = List.of(new StageMemberSnapshot("member-1", "Alice", "READY", "HOST_CAMERA"));

        String encoded = StagePacketCodec.encode(packet);
        StagePacket decoded = StagePacketCodec.decode(encoded);

        assertNotNull(decoded);
        assertEquals(StagePacketType.PLAYBACK_START, decoded.type);
        assertEquals(packet.sessionId, decoded.sessionId);
        assertEquals(packet.targetPlayerId, decoded.targetPlayerId);
        assertEquals(packet.inviteDecision, decoded.inviteDecision);
        assertEquals(packet.ready, decoded.ready);
        assertEquals(packet.cameraMode, decoded.cameraMode);
        assertEquals(packet.frame, decoded.frame);
        assertEquals(packet.heightOffset, decoded.heightOffset);
        assertNotNull(decoded.descriptor);
        assertEquals("demo_pack", decoded.descriptor.getPackName());
        assertEquals(List.of("dance_a.vmd", "dance_b.vmd"), decoded.descriptor.getMotionFiles());
        assertEquals("guest_pack", decoded.motionPackName);
        assertEquals(List.of("dance_b.vmd"), decoded.motionFiles);
        assertEquals("camera.vmd", decoded.descriptor.getCameraFile());
        assertEquals("music.ogg", decoded.descriptor.getAudioFile());
        assertEquals(1, decoded.members.size());
        assertEquals("Alice", decoded.members.get(0).name);
    }

    @Test
    void shouldRejectInvalidPacketPrefix() {
        assertNull(StagePacketCodec.decode("V2:abc"));
    }

    @Test
    void shouldRejectInvalidPayload() {
        assertNull(StagePacketCodec.decode("S3:not-base64"));
    }

    @Test
    void shouldRecognizeEncodedStagePacket() {
        StagePacket packet = new StagePacket(StagePacketType.FRAME_SYNC);
        String encoded = StagePacketCodec.encode(packet);
        assertTrue(StagePacketCodec.isStagePacket(encoded));
    }
    @Test
    void hostEndFrameSurvivesCopyAndTheNetworkPacket() {
        StagePacket packet=new StagePacket(StagePacketType.PLAYBACK_START);
        packet.descriptor=new StageDescriptor("pack",List.of("dance.vmd"),"camera.vmd","audio.wav");
        packet.descriptor.setEndFrame(900f);
        assertEquals(900f,packet.descriptor.copy().getEndFrame());
        StagePacket restored=StagePacketCodec.decode(StagePacketCodec.encode(packet));
        assertNotNull(restored);
        assertEquals(900f,restored.descriptor.getEndFrame());
        restored.descriptor.setEndFrame(Float.POSITIVE_INFINITY);
        org.junit.jupiter.api.Assertions.assertFalse(restored.descriptor.isValid());
    }
}
