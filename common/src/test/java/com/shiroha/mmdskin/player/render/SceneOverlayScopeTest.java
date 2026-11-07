package com.shiroha.mmdskin.player.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SceneOverlayScopeTest {
    @Test
    void inventoryPreviewDoesNotAccessWorldSceneOrMinecraft() {
        InventoryRenderScope.enter();
        try {
            assertDoesNotThrow(() -> PlayerRenderEntrypoint.renderSceneOverlay(null, 0, null, 0));
        } finally {
            InventoryRenderScope.exit();
        }
    }

    @Test
    void paperDollPreviewDoesNotAccessWorldSceneOrMinecraft() {
        PaperDollRenderScope.enter();
        try {
            assertDoesNotThrow(() -> PlayerRenderEntrypoint.renderSceneOverlay(null, 0, null, 0));
        } finally {
            PaperDollRenderScope.exit();
        }
    }
}
