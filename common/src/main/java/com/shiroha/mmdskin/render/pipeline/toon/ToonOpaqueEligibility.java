package com.shiroha.mmdskin.render.pipeline.toon;

import com.shiroha.mmdskin.render.material.MaterialRenderState;
import com.shiroha.mmdskin.render.material.ModelMaterial;
import com.shiroha.mmdskin.render.material.SubMeshDrawHelper;

import java.nio.ByteBuffer;

/** Prototype capability check. Reject the whole model instead of dropping its translucent pass. */
public final class ToonOpaqueEligibility {
    private ToonOpaqueEligibility() {}

    public static String rejection(ByteBuffer meshes, int count, ModelMaterial[] materials,
                                   ByteBuffer morphs, SubMeshDrawHelper.AlphaResolver alpha) {
        var state = new MaterialRenderState();
        for (int i = 0; i < count; i++) {
            int base = i * 20, id = meshes.getInt(base);
            if (meshes.get(base + 16) == 0 || meshes.getInt(base + 8) <= 0) continue;
            if (id < 0 || id >= materials.length || materials[id] == null) return "MISSING_MATERIAL";
            var material = materials[id];
            state.resolve(material, morphs, id, alpha.resolve(id, meshes.getFloat(base + 12)));
            if (!Float.isFinite(state.alpha)) return "INVALID_ALPHA";
            if (state.alpha < 0.001f) continue;
            if (state.alpha < MaterialRenderState.OPAQUE_ALPHA) return "TRANSLUCENT_MATERIAL";
            // Binary cutouts also produce fractional coverage under linear filtering.
            if (material.hasTranslucentPixels || material.hasAlpha
                    && (material.alphaCoverage == null || !material.alphaCoverage.isFullyOpaque()))
                return "TEXTURE_ALPHA_REQUIRES_SHARED_TRANSPARENCY";
        }
        return null;
    }
}
