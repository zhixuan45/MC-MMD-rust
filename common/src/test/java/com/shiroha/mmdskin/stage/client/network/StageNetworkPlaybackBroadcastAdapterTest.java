package com.shiroha.mmdskin.stage.client.network;

import com.shiroha.mmdskin.stage.application.StageSessionService;
import com.shiroha.mmdskin.stage.application.port.StageLocalPlayerContextPort;
import com.shiroha.mmdskin.stage.application.port.StagePlaybackPreferencesPort;
import com.shiroha.mmdskin.stage.application.port.StageSessionOutboundPort;
import com.shiroha.mmdskin.stage.application.port.StageSessionReadyCommand;
import com.shiroha.mmdskin.stage.domain.model.StageDescriptor;
import com.shiroha.mmdskin.stage.domain.model.StageInviteDecision;
import com.shiroha.mmdskin.stage.protocol.StagePacket;
import com.shiroha.mmdskin.stage.protocol.StagePacketCodec;
import com.shiroha.mmdskin.stage.protocol.StagePacketType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageNetworkPlaybackBroadcastAdapterTest {
    private static final StageDescriptor DESCRIPTOR = new StageDescriptor("pack", List.of("dance.vmd"), null, null);

    private final List<StagePacket> packets = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    @AfterEach
    void clearNetworkSender() {
        StageNetworkHandler.setStageMultiSender(null);
    }

    @Test
    void noneRoleLocalSingleplayerSkipsQuietly() {
        StageSessionService session = newSession(UUID.randomUUID());
        StageNetworkHandler.setStageMultiSender(this::capture);
        StageNetworkPlaybackBroadcastAdapter adapter = newAdapter(session, false);

        adapter.sendRemoteStageStart(DESCRIPTOR);
        adapter.sendRemoteStageStop();
        adapter.sendRemoteStageStop();

        assertTrue(packets.isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void noneRoleRemoteConnectionWarnsOnceAndDoesNotBroadcast() {
        StageSessionService session = newSession(UUID.randomUUID());
        StageNetworkHandler.setStageMultiSender(this::capture);
        StageNetworkPlaybackBroadcastAdapter adapter = newAdapter(session, true);

        adapter.sendRemoteStageStart(DESCRIPTOR);
        adapter.sendRemoteStageStop();
        adapter.sendRemoteStageStart(DESCRIPTOR);

        assertTrue(packets.isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("role=NONE"));
        assertTrue(warnings.get(0).contains("context=multiplayer_no_stage_session"));
        assertTrue(warnings.get(0).contains("reason=invitation_session_required"));
        assertTrue(warnings.get(0).contains("action=skip_invalid_broadcast"));
    }

    @Test
    void hostStopUsesAnnouncedIdAfterSessionClearAndIsIdempotent() {
        UUID self = UUID.randomUUID();
        StageSessionService session = newSession(self);
        session.sendInvite(UUID.randomUUID());
        UUID announcedId = session.getSessionId();
        StageNetworkHandler.setStageMultiSender(this::capture);
        StageNetworkPlaybackBroadcastAdapter adapter = newAdapter(session, true);

        adapter.sendRemoteStageStart(DESCRIPTOR);
        session.onDisconnect();
        adapter.sendRemoteStageStop();
        adapter.sendRemoteStageStop();

        assertEquals(List.of(StagePacketType.REMOTE_STAGE_START, StagePacketType.REMOTE_STAGE_STOP), packetTypes());
        assertEquals(announcedId.toString(), packets.get(0).sessionId);
        assertEquals(announcedId.toString(), packets.get(1).sessionId);
    }

    @Test
    void memberStopUsesItsAnnouncedSessionId() {
        UUID self = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        StageSessionService session = newSession(self);
        assertTrue(session.onInviteReceived(host, sessionId));
        session.acceptInvite();
        StageNetworkHandler.setStageMultiSender(this::capture);
        StageNetworkPlaybackBroadcastAdapter adapter = newAdapter(session, true);

        adapter.sendRemoteStageStart(DESCRIPTOR);
        adapter.sendRemoteStageStop();

        assertEquals(List.of(StagePacketType.REMOTE_STAGE_START, StagePacketType.REMOTE_STAGE_STOP), packetTypes());
        assertEquals(sessionId.toString(), packets.get(0).sessionId);
        assertEquals(sessionId.toString(), packets.get(1).sessionId);
    }

    @Test
    void startingAnotherSessionStopsOldAnnouncementFirst() {
        StageSessionService session = newSession(UUID.randomUUID());
        session.sendInvite(UUID.randomUUID());
        UUID oldId = session.getSessionId();
        StageNetworkHandler.setStageMultiSender(this::capture);
        StageNetworkPlaybackBroadcastAdapter adapter = newAdapter(session, true);
        adapter.sendRemoteStageStart(DESCRIPTOR);

        session.onDisconnect();
        session.sendInvite(UUID.randomUUID());
        UUID newId = session.getSessionId();
        adapter.sendRemoteStageStart(DESCRIPTOR);

        assertEquals(List.of(StagePacketType.REMOTE_STAGE_START, StagePacketType.REMOTE_STAGE_STOP,
                StagePacketType.REMOTE_STAGE_START), packetTypes());
        assertEquals(oldId.toString(), packets.get(1).sessionId);
        assertEquals(newId.toString(), packets.get(2).sessionId);
    }

    private void capture(String encoded) {
        StagePacket packet = StagePacketCodec.decode(encoded);
        if (packet != null) {
            packets.add(packet);
        }
    }

    private List<StagePacketType> packetTypes() {
        return packets.stream().map(packet -> packet.type).toList();
    }

    private StageNetworkPlaybackBroadcastAdapter newAdapter(StageSessionService session, boolean remoteContext) {
        return new StageNetworkPlaybackBroadcastAdapter(session, () -> remoteContext, warnings::add);
    }

    private static StageSessionService newSession(UUID self) {
        StageLocalPlayerContextPort players = new StageLocalPlayerContextPort() {
            @Override
            public UUID getLocalPlayerUUID() {
                return self;
            }

            @Override
            public String resolvePlayerName(UUID uuid) {
                return uuid == null ? "Unknown" : uuid.toString();
            }
        };
        StagePlaybackPreferencesPort preferences = new StagePlaybackPreferencesPort() {
            @Override
            public boolean isCustomMotionEnabled() {
                return false;
            }

            @Override
            public List<String> getSelectedMotionFiles() {
                return List.of();
            }

            @Override
            public String getSelectedPackName() {
                return null;
            }

            @Override
            public void reset() {
            }
        };
        StageSessionOutboundPort outbound = new StageSessionOutboundPort() {
            @Override public void sendReady(StageSessionReadyCommand command) { }
            @Override public void sendStageInvite(UUID targetUUID, UUID sessionId) { }
            @Override public void sendInviteCancel(UUID targetUUID, UUID sessionId) { }
            @Override public void sendInviteResponse(UUID hostUUID, UUID sessionId, StageInviteDecision decision) { }
            @Override public void sendLeave(UUID hostUUID, UUID sessionId) { }
            @Override public void sendStageWatchEnd(UUID targetUUID, UUID sessionId) { }
            @Override public void sendSessionDissolve(UUID sessionId) { }
        };
        return new StageSessionService(players, preferences, outbound);
    }
}
