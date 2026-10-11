package com.shiroha.mmdskin.stage.client.network;

import com.shiroha.mmdskin.stage.application.StageSessionService;
import com.shiroha.mmdskin.stage.client.camera.port.StageCameraBroadcastPort;
import com.shiroha.mmdskin.stage.client.playback.StagePlaybackStartRequest;
import com.shiroha.mmdskin.stage.client.playback.port.StagePlaybackBroadcastPort;
import com.shiroha.mmdskin.stage.client.playback.port.StagePlaybackWatchRequest;
import com.shiroha.mmdskin.stage.domain.model.StageDescriptor;
import com.shiroha.mmdskin.stage.domain.model.StageRole;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 文件职责：把舞台播放广播请求适配到网络层。 */
public final class StageNetworkPlaybackBroadcastAdapter implements StagePlaybackBroadcastPort, StageCameraBroadcastPort {
    private static final Logger LOGGER = LogManager.getLogger(StageNetworkPlaybackBroadcastAdapter.class);

    private final StageSessionService sessionService;
    private final BooleanSupplier remoteMultiplayerContext;
    private final Consumer<String> warningSink;
    private UUID announcedSessionId;
    private boolean warnedSessionIssue;

    public StageNetworkPlaybackBroadcastAdapter(StageSessionService sessionService) {
        this(sessionService, StageNetworkPlaybackBroadcastAdapter::isRemoteOrPublishedLan, LOGGER::warn);
    }

    StageNetworkPlaybackBroadcastAdapter(StageSessionService sessionService,
                                         BooleanSupplier remoteMultiplayerContext,
                                         Consumer<String> warningSink) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.remoteMultiplayerContext = Objects.requireNonNull(remoteMultiplayerContext, "remoteMultiplayerContext");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    @Override
    public void sendStageWatch(StagePlaybackWatchRequest request) {
        if (request == null) {
            return;
        }
        StageNetworkHandler.sendStageWatch(
                request.targetUUID(),
                request.sessionId(),
                request.descriptor(),
                request.heightOffset(),
                request.startFrame()
        );
    }

    @Override
    public synchronized void sendRemoteStageStart(StageDescriptor descriptor) {
        StageRole role = sessionService.getLocalRole();
        if (role == StageRole.NONE) {
            if (remoteMultiplayerContext.getAsBoolean()) {
                warnSessionIssue(role, "multiplayer_no_stage_session", "invitation_session_required");
            } else {
                warnedSessionIssue = false;
            }
            return;
        }

        UUID sessionId = sessionService.getSessionId();
        if (sessionId == null) {
            warnSessionIssue(role, "active_session_missing_id", "session_id_missing");
            return;
        }

        warnedSessionIssue = false;
        if (descriptor == null || !descriptor.isValid()) {
            StageNetworkHandler.sendRemoteStageStart(sessionId, descriptor);
            return;
        }

        if (announcedSessionId != null && !announcedSessionId.equals(sessionId)) {
            StageNetworkHandler.sendRemoteStageStop(announcedSessionId);
        }
        StageNetworkHandler.sendRemoteStageStart(sessionId, descriptor);
        announcedSessionId = sessionId;
    }

    @Override
    public synchronized void sendRemoteStageStop() {
        if (announcedSessionId != null) {
            UUID sessionId = announcedSessionId;
            announcedSessionId = null;
            StageNetworkHandler.sendRemoteStageStop(sessionId);
            return;
        }

        StageRole role = sessionService.getLocalRole();
        if (role == StageRole.NONE) {
            if (remoteMultiplayerContext.getAsBoolean()) {
                warnSessionIssue(role, "multiplayer_no_stage_session", "invitation_session_required");
            } else {
                warnedSessionIssue = false;
            }
        } else if (sessionService.getSessionId() == null) {
            warnSessionIssue(role, "active_session_missing_id", "session_id_missing");
        }
    }

    private void warnSessionIssue(StageRole role, String context, String reason) {
        if (!warnedSessionIssue) {
            warnedSessionIssue = true;
            warningSink.accept("[多人舞台] remote broadcast skipped role=" + role
                    + " context=" + context + " reason=" + reason
                    + " action=skip_invalid_broadcast");
        }
    }

    private static boolean isRemoteOrPublishedLan() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.hasSingleplayerServer()) {
            var integratedServer = minecraft.getSingleplayerServer();
            return integratedServer != null && integratedServer.isPublished();
        }
        return minecraft.getConnection() != null;
    }

    @Override
    public void sendFrameSync(UUID sessionId, float frame) {
        StageNetworkHandler.sendFrameSync(sessionId, frame);
    }

    @Override
    public void sendLeave(UUID hostUUID, UUID sessionId) {
        StageNetworkHandler.sendLeave(hostUUID, sessionId);
    }
}
