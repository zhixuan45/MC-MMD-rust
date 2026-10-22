package com.shiroha.mmdskin.model.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelRequestKeyTest {
    @Test
    void variantsDoNotShareNativeModelCacheKeys() {
        ModelRequestKey normal = new ModelRequestKey(ModelSubjectKind.PLAYER, "uuid", "model-folder");
        ModelRequestKey held = new ModelRequestKey(ModelSubjectKind.PLAYER, "uuid", "model-folder", "with_prop.pmx");

        assertEquals("PLAYER:uuid:model-folder", normal.cacheKey());
        assertEquals("PLAYER:uuid:model-folder:pmx:with_prop.pmx", held.cacheKey());
        assertNotEquals(normal.cacheKey(), held.cacheKey());
        assertEquals("model-folder", held.modelName());
    }

    @Test
    void nullVariantMaintainsLegacyCacheIdentity() {
        ModelRequestKey request = new ModelRequestKey(ModelSubjectKind.PAPERDOLL, "uuid", "model-folder", null);
        assertEquals("PAPERDOLL:uuid:model-folder", request.cacheKey());
    }
}
