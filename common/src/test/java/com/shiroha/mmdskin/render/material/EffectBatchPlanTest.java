package com.shiroha.mmdskin.render.material;

import com.shiroha.mmdskin.render.effectpack.FxEffect;
import com.shiroha.mmdskin.render.effectpack.FxMaterialData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EffectBatchPlanTest {
    private static ByteBuffer runs(int... ids) {
        ByteBuffer result = ByteBuffer.allocate(ids.length * 20).order(ByteOrder.nativeOrder());
        for (int i = 0; i < ids.length; i++) result.putInt(ids[i]).putInt(i * 3).putInt(3).putFloat(0.5f).put((byte)1).put((byte)1).putShort((short)0);
        return result.flip();
    }
    private static ModelMaterial[] materials() { var a = new ModelMaterial(); a.tex = 10; var b = new ModelMaterial(); b.tex = 10; return new ModelMaterial[]{a,b}; }
    private static FxMaterialData effect(int id) {
        var e = new FxEffect("pack/" + id, "effect", Path.of("."), FxEffect.Kind.RAY_MATERIAL, Map.of(), 1, FxEffect.IDENTITY, List.of());
        return new FxMaterialData(e, id + 1, new int[]{20 + id * 2, 21 + id * 2, 0,0,0,0,0});
    }
    @Test void windowsPreserveInterleavedEffectsAndTriangleOrder() {
        var materials = materials();
        var plan = TranslucentBatchPlan.build(runs(0,1,0), 3, materials, i -> 10, 3, EffectBatchPlanTest::effect);
        assertNotNull(plan); assertEquals(3, plan.windows.size());
        assertArrayEquals(new int[]{0,1,2}, plan.triangleRows);
        assertEquals(List.of(0,3,6), plan.windows.stream().map(TranslucentBatchPlan.Window::firstIndex).toList());
        for (var window : plan.windows) assertEquals(3, window.textures().length);
        assertEquals(1, plan.rows.get(0).effect().selection()); assertEquals(2, plan.rows.get(1).effect().selection());
        assertEquals(1, plan.rows.get(2).effect().selection());
    }
    @Test void gpuPlanIncludesBothEffectsWithoutChangingMaterialOwners() {
        var materials = materials();
        var plan = TranslucentBatchPlan.singleWindowForCandidates(runs(0,1), 2, new int[]{0,1}, 2,
                materials, i -> 10, 8, EffectBatchPlanTest::effect);
        assertNotNull(plan); assertArrayEquals(new int[]{0,1}, plan.rowBySubmesh());
        assertEquals(5, plan.materials().windows.getFirst().textures().length);
    }
    @Test void insufficientCapacityRejectsBeforeAnySubmission() {
        var materials = materials();
        assertNull(TranslucentBatchPlan.build(runs(0), 1, materials, i -> 10, 2, EffectBatchPlanTest::effect));
        assertNull(TranslucentBatchPlan.singleWindowForCandidates(runs(0,1), 2, new int[]{0,1}, 2,
                materials, i -> 10, 4, EffectBatchPlanTest::effect));
    }
    @Test void fxChannelDataHasTheExpectedStd430StrideAndSlots() {
        var channel = new FxEffect.Channel(1, "normal.tga", new float[]{0.2f}, 2, new float[]{3,4}, 1, 0, 0);
        var e = new FxEffect("pack/a", "normal", Path.of("."), FxEffect.Kind.RAY_MATERIAL, Map.of("NORMAL", channel), 1, FxEffect.IDENTITY, List.of());
        var data = new FxMaterialData(e, 1, new int[]{0,42,0,0,0,0,0});
        ByteBuffer bytes = ByteBuffer.allocate(FxMaterialData.CHANNEL_BYTES).order(ByteOrder.nativeOrder());
        data.writeChannels(bytes, new int[]{-1,5,-1,-1,-1,-1,-1});
        assertEquals(576, bytes.position());
        assertEquals(0.2f, bytes.getFloat(48)); assertEquals(2f, bytes.getFloat(64));
        assertEquals(3f, bytes.getFloat(80)); assertEquals(4f, bytes.getFloat(84));
        assertEquals(1f, bytes.getFloat(88)); assertEquals(5f, bytes.getFloat(92));
        assertEquals(-1f, bytes.getFloat(9 * 48 + 40));
    }
    @Test void constantAlphaAndDiffuseSwitchSurviveBatchSerialization() {
        var alpha = new FxEffect.Channel(0, "", new float[]{.25f}, 0, new float[]{1, 1}, 0, 3, 0);
        var albedo = new FxEffect.Channel(0, "", new float[]{1, 1, 1}, 0, new float[]{1, 1}, 0, 0, 0);
        var e = new FxEffect("pack/a", "static", Path.of("."), FxEffect.Kind.RAY_MATERIAL,
                Map.of("ALPHA", alpha, "ALBEDO", albedo), .25f, FxEffect.IDENTITY, List.of());
        var data = new FxMaterialData(e, 1, new int[FxMaterialData.CHANNELS.length]);
        ByteBuffer bytes = ByteBuffer.allocate(FxMaterialData.CHANNEL_BYTES).order(ByteOrder.nativeOrder());
        data.writeChannels(bytes, new int[0]);
        assertTrue(data.externalAlpha());
        assertEquals(.25f, bytes.getFloat(9 * 48));
        assertEquals(0f, bytes.getFloat(9 * 48 + 40));
        assertEquals(0f, bytes.getFloat(20));
    }

    @Test void clothChannelsAndFlagsSurviveTheBatchTable() {
        var albedo = new FxEffect.Channel(0, "", new float[]{1,1,1}, 0, new float[]{1,1}, 0,0,0,1);
        var weight = new FxEffect.Channel(1, "weight.png", new float[]{0.7f}, 3, new float[]{2,3}, 5,2,1,1);
        var sheen = new FxEffect.Channel(1, "sheen.png", new float[]{0.4f,0.2f,0.1f}, 1, new float[]{4,5}, 1,0,2,1);
        var e = new FxEffect("cloth", "cloth", Path.of("."), FxEffect.Kind.RAY_MATERIAL,
                Map.of("ALBEDO", albedo, "CUSTOM_A", weight, "CUSTOM_B", sheen), 1, FxEffect.IDENTITY, List.of());
        ByteBuffer bytes = ByteBuffer.allocate(FxMaterialData.CHANNEL_BYTES).order(ByteOrder.nativeOrder());
        int[] slots = new int[12]; slots[10] = 6; slots[11] = 7;
        new FxMaterialData(e, 1, new int[12]).writeChannels(bytes, slots, 30);
        assertEquals(1f, bytes.getFloat(12)); // disable diffuse
        assertEquals(30f, bytes.getFloat(2 * 48 + 12)); // smoothness still carries PMX power
        assertEquals(0.7f, bytes.getFloat(10 * 48));
        assertEquals(1f, bytes.getFloat(10 * 48 + 12)); // invert weight
        assertEquals(5f, bytes.getFloat(10 * 48 + 20)); // shading model
        assertEquals(2f, bytes.getFloat(10 * 48 + 24)); // swizzle
        assertEquals(6f, bytes.getFloat(10 * 48 + 44));
        assertEquals(0.4f, bytes.getFloat(11 * 48));
        assertEquals(1f, bytes.getFloat(11 * 48 + 12));
        assertEquals(1f, bytes.getFloat(11 * 48 + 20)); // linear map
        assertEquals(7f, bytes.getFloat(11 * 48 + 44));
    }
}
