package com.shiroha.mmdskin.player.render;

import com.shiroha.mmdskin.render.scene.RenderScene;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryScreenEntrypointCompatibilityTest {
    @Test
    void forge1201InventoryPreviewEntrypointMatchesMixinDescriptor() {
        assertDoesNotThrow(() -> InventoryScreen.class.getDeclaredMethod(
                "renderEntityInInventory",
                GuiGraphics.class,
                int.class,
                int.class,
                int.class,
                Quaternionf.class,
                Quaternionf.class,
                LivingEntity.class));
    }

    @Test
    void vanillaMouseFollowerDelegatesThroughTheScopedEntrypointSignature() {
        assertDoesNotThrow(() -> InventoryScreen.class.getDeclaredMethod(
                "renderEntityInInventoryFollowsMouse",
                GuiGraphics.class,
                int.class,
                int.class,
                int.class,
                float.class,
                float.class,
                LivingEntity.class));
    }

    @Test
    void playerPreviewScenesUseTheGuiModelViewPath() {
        assertTrue(RenderScene.INVENTORY.isInventoryScene());
        assertTrue(RenderScene.PAPERDOLL.isInventoryScene());
        assertFalse(RenderScene.WORLD.isInventoryScene());
    }
}
