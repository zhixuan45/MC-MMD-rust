package com.shiroha.mmdskin.render.material;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import com.shiroha.mmdskin.render.effectpack.FxMaterialData;
import java.util.function.IntFunction;

/** Contiguous texture windows over the existing sorted EBO; never changes triangle order. */
final class TranslucentBatchPlan {
    static final int TEXTURE_SLOTS = 8;
    record Key(int material, int alphaBits, boolean bothFace) { }
    record Row(Key key, int diffuseSlot, int sphereSlot, FxMaterialData effect, int[] effectSlots) { }
    record Window(int firstIndex, int indexCount, int[] textures) { }
    final List<Row> rows = new ArrayList<>();
    final List<Window> windows = new ArrayList<>();
    final int[] triangleRows;

    private TranslucentBatchPlan(int triangles) { triangleRows = new int[triangles]; }

    record DevicePlan(TranslucentBatchPlan materials, int[] rowBySubmesh) { }

    static DevicePlan singleWindowForCandidates(ByteBuffer source, int count, int[] owners,
                                                int candidateCount, ModelMaterial[] materials,
                                                SubMeshDrawHelper.TextureResolver resolver) {
        return singleWindowForCandidates(source, count, owners, candidateCount, materials, resolver, TEXTURE_SLOTS);
    }

    static DevicePlan singleWindowForCandidates(ByteBuffer source, int count, int[] owners,
                                                int candidateCount, ModelMaterial[] materials,
                                                SubMeshDrawHelper.TextureResolver resolver, int capacity) {
        return singleWindowForCandidates(source, count, owners, candidateCount, materials, resolver, capacity, id -> FxMaterialData.NONE);
    }

    static DevicePlan singleWindowForCandidates(ByteBuffer source, int count, int[] owners,
                                                int candidateCount, ModelMaterial[] materials,
                                                SubMeshDrawHelper.TextureResolver resolver, int capacity,
                                                IntFunction<FxMaterialData> effects) {
        int[] active = new int[count];
        java.util.Arrays.fill(active, -1);
        for (int i = 0; i < candidateCount; i++) active[owners[i]] = 0;
        return singleWindow(source, count, active, materials, resolver, capacity, effects);
    }

    /** One material row per active source submesh, independent of the device's sorted order. */
    static DevicePlan singleWindow(ByteBuffer source, int count, int[] active,
                                   ModelMaterial[] materials, SubMeshDrawHelper.TextureResolver resolver) {
        return singleWindow(source, count, active, materials, resolver, TEXTURE_SLOTS);
    }

    static DevicePlan singleWindow(ByteBuffer source, int count, int[] active,
                                   ModelMaterial[] materials, SubMeshDrawHelper.TextureResolver resolver,
                                   int capacity) {
        return singleWindow(source, count, active, materials, resolver, capacity, id -> FxMaterialData.NONE);
    }

    private static DevicePlan singleWindow(ByteBuffer source, int count, int[] active,
                                   ModelMaterial[] materials, SubMeshDrawHelper.TextureResolver resolver,
                                   int capacity, IntFunction<FxMaterialData> effects) {
        if (capacity < 1 || capacity > TEXTURE_SLOTS) return null;
        var plan = new TranslucentBatchPlan(0);
        var slots = new HashMap<Integer, Integer>();
        int[] rows = new int[count];
        java.util.Arrays.fill(rows, -1);
        for (int s = 0; s < count; s++) {
            if (active[s] < 0) continue;
            int b = s * 20, id = source.getInt(b);
            ModelMaterial material = materials[id];
            int diffuse = resolver.resolve(id);
            boolean sphere = material.sphereTex > 0 && (material.sphereMode == 1 || material.sphereMode == 2);
            int diffuseSlot = slots.computeIfAbsent(diffuse, unused -> slots.size());
            int sphereSlot = sphere ? slots.computeIfAbsent(material.sphereTex, unused -> slots.size()) : -1;
            FxMaterialData effect = effects.apply(id);
            int[] effectSlots = effectSlots(effect, slots);
            if (slots.size() > capacity) return null;
            rows[s] = plan.rows.size();
            plan.rows.add(new Row(new Key(id, Float.floatToRawIntBits(source.getFloat(b + 12)), source.get(b + 17) != 0),
                    diffuseSlot, sphereSlot, effect, effectSlots));
        }
        plan.addWindow(0, 0, slots);
        return new DevicePlan(plan, rows);
    }

