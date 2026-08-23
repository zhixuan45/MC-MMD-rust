package com.shiroha.mmdskin.mixin.neoforge;

import com.shiroha.mmdskin.ui.paperdoll.PaperDollRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 文件职责：在 NeoForge 端 Screen 界面渲染完成后注入纸娃娃渲染钩子。
 */
@Mixin(Screen.class)
public abstract class NeoForgeScreenMixin {

    @Inject(method = "render", at = @At("RETURN"))
    private void mmdskin$onRenderScreen(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        PaperDollRenderer.renderInScreen(guiGraphics, (Screen) (Object) this, partialTick);
    }
}
