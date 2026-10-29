package com.shiroha.mmdskin.ui.stage;

import com.shiroha.mmdskin.stage.client.camera.StageAudioLoadTask;
import com.shiroha.mmdskin.ui.chrome.TranslucentTrayChrome;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A non-pausing progress screen for a guest whose local soundtrack is still decoding. */
public final class StageAudioLoadingScreen extends Screen {
    private final StageAudioLoadTask task;
    private final Runnable cancel;

    public StageAudioLoadingScreen(StageAudioLoadTask task, Runnable cancel) {
        super(Component.translatable("gui.mmdskin.stage.audio_loading"));
        this.task = task;
        this.cancel = cancel;
    }

    public boolean isFor(StageAudioLoadTask task) { return this.task == task; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        task.cancel();
        cancel.run();
        super.onClose();
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawProgress(graphics, width, height, task.progress());
    }

    public static void drawProgress(GuiGraphics graphics, int width, int height, float progress) {
        int w = Math.max(80, Math.min(280, width - 24));
        int x = (width - w) / 2, y = Math.max(12, height / 2 - 34);
        TranslucentTrayChrome.drawOverlay(graphics, width, height);
        TranslucentTrayChrome.drawPanel(graphics, x, y, w, 68);
        var font = Minecraft.getInstance().font;
        graphics.drawCenteredString(font, Component.translatable("gui.mmdskin.stage.audio_loading"), width / 2, y + 10, 0xffeeeeee);
        int barX=x+12, barY=y+30, barW=w-24;
        graphics.fill(barX,barY,barX+barW,barY+7,0xff253747);
        graphics.fill(barX,barY,barX+Math.round(barW*Math.max(0,Math.min(1,progress))),barY+7,0xff65b7dc);
        graphics.drawCenteredString(font, Component.translatable("gui.mmdskin.stage.audio_loading_progress", Math.round(progress*100)), width/2,y+46,0xffcbd5e1);
    }
}