    static TranslucentBatchPlan build(ByteBuffer runs, int count, ModelMaterial[] materials,
                                      SubMeshDrawHelper.TextureResolver resolver) {
        return build(runs, count, materials, resolver, TEXTURE_SLOTS);
    }

    static TranslucentBatchPlan build(ByteBuffer runs, int count, ModelMaterial[] materials,
                                      SubMeshDrawHelper.TextureResolver resolver, int capacity) {
        return build(runs, count, materials, resolver, capacity, id -> FxMaterialData.NONE);
    }

    static TranslucentBatchPlan build(ByteBuffer runs, int count, ModelMaterial[] materials,
                                      SubMeshDrawHelper.TextureResolver resolver, int capacity,
                                      IntFunction<FxMaterialData> effects) {
        if (capacity < 1 || capacity > TEXTURE_SLOTS) throw new IllegalArgumentException("Invalid texture capacity");
        int indices = 0;
        for (int i = 0; i < count; i++) {
            int b = i * 20, n = runs.getInt(b + 8), id = runs.getInt(b);
            if (runs.getInt(b + 4) != indices || n <= 0 || n % 3 != 0
                    || id < 0 || id >= materials.length || runs.get(b + 16) == 0)
                throw new IllegalArgumentException("Invalid sorted translucent run");
            indices = Math.addExact(indices, n);
        }
        TranslucentBatchPlan plan = new TranslucentBatchPlan(indices / 3);
        var textureSlots = new HashMap<Integer, Integer>();
        var rowIds = new HashMap<Key, Integer>();
        int first = 0;
        // Resolve a diffuse texture only once per material, rather than once per sorted run.
        var diffuseTextures = new HashMap<Integer, Integer>();
        for (int i = 0; i < count; i++) {
            int b = i * 20, id = runs.getInt(b), start = runs.getInt(b + 4), n = runs.getInt(b + 8);
            ModelMaterial material = materials[id];
            int diffuse = diffuseTextures.computeIfAbsent(id, resolver::resolve);
            boolean sphere = material.sphereTex > 0 && (material.sphereMode == 1 || material.sphereMode == 2);
            FxMaterialData effect = effects.apply(id);
            var required = new java.util.HashSet<Integer>();
            required.add(diffuse);
            if (sphere) required.add(material.sphereTex);
            for (int texture : effect.requiredTextures()) required.add(texture);
            int extra = (int)required.stream().filter(texture -> !textureSlots.containsKey(texture)).count();
            if (required.size() > capacity) return null;
            if (textureSlots.size() + extra > capacity) {
                plan.addWindow(first, start, textureSlots);
                first = start;
                textureSlots.clear();
                rowIds.clear();
            }
            int diffuseSlot = textureSlots.computeIfAbsent(diffuse, unused -> textureSlots.size());
            int sphereSlot = sphere ? textureSlots.computeIfAbsent(material.sphereTex, unused -> textureSlots.size()) : -1;
            int[] effectSlots = effectSlots(effect, textureSlots);
            Key key = new Key(id, Float.floatToRawIntBits(runs.getFloat(b + 12)), runs.get(b + 17) != 0);
            Integer row = rowIds.get(key);
            if (row == null) {
                row = plan.rows.size();
                rowIds.put(key, row);
                plan.rows.add(new Row(key, diffuseSlot, sphereSlot, effect, effectSlots));
            }
            java.util.Arrays.fill(plan.triangleRows, start / 3, (start + n) / 3, row);
        }
        if (indices > first) plan.addWindow(first, indices, textureSlots);
        return plan;
    }

    private static int[] effectSlots(FxMaterialData effect, HashMap<Integer, Integer> slots) {
        int[] result = new int[com.shiroha.mmdskin.render.effectpack.FxMaterialData.CHANNELS.length];
        java.util.Arrays.fill(result, -1);
        for (int i = 0; i < Math.min(result.length, effect.textures().length); i++) if (effect.textures()[i] > 0)
            result[i] = slots.computeIfAbsent(effect.textures()[i], unused -> slots.size());
        return result;
    }

    private void addWindow(int first, int end, HashMap<Integer, Integer> slots) {
        int[] textures = new int[slots.size()];
        slots.forEach((texture, slot) -> textures[slot] = texture);
        windows.add(new Window(first, end - first, textures));
    }
}
