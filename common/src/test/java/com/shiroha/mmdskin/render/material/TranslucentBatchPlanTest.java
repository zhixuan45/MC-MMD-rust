package com.shiroha.mmdskin.render.material;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TranslucentBatchPlanTest {
    private static ByteBuffer runs(int... ids) {
        ByteBuffer result = ByteBuffer.allocate(ids.length * 20).order(ByteOrder.nativeOrder());
        for (int i = 0; i < ids.length; i++) {
            int b = i * 20;
            result.putInt(b, ids[i]).putInt(b + 4, i * 3).putInt(b + 8, 3);
            result.putFloat(b + 12, .5f).put(b + 16, (byte) 1);
        }
        return result;
    }
    private static ModelMaterial[] materials(int count) {
        ModelMaterial[] result = new ModelMaterial[count];
        for (int i = 0; i < count; i++) result[i] = new ModelMaterial();
        return result;
    }
    @Test void alternatingMaterialsKeepTriangleOrderAndShareRows() {
        var plan = TranslucentBatchPlan.build(runs(0, 1, 0, 1, 0), 5, materials(2), i -> i + 100);
        assertArrayEquals(new int[]{0, 1, 0, 1, 0}, plan.triangleRows);
        assertEquals(2, plan.rows.size());
        assertEquals(1, plan.windows.size());
        assertEquals(15, plan.windows.getFirst().indexCount());
    }
    @Test void overflowCreatesContiguousWindowsAndRemapsSlots() {
        int[] ids = {0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 8};
        var plan = TranslucentBatchPlan.build(runs(ids), ids.length, materials(9), i -> i + 100);
        assertEquals(2, plan.windows.size());
        assertEquals(0, plan.windows.get(0).firstIndex());
        assertEquals(24, plan.windows.get(0).indexCount());
        assertEquals(24, plan.windows.get(1).firstIndex());
        assertEquals(9, plan.windows.get(1).indexCount());
        for (int i = 0; i < ids.length; i++) {
            var row = plan.rows.get(plan.triangleRows[i]);
            var window = plan.windows.get(i < 8 ? 0 : 1);
            assertEquals(ids[i], row.key().material());
            assertEquals(ids[i] + 100, window.textures()[row.diffuseSlot()]);
        }
    }
    @Test void constrainedCapacitySplitsOnlyAtContinuousRunBoundaries() {
        int[] ids = {0, 1, 2, 0, 2};
        var plan = TranslucentBatchPlan.build(runs(ids), ids.length, materials(3), i -> i + 100, 2);
        assertEquals(2, plan.windows.size());
        assertEquals(0, plan.windows.get(0).firstIndex());
        assertEquals(6, plan.windows.get(0).indexCount());
        assertEquals(6, plan.windows.get(1).firstIndex());
        assertEquals(9, plan.windows.get(1).indexCount());
        for (int i = 0; i < ids.length; i++) {
            assertEquals(ids[i], plan.rows.get(plan.triangleRows[i]).key().material());
        }
    }

    @Test void oneSphereMaterialCannotExceedTheAvailableWindow() {
        var mats = materials(1);
        mats[0].sphereMode = 1;
        mats[0].sphereTex = 200;
        assertNull(TranslucentBatchPlan.build(runs(0), 1, mats, i -> i + 100, 1));
    }
    @Test void sphereTexturesConsumeSlotsAndSameTextureIsDeduplicated() {
        var mats = materials(6);
        for (int i = 0; i < mats.length; i++) { mats[i].sphereMode = 2; mats[i].sphereTex = 200 + i; }
        mats[0].sphereTex = 100;
        var plan = TranslucentBatchPlan.build(runs(0, 1, 2, 3, 4, 5), 6, mats, i -> i + 100);
        assertEquals(2, plan.windows.size());
        assertEquals(12, plan.windows.get(0).indexCount());
        assertEquals(plan.rows.getFirst().diffuseSlot(), plan.rows.getFirst().sphereSlot());
    }
    @Test void sameMaterialWithDifferentAlphaOrCullingUsesDifferentRows() {
        var runs = runs(0, 0, 0);
        runs.putFloat(32, .2f).put(57, (byte) 1);
        var plan = TranslucentBatchPlan.build(runs, 3, materials(1), i -> 100);
        assertArrayEquals(new int[]{0, 1, 2}, plan.triangleRows);
        assertTrue(plan.rows.get(2).key().bothFace());
    }
    @Test void emptyAndInvalidRuns() {
        assertTrue(TranslucentBatchPlan.build(runs(), 0, materials(1), i -> 1).windows.isEmpty());
        var invalid = runs(0); invalid.putInt(4, 3);
        assertThrows(IllegalArgumentException.class,
                () -> TranslucentBatchPlan.build(invalid, 1, materials(1), i -> 1));
    }

    @Test void unusedTransparentTexturesDoNotForceDeviceFallback() {
        var source = runs(0, 1, 2, 3, 4, 5, 6, 7, 8);
        int[] active = new int[9];
        assertNull(TranslucentBatchPlan.singleWindow(source, 9, active, materials(9), i -> i + 100));
        var plan = TranslucentBatchPlan.singleWindowForCandidates(source, 9,
                new int[]{8, 0, 8, 1}, 4, materials(9), i -> i + 100);
        assertNotNull(plan);
        assertEquals(3, plan.materials().rows.size());
        assertEquals(-1, plan.rowBySubmesh()[2]);
        assertEquals(2, plan.rowBySubmesh()[8]);
        assertArrayEquals(new int[]{100, 101, 108}, plan.materials().windows.getFirst().textures());
    }
}
