package com.shiroha.mmdskin.player.runtime;

import com.shiroha.mmdskin.asset.catalog.AnimationInfo;
import com.shiroha.mmdskin.expression.ExpressionApplicationService;
import com.shiroha.mmdskin.expression.ExpressionApplicationService.PreparedVpd;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.player.model.PlayerModelResolver;
import com.shiroha.mmdskin.player.sync.VpdActionLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Resolves and parses remote poses on one worker; only the client thread changes models. */
public final class RemoteVpdActions {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_PENDING = 8;
    private static final int MAX_INDEXES = 16;
    private static final long INDEX_TTL = TimeUnit.SECONDS.toNanos(5);
    private static final VpdActionLimits LIMITS = new VpdActionLimits();
    private static final Map<UUID, Request> PENDING = new HashMap<>();
    private static final LinkedHashMap<String, Index> INDEXES = new LinkedHashMap<>(16, .75f, true);
    private static final Executor WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mmd-vpd-action-loader");
        thread.setDaemon(true);
        return thread;
    });
    private static int inFlight;

    private record Request(Player player, ManagedModel model, String action, long stageRevision) { }
    private record Index(long createdAt, Map<String, String> paths) { }

    private RemoteVpdActions() { }

    public static void receive(Player player, String action) {
        UUID sender = player.getUUID();
        if (!LIMITS.allow(sender, action)) return;
        if (!VpdActionLimits.isVpd(action) || AnimationInfo.RESET_POSE_ACTION.equals(action)) {
            cancel(sender);
            MmdSkinRendererPlayerHelper.CustomAnim(player, action);
            return;
        }
        if (inFlight >= MAX_PENDING) return;
        var resolved = PlayerModelResolver.resolve(player);
        if (resolved == null) return;
        ManagedModel model = resolved.model();
        Request request = new Request(player, model, action, model.entityState().stageActionRevision);
        Request previous = PENDING.get(sender);
        if (request.equals(previous)) return;
        PENDING.put(sender, request);
        inFlight++;
        Minecraft client = Minecraft.getInstance();
        String modelName = model.requestKey().modelName();
        WORKER.execute(() -> {
            PreparedVpd pose = null;
            try {
                String path = index(modelName).get(action);
                if (path != null) pose = ExpressionApplicationService.prepareVpd(path);
            } catch (RuntimeException | LinkageError error) {
                LOGGER.debug("Could not prepare remote VPD", error);
            } finally {
                PreparedVpd prepared = pose;
                client.execute(() -> finish(client, sender, request, prepared));
            }
        });
    }

    public static void cancel(UUID sender) {
        PENDING.remove(sender);
    }

    private static void finish(Minecraft client, UUID sender, Request request, PreparedVpd pose) {
        try (pose) {
            if (PENDING.get(sender) != request) return;
            PENDING.remove(sender);
            if (pose == null || client.level == null
                    || client.level.getPlayerByUUID(sender) != request.player()) return;
            var current = PlayerModelResolver.resolve(request.player());
            if (current != null && current.model() == request.model()
                    && request.stageRevision() == current.model().entityState().stageActionRevision) {
                VpdPosePlayback.applyPrepared(current.model(), pose, current.playerName());
            }
        } finally {
            inFlight--;
        }
    }

    private static Map<String, String> index(String modelName) {
        long now = System.nanoTime();
        Index current = INDEXES.get(modelName);
        if (current != null && now - current.createdAt() < INDEX_TTL) return current.paths();
        Map<String, String> paths = new HashMap<>();
        for (AnimationInfo pose : AnimationInfo.scanPoseActions(modelName)) {
            paths.putIfAbsent(pose.getActionId(), pose.getFilePath());
        }
        INDEXES.put(modelName, new Index(now, paths));
        if (INDEXES.size() > MAX_INDEXES) INDEXES.pollFirstEntry();
        return paths;
    }
}
