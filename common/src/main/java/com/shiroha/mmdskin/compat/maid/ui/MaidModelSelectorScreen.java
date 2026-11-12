/** 文件职责：提供支持搜索、返回来源界面及保存反馈的女仆模型选择界面。 */
package com.shiroha.mmdskin.compat.maid.ui;

import com.shiroha.mmdskin.compat.maid.service.DefaultMaidModelSelectionService;
import com.shiroha.mmdskin.compat.maid.service.MaidModelSelectionService;
import com.shiroha.mmdskin.config.UIConstants;
import com.shiroha.mmdskin.ui.chrome.TranslucentTrayChrome;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MaidModelSelectorScreen extends Screen {
    private static final int OUTER_MARGIN = 6;
    private static final int BUTTON_HEIGHT = 18;
    private static final int HEADER_HEIGHT = 56;
    private static final int CARD_HEIGHT = 16;
    private static final int CARD_GAP = 3;
    private static final int LIST_PADDING = 4;

    private final UUID maidUUID;
    private final int maidEntityId;
    private final String maidName;
    private final Screen parent;
    private final MaidModelSelectionService selectionService;
    private final List<String> allModels = new ArrayList<>();
    private final List<String> visibleModels = new ArrayList<>();

    private String currentModel;
    private boolean saveFailed;
    private EditBox searchBox;
    private boolean pendingClose;
    private float targetScroll;
    private float animatedScroll;
    private int hoveredCard = -1;
    private ButtonTarget hoveredButton = ButtonTarget.NONE;
    private Layout layout = Layout.empty();

    public MaidModelSelectorScreen(UUID maidUUID, int maidEntityId, String maidName) {
        this(maidUUID, maidEntityId, maidName, null, new DefaultMaidModelSelectionService());
    }

    public MaidModelSelectorScreen(UUID maidUUID, int maidEntityId, String maidName, Screen parent) {
        this(maidUUID, maidEntityId, maidName, parent, new DefaultMaidModelSelectionService());
    }

    MaidModelSelectorScreen(UUID maidUUID, int maidEntityId, String maidName, Screen parent,
                            MaidModelSelectionService selectionService) {
        super(Component.translatable("gui.mmdskin.maid_model_selector"));
        this.maidUUID = maidUUID;
        this.maidEntityId = maidEntityId;
        this.maidName = maidName == null ? "" : maidName;
        this.parent = parent;
        this.selectionService = selectionService;
        reloadModels();
    }

    @Override
    protected void init() {
        String previousQuery = searchBox == null ? "" : searchBox.getValue();
        super.init();
        updateLayout();
        searchBox = new EditBox(font, layout.searchBox.x, layout.searchBox.y, layout.searchBox.w, layout.searchBox.h,
                Component.translatable("gui.mmdskin.maid_model_selector.search"));
        searchBox.setHint(Component.translatable("gui.mmdskin.maid_model_selector.search"));
        searchBox.setResponder(value -> applyFilter());
        searchBox.setValue(previousQuery);
        addRenderableWidget(searchBox);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateLayout();
        updateSearchBounds();
        animatedScroll = Mth.lerp(0.24f, animatedScroll, targetScroll);
        if (Math.abs(animatedScroll - targetScroll) < 0.25f) animatedScroll = targetScroll;
        updateHoverState(mouseX, mouseY);
        TranslucentTrayChrome.drawOverlay(graphics, width, height);
        graphics.enableScissor(0, 0, width, height);
        TranslucentTrayChrome.drawPanel(graphics, layout.panel.x, layout.panel.y, layout.panel.w, layout.panel.h);
        drawHeader(graphics);
        drawButtons(graphics);
        drawModelList(graphics);
        // 搜索框是实际输入控件，单独绘制以配合自适应面板。
        if (searchBox != null) searchBox.render(graphics, mouseX, mouseY, partialTick);
        if (saveFailed && layout.panel.h > 0) {
            String message = fitPixels(Component.translatable("gui.mmdskin.maid.save_failed").getString(), Math.max(0, layout.panel.w - 12));
            graphics.drawCenteredString(font, message, layout.panel.centerX(), layout.panel.y + layout.panel.h - 27, 0xFFFF7777);
        }
        graphics.disableScissor();
        if (pendingClose && Minecraft.getInstance().screen == this) {
            pendingClose = false;
            Minecraft.getInstance().setScreen(parent);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        updateHoverState((int) mouseX, (int) mouseY);
        if (button == 0 && layout.searchBox.contains(mouseX, mouseY) && searchBox != null) {
            setFocused(searchBox);
            return searchBox.mouseClicked(mouseX, mouseY, button);
        }
        if (searchBox != null) searchBox.setFocused(false);
        if (button != 0 || !layout.panel.contains(mouseX, mouseY)) return super.mouseClicked(mouseX, mouseY, button);
        if (layout.doneButton.contains(mouseX, mouseY)) {
            pendingClose = true;
            return true;
        }
        if (layout.refreshButton.contains(mouseX, mouseY)) {
            reloadModels();
            targetScroll = animatedScroll = 0;
            return true;
        }
        if (layout.listBox.contains(mouseX, mouseY) && hoveredCard >= 0 && hoveredCard < visibleModels.size()) {
            selectModel(visibleModels.get(hoveredCard));
            return true;
        }
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return searchBox != null && searchBox.isFocused() && searchBox.charTyped(codePoint, modifiers)
                || super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            onClose();
            return true;
        }
        if (searchBox != null && searchBox.isFocused() && searchBox.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!layout.listBox.contains(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, scrollY);
        double delta = scrollY;
        targetScroll = Mth.clamp(targetScroll - (float) delta * 12.0f, 0.0f, maxScroll());
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void drawHeader(GuiGraphics graphics) {
        graphics.drawString(font, fitPixels(title.getString(), Math.max(0, layout.header.w)),
                layout.header.x, layout.header.y, TranslucentTrayChrome.TITLE_TEXT, false);
        graphics.drawString(font, fitPixels(maidName, Math.max(0, layout.header.w)),
                layout.header.x, layout.header.y + 11, TranslucentTrayChrome.SUBTITLE_TEXT, false);
        String stats = Component.translatable("gui.mmdskin.model_selector.stats",
                        Math.max(0, allModels.size() - 1), fitPixels(currentLabel(), Math.max(0, layout.header.w))).getString();
        graphics.drawString(font, fitPixels(stats, Math.max(0, layout.header.w)),
                layout.header.x, layout.header.y + 21, TranslucentTrayChrome.DETAIL_TEXT, false);
    }

    private void drawButtons(GuiGraphics graphics) {
        drawButton(graphics, layout.doneButton, Component.translatable("gui.done").getString(), hoveredButton == ButtonTarget.DONE);
        drawButton(graphics, layout.refreshButton, Component.translatable("gui.mmdskin.refresh").getString(), hoveredButton == ButtonTarget.REFRESH);
    }

    private void drawModelList(GuiGraphics graphics) {
        var list = layout.listBox;
        TranslucentTrayChrome.fillListArea(graphics, list.x, list.y, list.w, list.h);
        if (visibleModels.isEmpty()) {
            String empty = fitPixels(Component.translatable("gui.mmdskin.maid_model_selector.no_results").getString(), Math.max(0, list.w - 8));
            graphics.drawCenteredString(font, empty, list.centerX(), list.centerY() - 4, TranslucentTrayChrome.BODY_TEXT);
            return;
        }
        graphics.enableScissor(list.x, list.y, list.x + list.w, list.y + list.h);
        int y = Math.round(list.y + LIST_PADDING - animatedScroll);
        for (int i = 0; i < visibleModels.size(); i++) {
            String model = visibleModels.get(i);
            if (y + CARD_HEIGHT >= list.y && y <= list.y + list.h) drawCard(graphics, list, y, model, i == hoveredCard);
            y += CARD_HEIGHT + CARD_GAP;
        }
        graphics.disableScissor();
        drawScrollBar(graphics, list);
    }

    private void drawCard(GuiGraphics graphics, MaidModelSelectorScreen.UiRect list, int y, String model, boolean hovered) {
        boolean selected = model.equals(currentModel);
        graphics.fill(list.x + 3, y, list.x + list.w - 3, y + CARD_HEIGHT,
                TranslucentTrayChrome.cardBackground(selected, hovered));
        graphics.fill(list.x + 3, y, list.x + 5, y + CARD_HEIGHT,
                selected ? TranslucentTrayChrome.ACCENT_STRIP_ACTIVE : TranslucentTrayChrome.ACCENT_STRIP);
        String name = UIConstants.DEFAULT_MODEL_NAME.equals(model)
                ? Component.translatable("gui.mmdskin.maid.default_appearance").getString() : model;
        int currentWidth = font.width(Component.translatable("gui.mmdskin.maid.current").getString());
        boolean showMarker = selected && list.w >= currentWidth + 34;
        int markerWidth = showMarker ? currentWidth + 7 : 0;
        String clipped = fitPixels(name, Math.max(0, list.w - 16 - markerWidth));
        graphics.drawString(font, clipped, list.x + 9, y + 4, TranslucentTrayChrome.BODY_TEXT, false);
        if (showMarker) graphics.drawString(font, Component.translatable("gui.mmdskin.maid.current"),
                list.x + list.w - currentWidth - 7,
                y + 4, TranslucentTrayChrome.DETAIL_TEXT, false);
    }

    private void drawScrollBar(GuiGraphics graphics, MaidModelSelectorScreen.UiRect list) {
        float max = maxScroll();
        if (max <= 0 || list.h <= 0) return;
        int x = list.x + list.w - 2;
        graphics.fill(x, list.y, x + 2, list.y + list.h, TranslucentTrayChrome.SCROLL_TRACK);
        float contentHeight = contentHeight();
        int thumbHeight = Math.max(8, Math.round(list.h * list.h / Math.max(list.h, contentHeight)));
        int thumbY = list.y + Math.round(animatedScroll / max * Math.max(0, list.h - thumbHeight));
        graphics.fill(x, thumbY, x + 2, thumbY + thumbHeight, TranslucentTrayChrome.SCROLL_THUMB);
    }

    private void drawButton(GuiGraphics graphics, MaidModelSelectorScreen.UiRect rect, String text, boolean hovered) {
        TranslucentTrayChrome.drawButton(graphics, font, rect.x, rect.y, rect.w, rect.h, fitPixels(text, rect.w - 8), hovered, true);
    }

    private void updateLayout() {
        int maxWidth = Math.max(0, width - OUTER_MARGIN * 2);
        int panelWidth = Math.min(maxWidth, Math.min(332, Math.max(0, Math.round(width * 0.34f))));
        int panelHeight = Math.max(0, height - OUTER_MARGIN * 2);
        int panelX = Math.max(0, width - panelWidth - Math.min(OUTER_MARGIN, width));
        int panelY = Math.min(OUTER_MARGIN, height);
        panelHeight = Math.max(0, height - panelY * 2);
        UiRect panel = new UiRect(panelX, panelY, panelWidth, panelHeight);
        UiRect header = new UiRect(panelX + Math.min(8, panelWidth / 2), panel.y + 6, Math.max(0, panelWidth - 16), HEADER_HEIGHT);
        int buttonWidth = Math.max(0, (header.w - 4) / 2);
        int buttonHeight = Math.min(BUTTON_HEIGHT, panel.h);
        int buttonY = Math.max(panel.y, panel.y + panel.h - buttonHeight - 6);
        UiRect done = new UiRect(header.x, buttonY, buttonWidth, buttonHeight);
        UiRect refresh = new UiRect(header.x + buttonWidth + 4, buttonY, Math.max(0, header.w - buttonWidth - 4), buttonHeight);
        int listY = Math.min(buttonY, header.y + header.h + 4);
        UiRect list = new UiRect(header.x, listY, header.w, Math.max(0, buttonY - listY - 5));
        int searchHeight = Math.min(18, Math.max(0, panel.y + panel.h - (header.y + 34)));
        UiRect search = new UiRect(header.x, header.y + 34,
                Math.min(header.w, Math.max(0, panel.x + panel.w - header.x - 8)), searchHeight);
        layout = new Layout(panel, header, search, list, done, refresh);
        targetScroll = Mth.clamp(targetScroll, 0.0f, maxScroll());
        animatedScroll = Mth.clamp(animatedScroll, 0.0f, maxScroll());
    }

    private void updateSearchBounds() {
        if (searchBox != null && (searchBox.getX() != layout.searchBox.x || searchBox.getY() != layout.searchBox.y
                || searchBox.getWidth() != layout.searchBox.w)) {
            searchBox.setX(layout.searchBox.x);
            searchBox.setY(layout.searchBox.y);
            searchBox.setWidth(layout.searchBox.w);
        }
    }

    private void updateHoverState(int mouseX, int mouseY) {
        hoveredCard = -1;
        hoveredButton = ButtonTarget.NONE;
        if (layout.doneButton.contains(mouseX, mouseY)) hoveredButton = ButtonTarget.DONE;
        else if (layout.refreshButton.contains(mouseX, mouseY)) hoveredButton = ButtonTarget.REFRESH;
        else if (layout.listBox.contains(mouseX, mouseY)) {
            float local = mouseY - (layout.listBox.y + LIST_PADDING) + animatedScroll;
            if (local >= 0) {
                int index = (int) (local / (CARD_HEIGHT + CARD_GAP));
                if (index < visibleModels.size() && local - index * (CARD_HEIGHT + CARD_GAP) <= CARD_HEIGHT) hoveredCard = index;
            }
        }
    }

    private void reloadModels() {
        allModels.clear();
        allModels.addAll(selectionService.loadAvailableModels());
        if (!allModels.contains(UIConstants.DEFAULT_MODEL_NAME)) allModels.add(0, UIConstants.DEFAULT_MODEL_NAME);
        currentModel = selectionService.getCurrentModel(maidUUID);
        saveFailed = false;
        applyFilter();
    }

    private void applyFilter() {
        visibleModels.clear();
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        for (String model : allModels) {
            if (model.equals(UIConstants.DEFAULT_MODEL_NAME) || model.toLowerCase(Locale.ROOT).contains(query)) visibleModels.add(model);
        }
        targetScroll = animatedScroll = 0;
    }

    private void selectModel(String modelName) {
        try {
            selectionService.selectModel(maidUUID, maidEntityId, modelName);
            currentModel = modelName;
            saveFailed = false;
        } catch (RuntimeException exception) {
            // 保存失败时保留原选择，并在界面底部显示明确反馈。
            saveFailed = true;
        }
    }

    private float contentHeight() {
        return visibleModels.isEmpty() ? 0 : LIST_PADDING * 2.0f + visibleModels.size() * (CARD_HEIGHT + CARD_GAP) - CARD_GAP;
    }

    private float maxScroll() {
        return Math.max(0.0f, contentHeight() - layout.listBox.h);
    }

    private String currentLabel() {
        return UIConstants.DEFAULT_MODEL_NAME.equals(currentModel)
                ? Component.translatable("gui.mmdskin.maid.default_appearance").getString() : currentModel;
    }

    private String fitPixels(String value, int maxWidth) {
        if (value == null || maxWidth <= 0) return "";
        if (font.width(value) <= maxWidth) return value;
        String suffix = "…";
        int suffixWidth = font.width(suffix);
        if (suffixWidth > maxWidth) return "";
        int end = value.length();
        while (end > 0 && font.width(value.substring(0, end)) + suffixWidth > maxWidth) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(0, end) + suffix;
    }

    private enum ButtonTarget { NONE, DONE, REFRESH }

    record UiRect(int x, int y, int w, int h) {
        static UiRect empty() { return new UiRect(0, 0, 0, 0); }
        boolean contains(double px, double py) { return px >= x && py >= y && px <= x + w && py <= y + h; }
        int centerX() { return x + w / 2; }
        int centerY() { return y + h / 2; }
    }

    private record Layout(UiRect panel, UiRect header, UiRect searchBox, UiRect listBox, UiRect doneButton, UiRect refreshButton) {
        static Layout empty() { UiRect r = UiRect.empty(); return new Layout(r, r, r, r, r, r); }
    }
}
