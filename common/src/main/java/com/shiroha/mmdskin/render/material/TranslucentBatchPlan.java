package com.shiroha.mmdskin.render.material;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Contiguous texture windows over the existing sorted EBO; never changes triangle order. */
final class TranslucentBatchPlan {
    static final int TEXTURE_SLOTS = 8;
    record Key(int material, int alphaBits, boolean bothFace) { }
    record Row(Key key, int diffuseSlot, int sphereSlot) { }
    record Window(int firstIndex, int indexCount, int[] textures) { }
    final List<Row> rows = new ArrayList<>();
    final List<Window> windows = new ArrayList<>();
    final int[] triangleRows;

    private TranslucentBatchPlan(int triangles) { triangleRows = new int[triangles]; }

    record DevicePlan(TranslucentBatchPlan materials, int[] rowBySubmesh) { }

    static DevicePlan singleWindowForCandidates(ByteBuffer source, int count, int[] owners,
                                                int candidateCount, ModelMaterial[] materials,
                                                SubMeshDrawHelper.TextureResolver resolver) {
        int[] active = new int[count];
        java.util.Arrays.fill(active, -1);
        for (int i = 0; i < candidateCount; i++) active[owners[i]] = 0;
        return singleWindow(source, count, active, materials, resolver);
    }

    /** One material row per active source submesh, independent of the device's sorted order. */
    static DevicePlan singleWindow(ByteBuffer source, int count, int[] active,
                                   ModelMaterial[] materials, SubMeshDrawHelper.TextureResolver resolver) {
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
            if (slots.size() > TEXTURE_SLOTS) return null;
            rows[s] = plan.rows.size();
            plan.rows.add(new Row(new Key(id, Float.floatToRawIntBits(source.getFloat(b + 12)), source.get(b + 17) != 0),
                    diffuseSlot, sphereSlot));
        }
        plan.addWindow(0, 0, slots);
        return new DevicePlan(plan, rows);
    }

    static TranslucentBatchPlan build(ByteBuffer runs, int count, ModelMaterial[] materials,
                                      SubMeshDrawHelper.TextureResolver resolver) {
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
            int extra = (textureSlots.containsKey(diffuse) ? 0 : 1)
                    + (sphere && material.sphereTex != diffuse && !textureSlots.containsKey(material.sphereTex) ? 1 : 0);
            if (textureSlots.size() + extra > TEXTURE_SLOTS) {
                plan.addWindow(first, start, textureSlots);
                first = start;
                textureSlots.clear();
                rowIds.clear();
            }
            int diffuseSlot = textureSlots.computeIfAbsent(diffuse, unused -> textureSlots.size());
            int sphereSlot = sphere ? textureSlots.computeIfAbsent(material.sphereTex, unused -> textureSlots.size()) : -1;
            Key key = new Key(id, Float.floatToRawIntBits(runs.getFloat(b + 12)), runs.get(b + 17) != 0);
            Integer row = rowIds.get(key);
            if (row == null) {
                row = plan.rows.size();
                rowIds.put(key, row);
                plan.rows.add(new Row(key, diffuseSlot, sphereSlot));
            }
            java.util.Arrays.fill(plan.triangleRows, start / 3, (start + n) / 3, row);
        }
        if (indices > first) plan.addWindow(first, indices, textureSlots);
        return plan;
    }

    private void addWindow(int first, int end, HashMap<Integer, Integer> slots) {
        int[] textures = new int[slots.size()];
        slots.forEach((texture, slot) -> textures[slot] = texture);
        windows.add(new Window(first, end - first, textures));
    }
}
