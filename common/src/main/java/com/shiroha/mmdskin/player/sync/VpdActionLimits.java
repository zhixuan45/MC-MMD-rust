package com.shiroha.mmdskin.player.sync;

import com.shiroha.mmdskin.asset.catalog.AnimationInfo;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** VPD loading and pose reset have separate limits so a pose can be cleared immediately. */
public final class VpdActionLimits {
    private static final int MAX_SENDERS = 1024;
    private static final long INTERVAL = TimeUnit.MILLISECONDS.toNanos(250);
    private final LinkedHashMap<UUID, Long> poses = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<UUID, Long> resets = new LinkedHashMap<>(16, .75f, true);

    public static boolean isVpd(String action) {
        return action != null && action.startsWith(AnimationInfo.VPD_ACTION_PREFIX);
    }

    public boolean allow(UUID sender, String action) {
        return allow(sender, action, System.nanoTime());
    }

    synchronized boolean allow(UUID sender, String action, long now) {
        if (!isVpd(action)) return true;
        if (action.length() > 1024) return false;
        var recent = AnimationInfo.RESET_POSE_ACTION.equals(action) ? resets : poses;
        Long previous = recent.get(sender);
        if (previous != null && now - previous < INTERVAL) return false;
        recent.put(sender, now);
        if (recent.size() > MAX_SENDERS) recent.remove(recent.keySet().iterator().next());
        return true;
    }
}
