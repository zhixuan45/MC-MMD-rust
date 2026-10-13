package com.shiroha.mmdskin.player.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FrontViewCameraSnapshotTest {
    @Test
    void onlyCurrentLocalCameraPlayerCanUseSnapshot() {
        FrontViewCameraSnapshot snapshot = new FrontViewCameraSnapshot();
        Object player = new Object();
        Object other = new Object();
        snapshot.capture(player, player, 1.75);

        assertEquals(1.75, snapshot.get(player, player, player));
        assertNull(snapshot.get(other, player, player));
        assertNull(snapshot.get(player, other, other));
        assertNull(snapshot.get(player, player, other));
    }

    @Test
    void clearDropsHeightAndScaleSetsWorldEyeHeight() {
        FrontViewCameraSnapshot snapshot = new FrontViewCameraSnapshot();
        Object player = new Object();
        snapshot.capture(player, player, FrontViewCameraSnapshot.worldHeight(64.0, new float[]{0, 16, 0}, 0.09f));

        assertEquals(65.44, snapshot.get(player, player, player), 1.0e-5);
        snapshot.clear();
        assertNull(snapshot.get(player, player, player));
    }

    @Test
    void invalidAnchorFallsBackToVanillaCameraHeight() {
        assertNull(FrontViewCameraSnapshot.worldHeight(64.0, new float[]{0, 0, 0}, 0.09f));
        assertNull(FrontViewCameraSnapshot.worldHeight(64.0, new float[]{0, Float.NaN, 0}, 0.09f));
        assertNull(FrontViewCameraSnapshot.worldHeight(64.0, new float[]{0, 16, 0}, 0.0f));
    }
}
