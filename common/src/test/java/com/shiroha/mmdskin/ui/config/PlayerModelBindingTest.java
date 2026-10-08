package com.shiroha.mmdskin.ui.config;

import com.shiroha.mmdskin.config.UIConstants;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerModelBindingTest {
    private static final UUID PLAYER = UUID.fromString("a86a14e8-3e29-4ec0-a7b4-7ca58ab3deab");

    @Test
    void uuidBindingSurvivesRenameAndTakesPriorityOverName() {
        Map<String, String> bindings = Map.of(PLAYER.toString(), "uuid-model", "Renamed", "name-model");

        assertEquals("uuid-model", ModelSelectorConfig.resolvePlayerModel(bindings, PLAYER, "Renamed"));
    }

    @Test
    void missingOrDefaultUuidBindingFallsBackToName() {
        for (String value : new String[] {"", " ", UIConstants.DEFAULT_MODEL_NAME}) {
            Map<String, String> bindings = Map.of(PLAYER.toString(), value, "Player", "name-model");
            assertEquals("name-model", ModelSelectorConfig.resolvePlayerModel(bindings, PLAYER, "Player"));
        }
        assertEquals("name-model", ModelSelectorConfig.resolvePlayerModel(Map.of("Player", "name-model"), PLAYER, "Player"));
    }

    @Test
    void offlinePlayersCanBeBoundByNameOrUuid() {
        assertEquals("name-model", ModelSelectorConfig.resolvePlayerModel(Map.of("Offline", "name-model"), null, "Offline"));
        assertEquals("uuid-model", ModelSelectorConfig.resolvePlayerModel(Map.of(PLAYER.toString(), "uuid-model"), PLAYER, null));
    }

    @Test
    void unresolvedOrEmptyBindingsUseVanillaRenderer() {
        assertEquals(UIConstants.DEFAULT_MODEL_NAME, ModelSelectorConfig.resolvePlayerModel(null, PLAYER, "Player"));
        assertEquals(UIConstants.DEFAULT_MODEL_NAME, ModelSelectorConfig.resolvePlayerModel(Map.of(), null, null));
        assertEquals(UIConstants.DEFAULT_MODEL_NAME, ModelSelectorConfig.resolvePlayerModel(Map.of("Player", " "), PLAYER, "Player"));
    }
}
