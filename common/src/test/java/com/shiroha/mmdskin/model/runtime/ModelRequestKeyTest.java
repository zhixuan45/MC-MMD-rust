package com.shiroha.mmdskin.model.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelRequestKeyTest {
    @Test
    void independentPropDoesNotReplaceCharacterCacheIdentity() {
        ModelRequestKey character = new ModelRequestKey(ModelSubjectKind.PLAYER, "uuid", "model-folder");
        ModelRequestKey prop = new ModelRequestKey(ModelSubjectKind.OTHER, "held-item/uuid",
                "model-folder", "fan.pmx");

        assertEquals("PLAYER:uuid:model-folder", character.cacheKey());
        assertEquals("OTHER:held-item/uuid:model-folder:pmx:fan.pmx", prop.cacheKey());
        assertNotEquals(character.cacheKey(), prop.cacheKey());
        assertFalse(character.isHeldItem());
        assertTrue(prop.isHeldItem());
        assertEquals("model-folder", prop.modelName());
    }

    @Test
    void normalPlayerRequestNeverUsesHeldPropVariant() {
        ModelRequestKey player = ModelRequestKey.player(null, "model-folder");
        assertEquals(ModelSubjectKind.PLAYER, player.subjectKind());
        assertEquals("", player.pmxVariant());
        assertFalse(player.isHeldItem());
    }

    @Test
    void anotherPropFileHasSeparateCacheEntry() {
        ModelRequestKey fan = new ModelRequestKey(ModelSubjectKind.OTHER, "held-item/uuid",
                "model-folder", "fan.pmx");
        ModelRequestKey sword = new ModelRequestKey(ModelSubjectKind.OTHER, "held-item/uuid",
                "model-folder", "sword.pmx");
        assertNotEquals(fan.cacheKey(), sword.cacheKey());
    }

    @Test
    void nullVariantMaintainsLegacyCacheIdentity() {
        ModelRequestKey request = new ModelRequestKey(ModelSubjectKind.PAPERDOLL, "uuid", "model-folder", null);
        assertEquals("PAPERDOLL:uuid:model-folder", request.cacheKey());
    }
}
