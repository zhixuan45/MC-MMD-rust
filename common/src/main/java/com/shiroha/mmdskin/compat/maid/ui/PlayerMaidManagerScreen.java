/** 文件职责：展示主人已经由当前已加载女仆实体验证的女仆目录。 */
package com.shiroha.mmdskin.compat.maid.ui;

import com.shiroha.mmdskin.compat.maid.ui.VerifiedMaidDirectory.MaidRecord;
import com.shiroha.mmdskin.ui.chrome.TranslucentTrayChrome;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;

import java.util.ArrayList;
import java.util.List;

public final class PlayerMaidManagerScreen extends Screen {
    private final Screen parent;
    private final List<MaidRecord> entries = new ArrayList<>();
    private Layout layout = Layout.empty();
    private int hovered = -1;
    private boolean closePending;
    private double targetScroll;
    private double animatedScroll;

    public PlayerMaidManagerScreen(Screen parent) {
        super(Component.translatable("gui.mmdskin.maid_manager.title"));
        this.parent = parent;
        refreshEntries();
    }

    @Override
    protected void init() {
        super.init();
        refreshEntries();
        updateLayout();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateLayout();
        animatedScroll += (targetScroll - animatedScroll) * 0.24;
        if (Math.abs(targetScroll - animatedScroll) < 0.25) animatedScroll = targetScroll;
        hovered = indexAt(mouseX, mouseY);
        TranslucentTrayChrome.drawOverlay(graphics, width, height);
        TranslucentTrayChrome.drawPanel(graphics, layout.panel.x, layout.panel.y, layout.panel.w, layout.panel.h);
        graphics.drawString(font, fit(title.getString(), Math.max(0, layout.panel.w - 18)), layout.panel.x + 9, layout.panel.y + 9,
                TranslucentTrayChrome.TITLE_TEXT, false);
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font, fit(Component.translatable("gui.mmdskin.maid_manager.empty").getString(), Math.max(0, layout.list.w - 8)),
                    layout.list.centerX(), layout.list.centerY(), TranslucentTrayChrome.BODY_TEXT);
        } else {
            TranslucentTrayChrome.fillListArea(graphics, layout.list.x, layout.list.y, layout.list.w, layout.list.h);
            graphics.enableScissor(layout.list.x, layout.list.y, layout.list.x + layout.list.w, layout.list.y + layout.list.h);
            int y = (int) Math.round(layout.list.y - animatedScroll);
            for (int i = 0; i < entries.size(); i++) {
                MaidRecord entry = entries.get(i);
                int rowY = y;
                if (rowY + layout.rowHeight >= layout.list.y && rowY <= layout.list.y + layout.list.h) {
                    graphics.fill(layout.list.x + 3, rowY + 1, layout.list.x + layout.list.w - 3,
                            rowY + layout.rowHeight - 1,
                            TranslucentTrayChrome.cardBackground(false, i == hovered));
                    String name = fit(entry.name(), Math.max(0, layout.list.w - 16));
                    graphics.drawString(font, name, layout.list.x + 9, rowY + 4, TranslucentTrayChrome.BODY_TEXT, false);
                    String model = entry.currentModel();
                    String status = entry.entityId() > 0
                            ? Component.translatable("gui.mmdskin.maid_manager.loaded").getString()
                            : Component.translatable("gui.mmdskin.maid_manager.last_seen").getString();
                    String detail = model == null || model.isBlank()
                            ? status : model + " · " + status;
                    graphics.drawString(font, fit(detail, Math.max(0, layout.list.w - 16)),
                            layout.list.x + 9, rowY + 13, TranslucentTrayChrome.DETAIL_TEXT, false);
                }
                y += layout.rowHeight;
            }
            graphics.disableScissor();
        }
        drawButton(graphics, layout.refresh, Component.translatable("gui.mmdskin.refresh").getString(), mouseOver(layout.refresh, mouseX, mouseY));
        drawButton(graphics, layout.close, Component.translatable("gui.done").getString(), mouseOver(layout.close, mouseX, mouseY));
        graphics.drawString(font, fit(Component.translatable("gui.mmdskin.maid_manager.scope").getString(),
                        Math.max(0, layout.panel.w - 18)),
                layout.panel.x + 9, layout.panel.y + layout.panel.h - 35, TranslucentTrayChrome.DETAIL_TEXT, false);
        if (closePending && Minecraft.getInstance().screen == this) {
            closePending = false;
            Minecraft.getInstance().setScreen(parent);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        if (layout.close.contains(mouseX, mouseY)) {
            closePending = true;
            return true;
        }
        if (layout.refresh.contains(mouseX, mouseY)) {
            refreshEntries();
            targetScroll = animatedScroll = 0;
            return true;
        }
        int index = indexAt(mouseX, mouseY);
        if (index >= 0 && index < entries.size()) {
            MaidRecord entry = entries.get(index);
            int verifiedEntityId = validatedEntityId(entry);
            Minecraft.getInstance().setScreen(new MaidModelSelectorScreen(entry.maidUUID(), verifiedEntityId, entry.name(), this));
            return true;
        }
        return layout.panel.contains(mouseX, mouseY) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!layout.list.contains(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, scrollY);
        double amount = scrollY;
        targetScroll = Math.max(0, Math.min(maxScroll(), targetScroll - amount * 12));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void refreshEntries() {
        entries.clear();
        entries.addAll(VerifiedMaidDirectory.listForLocalPlayer(Minecraft.getInstance()));
    }

    private void updateLayout() {
        int panelW = Math.max(0, Math.min(360, width - 12));
        int panelH = Math.max(0, height - 12);
        int x = Math.max(6, width - panelW - 6);
        int y = 6;
        int rowH = 28;
        int footerY = Math.max(y, y + panelH - 24);
        int listY = Math.min(footerY, y + 28);
        layout = new Layout(new Rect(x, y, panelW, panelH),
                new Rect(x + 7, listY, Math.max(0, panelW - 14), Math.max(0, footerY - listY - 18)),
                new Rect(x + 7, footerY, Math.max(0, (panelW - 18) / 2), 18),
                new Rect(x + 11 + (panelW - 18) / 2, footerY, Math.max(0, (panelW - 18) / 2), 18), rowH);
        targetScroll = Math.max(0, Math.min(maxScroll(), targetScroll));
        animatedScroll = Math.max(0, Math.min(maxScroll(), animatedScroll));
    }

    private int indexAt(double mx, double my) {
        if (!layout.list.contains(mx, my)) return -1;
        double localY = my - layout.list.y + animatedScroll;
        int i = (int) (localY / layout.rowHeight);
        return i >= 0 && i < entries.size() && localY - i * layout.rowHeight < layout.rowHeight ? i : -1;
    }

    private int maxScroll() {
        return Math.max(0, entries.size() * layout.rowHeight - layout.list.h);
    }

    private int validatedEntityId(MaidRecord record) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || record.context() != minecraft.level || record.entityId() <= 0) return 0;
        var entity = minecraft.level.getEntity(record.entityId());
        if (entity == null || !record.maidUUID().equals(entity.getUUID()) || !record.ownerUUID().equals(minecraft.player.getUUID())
                || !(entity instanceof TamableAnimal tameable)) return 0;
        LivingEntity owner = tameable.getOwner();
        if (owner == null || minecraft.player == null || !minecraft.player.getUUID().equals(owner.getUUID())) return 0;
        return record.entityId();
    }

    private void drawButton(GuiGraphics graphics, Rect rect, String label, boolean hover) {
        TranslucentTrayChrome.drawButton(graphics, font, rect.x, rect.y, rect.w, rect.h,
                fit(label, Math.max(0, rect.w - 8)), hover, true);
    }

    private boolean mouseOver(Rect rect, double mx, double my) { return rect.contains(mx, my); }

    private String fit(String value, int maxWidth) {
        if (value == null || maxWidth <= 0) return "";
        if (font.width(value) <= maxWidth) return value;
        String ellipsis = "…";
        int end = value.length();
        while (end > 0 && font.width(value.substring(0, end)) + font.width(ellipsis) > maxWidth) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return end == 0 ? "" : value.substring(0, end) + ellipsis;
    }

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double px, double py) { return px >= x && py >= y && px <= x + w && py <= y + h; }
        int centerX() { return x + w / 2; }
        int centerY() { return y + h / 2; }
    }

    private record Layout(Rect panel, Rect list, Rect refresh, Rect close, int rowHeight) {
        static Layout empty() { Rect r = new Rect(0, 0, 0, 0); return new Layout(r, r, r, r, 0); }
    }
}
