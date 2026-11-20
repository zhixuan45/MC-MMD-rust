package com.shiroha.mmdskin.player.sync;

import com.shiroha.mmdskin.asset.catalog.AnimationInfo;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VpdActionLimitsTest {
    @Test
    void poseRequestsAreRateLimitedIndependentlyOfOtherPlayersAndReset() {
        var limits = new VpdActionLimits();
        var sender = UUID.randomUUID();
        var other = UUID.randomUUID();
        String pose = AnimationInfo.VPD_ACTION_PREFIX + "CUSTOM||normal|VPD";
        assertTrue(limits.allow(sender, pose, 0));
        assertFalse(limits.allow(sender, pose, 1));
        assertTrue(limits.allow(sender, "walk", 1));
        assertTrue(limits.allow(sender, AnimationInfo.RESET_POSE_ACTION, 1));
        assertTrue(limits.allow(other, pose, 1));
        assertTrue(limits.allow(sender, pose, TimeUnit.MILLISECONDS.toNanos(250)));
    }
}
