/* 职责：以原生 GuiGraphics 渲染模型设置界面。 */
package com.shiroha.mmdskin.ui.selector;

import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.ui.chrome.TranslucentTrayChrome;
import com.shiroha.mmdskin.ui.selector.ModelSettingsLayout.UiRect;
import com.shiroha.mmdskin.ui.selector.application.ModelSettingsApplicationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** 文件职责：提供模型设置原生界面。 */
public class ModelSettingsScreen extends Screen {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final ModelSettingsApplicationService SERVICE = ModelSelectorServices.modelSettings();

    private final String modelName;
    private final Screen parentScreen;

    private ModelConfigData config;
    private List<ModelSettingsApplicationService.QuickSlotBinding> quickSlotBindings = List.of();
    private boolean pendingClose;
    private boolean pendingOpenAnimConfig;
    private HoverTarget hoveredTarget = HoverTarget.NONE;
    private ModelSettingsLayout layout = ModelSettingsLayout.create(320, 240, 0);
    private ActiveSlider activeSlider = ActiveSlider.NONE;
    private double scrollOffset;
    private boolean draggingScrollbar;
    private double scrollbarGrabOffset;

    private enum ActiveSlider {
        NONE,
        EYE,
        SCALE,
        HELD_BLOCK
    }

    private enum HoverTarget {
        NONE,
        EYE_TOGGLE,
        TAIL_IDLE_LIFT,
        TAIL_MOVEMENT_BOOST,
        EYE_SLIDER,
        SCALE_SLIDER,
        HELD_BLOCK_SLIDER,
        SLOT_0,
        SLOT_1,
        SLOT_2,
        SLOT_3,
        SAVE,
        RESET,
        ANIM,
        DONE
    }

    public ModelSettingsScreen(String modelName, Screen parentScreen) {
        super(Component.translatable("gui.mmdskin.model_settings.title"));
        this.modelName = modelName;
        this.parentScreen = parentScreen;
        this.config = SERVICE.loadEditableConfig(modelName);
        reloadQuickSlotBindings();
    }

    @Override
    protected void init() {
        super.init();
        activeSlider = ActiveSlider.NONE;
        draggingScrollbar = false;
        updateLayout();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            updateLayout();
            updateHoverState(mouseX, mouseY);
            renderFallback(guiGraphics);
            flushPendingActions(minecraft);
        } catch (Throwable throwable) {
            closeAfterFailure(throwable);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (!layout.panel.contains(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (layout.scrollTrack.contains(mouseX, mouseY)) {
            draggingScrollbar = true;
            scrollbarGrabOffset = layout.scrollThumb.contains(mouseX, mouseY)
                    ? mouseY - layout.scrollThumb.y : layout.scrollThumb.h / 2.0;
            setScrollOffset(layout.scrollToThumb(mouseY - scrollbarGrabOffset));
            return true;
        }

        if (layout.contentHit(layout.eyeToggle, mouseX, mouseY)) {
            config.eyeTrackingEnabled = !config.eyeTrackingEnabled;
            return true;
        }
        if (layout.contentHit(layout.tailIdleToggle, mouseX, mouseY)) {
            config.tailIdleLiftEnabled = !config.tailIdleLiftEnabled;
            return true;
        }
        if (layout.contentHit(layout.tailMovementToggle, mouseX, mouseY)) {
            config.tailMovementBoostEnabled = !config.tailMovementBoostEnabled;
            return true;
        }
        if (layout.contentHit(layout.eyeSlider, mouseX, mouseY)) {
            activeSlider = ActiveSlider.EYE;
            updateSliderValue(activeSlider, mouseX);
            return true;
        }
        if (layout.contentHit(layout.scaleSlider, mouseX, mouseY)) {
            activeSlider = ActiveSlider.SCALE;
            updateSliderValue(activeSlider, mouseX);
            return true;
        }
        if (layout.contentHit(layout.heldBlockSlider, mouseX, mouseY)) {
            activeSlider = ActiveSlider.HELD_BLOCK;
            updateSliderValue(activeSlider, mouseX);
            return true;
        }

        for (int i = 0; i < layout.quickSlotButtons.length; i++) {
            UiRect slotButton = layout.quickSlotButtons[i];
            if (slotButton != null && layout.contentHit(slotButton, mouseX, mouseY)) {
                SERVICE.toggleQuickSlot(modelName, i);
                reloadQuickSlotBindings();
                return true;
            }
        }

        if (layout.saveButton.contains(mouseX, mouseY)) {
            saveAndClose();
            return true;
        }
        if (layout.resetButton.contains(mouseX, mouseY)) {
            config = SERVICE.resetToDefaults();
            return true;
        }
        if (layout.animButton.contains(mouseX, mouseY)) {
            pendingOpenAnimConfig = true;
            return true;
        }
        if (layout.doneButton.contains(mouseX, mouseY)) {
            pendingClose = true;
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            activeSlider = ActiveSlider.NONE;
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingScrollbar) {
            setScrollOffset(layout.scrollToThumb(mouseY - scrollbarGrabOffset));
            return true;
        }
        if (button == 0 && activeSlider != ActiveSlider.NONE) {
            if (mouseY >= layout.viewport.y && mouseY < layout.viewport.bottom()) {
                updateSliderValue(activeSlider, mouseX);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (layout.panel.contains(mouseX, mouseY)) {
            draggingScrollbar = false;
            setScrollOffset(layout.scrollBy(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_PAGE_UP -> setScrollOffset(scrollOffset - layout.viewport.h);
            case GLFW.GLFW_KEY_PAGE_DOWN -> setScrollOffset(scrollOffset + layout.viewport.h);
            case GLFW.GLFW_KEY_HOME -> setScrollOffset(0);
            case GLFW.GLFW_KEY_END -> setScrollOffset(layout.maxScroll);
            default -> { return super.keyPressed(keyCode, scanCode, modifiers); }
        }
        draggingScrollbar = false;
        return true;
    }

    @Override
    public void onClose() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == this) {
            minecraft.setScreen(parentScreen);
            return;
        }
        super.onClose();
    }

    @Override
    public void removed() {
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void updateLayout() {
        layout = ModelSettingsLayout.create(this.width, this.height, scrollOffset);
        scrollOffset = layout.scrollOffset;
    }

    private void setScrollOffset(double value) {
        scrollOffset = value;
        activeSlider = ActiveSlider.NONE;
        updateLayout();
        hoveredTarget = HoverTarget.NONE;
    }

    private void updateHoverState(int mouseX, int mouseY) {
        hoveredTarget = HoverTarget.NONE;
        if (layout.contentHit(layout.eyeToggle, mouseX, mouseY)) {
            hoveredTarget = HoverTarget.EYE_TOGGLE;
            return;
        }
        if (layout.contentHit(layout.tailIdleToggle, mouseX, mouseY)) {
            hoveredTarget = HoverTarget.TAIL_IDLE_LIFT;
            return;
        }
        if (layout.contentHit(layout.tailMovementToggle, mouseX, mouseY)) {
            hoveredTarget = HoverTarget.TAIL_MOVEMENT_BOOST;
            return;
        }
        if (layout.contentHit(layout.eyeSlider, mouseX, mouseY)) {
            hoveredTarget = HoverTarget.EYE_SLIDER;
            return;
        }
        if (layout.contentHit(layout.scaleSlider, mouseX, mouseY)) {
            hoveredTarget = HoverTarget.SCALE_SLIDER;
            return;
        }
        if (layout.contentHit(layout.heldBlockSlider, mouseX, mouseY)) {
            hoveredTarget = HoverTarget.HELD_BLOCK_SLIDER;
            return;
        }
        for (int i = 0; i < layout.quickSlotButtons.length; i++) {
            UiRect slotButton = layout.quickSlotButtons[i];
            if (slotButton != null && layout.contentHit(slotButton, mouseX, mouseY)) {
                hoveredTarget = switch (i) {
                    case 0 -> HoverTarget.SLOT_0;
                    case 1 -> HoverTarget.SLOT_1;
                    case 2 -> HoverTarget.SLOT_2;
                    default -> HoverTarget.SLOT_3;
                };
                return;
            }
        }
        if (layout.saveButton.contains(mouseX, mouseY)) {
            hoveredTarget = HoverTarget.SAVE;
            return;
        }
        if (layout.resetButton.contains(mouseX, mouseY)) {
            hoveredTarget = HoverTarget.RESET;
            return;
        }
        if (layout.animButton.contains(mouseX, mouseY)) {
            hoveredTarget = HoverTarget.ANIM;
            return;
        }
        if (layout.doneButton.contains(mouseX, mouseY)) {
            hoveredTarget = HoverTarget.DONE;
        }
    }

    private void updateSliderValue(ActiveSlider slider, double mouseX) {
        if (slider == ActiveSlider.EYE) {
            config.eyeMaxAngle = valueFromSlider(
                    layout.eyeSlider,
                    mouseX,
                    ModelConfigData.MIN_EYE_MAX_ANGLE,
                    ModelConfigData.MAX_EYE_MAX_ANGLE);
            return;
        }
        if (slider == ActiveSlider.SCALE) {
            config.modelScale = valueFromSlider(
                    layout.scaleSlider,
                    mouseX,
                    ModelConfigData.MIN_MODEL_SCALE,
                    ModelConfigData.MAX_MODEL_SCALE);
            return;
        }
        if (slider == ActiveSlider.HELD_BLOCK) {
            config.heldItemScale = valueFromSlider(
                    layout.heldBlockSlider,
                    mouseX,
                    ModelConfigData.MIN_HELD_ITEM_SCALE,
                    ModelConfigData.MAX_HELD_ITEM_SCALE);
        }
    }

    private float valueFromSlider(UiRect sliderRect, double mouseX, float min, float max) {
        float t = (float) ((mouseX - sliderRect.x) / Math.max(1.0, sliderRect.w));
        return Mth.clamp(min + (max - min) * Mth.clamp(t, 0.0f, 1.0f), min, max);
    }

    private float normalized(float value, float min, float max) {
        return Mth.clamp((value - min) / (max - min), 0.0f, 1.0f);
    }

    private HoverTarget slotHoverTarget(int index) {
        return switch (index) {
            case 0 -> HoverTarget.SLOT_0;
            case 1 -> HoverTarget.SLOT_1;
            case 2 -> HoverTarget.SLOT_2;
            default -> HoverTarget.SLOT_3;
        };
    }
    private void renderFallback(GuiGraphics guiGraphics) {
        TranslucentTrayChrome.drawOverlay(guiGraphics, this.width, this.height);
        TranslucentTrayChrome.drawPanel(guiGraphics, layout.panel.x, layout.panel.y, layout.panel.w, layout.panel.h);

        guiGraphics.drawString(this.font, this.title.getString(), layout.header.x, layout.header.y + 1, TranslucentTrayChrome.TITLE_TEXT, false);
        guiGraphics.drawString(this.font, shorten(modelName, 14), layout.header.x, layout.header.y + 10, TranslucentTrayChrome.SUBTITLE_TEXT, false);

        guiGraphics.enableScissor(layout.viewport.x, layout.viewport.y, layout.viewport.right(), layout.viewport.bottom());
        try {
            drawSettingsContents(guiGraphics);
        } finally {
            guiGraphics.disableScissor();
        }
        if (layout.maxScroll > 0) {
            UiRect track = layout.scrollTrack;
            UiRect thumb = layout.scrollThumb;
            guiGraphics.fill(track.x, track.y, track.right(), track.bottom(), TranslucentTrayChrome.SCROLL_TRACK);
            guiGraphics.fill(thumb.x, thumb.y, thumb.right(), thumb.bottom(), TranslucentTrayChrome.SCROLL_THUMB);
        }

        drawFallbackButton(guiGraphics, layout.saveButton, Component.translatable("gui.mmdskin.model_settings.save").getString(), hoveredTarget == HoverTarget.SAVE);
        drawFallbackButton(guiGraphics, layout.resetButton, Component.translatable("gui.mmdskin.model_settings.reset").getString(), hoveredTarget == HoverTarget.RESET);
        drawFallbackButton(guiGraphics, layout.animButton, Component.translatable("gui.mmdskin.model_settings.anim_config").getString(), hoveredTarget == HoverTarget.ANIM);
        drawFallbackButton(guiGraphics, layout.doneButton, Component.translatable("gui.done").getString(), hoveredTarget == HoverTarget.DONE);
    }

    private void drawSettingsContents(GuiGraphics guiGraphics) {
        float eyeAngleNormalized = normalized(
                config.eyeMaxAngle,
                ModelConfigData.MIN_EYE_MAX_ANGLE,
                ModelConfigData.MAX_EYE_MAX_ANGLE);
        float modelScaleNormalized = normalized(
                config.modelScale,
                ModelConfigData.MIN_MODEL_SCALE,
                ModelConfigData.MAX_MODEL_SCALE);
        float heldBlockScaleNormalized = normalized(
                config.heldItemScale,
                ModelConfigData.MIN_HELD_ITEM_SCALE,
                ModelConfigData.MAX_HELD_ITEM_SCALE);
        drawFallbackCard(guiGraphics, layout.eyeCard, Component.translatable("gui.mmdskin.model_settings.eye_tracking").getString());
        guiGraphics.drawString(this.font, Component.translatable("gui.mmdskin.model_settings.eye_tracking_enabled").getString(), layout.eyeCard.x + 4, layout.eyeToggle.y, TranslucentTrayChrome.BODY_TEXT, false);
        drawFallbackSlider(
                guiGraphics,
                layout.eyeSlider,
                Component.translatable("gui.mmdskin.model_settings.eye_max_angle", String.format("%.0f", Math.toDegrees(config.eyeMaxAngle))).getString(),
                eyeAngleNormalized
        );
        drawFallbackToggle(guiGraphics, layout.eyeToggle, config.eyeTrackingEnabled, hoveredTarget == HoverTarget.EYE_TOGGLE);

        drawFallbackCard(guiGraphics, layout.scaleCard, Component.translatable("gui.mmdskin.model_settings.model_display").getString());
        drawFallbackSlider(
                guiGraphics,
                layout.scaleSlider,
                Component.translatable("gui.mmdskin.model_settings.model_scale", String.format("%.2f", config.modelScale)).getString(),
                modelScaleNormalized
        );

        drawFallbackCard(guiGraphics, layout.heldBlockCard, Component.translatable("gui.mmdskin.model_settings.held_item_display").getString());
        drawFallbackSlider(
                guiGraphics,
                layout.heldBlockSlider,
                Component.translatable("gui.mmdskin.model_settings.held_item_scale", String.format("%.2f", config.heldItemScale)).getString(),
                heldBlockScaleNormalized
        );

        drawFallbackCard(guiGraphics, layout.tailCard, Component.translatable("gui.mmdskin.model_settings.tail_physics").getString());
        guiGraphics.drawString(this.font, Component.translatable("gui.mmdskin.model_settings.tail_idle_lift").getString(),
                layout.tailCard.x + 4, layout.tailIdleToggle.y, TranslucentTrayChrome.BODY_TEXT, false);
        drawFallbackToggle(guiGraphics, layout.tailIdleToggle, config.tailIdleLiftEnabled,
                hoveredTarget == HoverTarget.TAIL_IDLE_LIFT);
        guiGraphics.drawString(this.font, Component.translatable("gui.mmdskin.model_settings.tail_movement_boost").getString(),
                layout.tailCard.x + 4, layout.tailMovementToggle.y, TranslucentTrayChrome.BODY_TEXT, false);
        drawFallbackToggle(guiGraphics, layout.tailMovementToggle, config.tailMovementBoostEnabled,
                hoveredTarget == HoverTarget.TAIL_MOVEMENT_BOOST);

        drawFallbackCard(guiGraphics, layout.quickCard, Component.translatable("gui.mmdskin.model_settings.quick_bind").getString());
        for (int i = 0; i < quickSlotBindings.size() && i < layout.quickSlotButtons.length; i++) {
            ModelSettingsApplicationService.QuickSlotBinding binding = quickSlotBindings.get(i);
            UiRect rect = layout.quickSlotButtons[i];
            int bg = binding.boundToCurrentModel()
                    ? TranslucentTrayChrome.CARD_SELECTED
                    : TranslucentTrayChrome.cardBackground(false, hoveredTarget == slotHoverTarget(i));
            guiGraphics.fill(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, bg);
            guiGraphics.drawCenteredString(this.font, buildQuickSlotLabel(binding), rect.centerX(), rect.y + 4, TranslucentTrayChrome.TITLE_TEXT);
        }
    }

    private void drawFallbackCard(GuiGraphics guiGraphics, UiRect rect, String title) {
        TranslucentTrayChrome.fillListArea(guiGraphics, rect.x, rect.y, rect.w, rect.h);
        guiGraphics.drawString(this.font, title, rect.x + 4, rect.y + 3, TranslucentTrayChrome.BODY_TEXT, false);
    }

    private void drawFallbackSlider(GuiGraphics guiGraphics, UiRect rect, String label, float normalized) {
        guiGraphics.drawString(this.font, label, rect.x, rect.y - 9, TranslucentTrayChrome.SUBTITLE_TEXT, false);
        guiGraphics.fill(rect.x, rect.y + 3, rect.x + rect.w, rect.y + 7, 0x28FFFFFF);
        int fillRight = rect.x + Math.round(rect.w * normalized);
        guiGraphics.fill(rect.x, rect.y + 3, fillRight, rect.y + 7, 0x58FFFFFF);
    }

    private void drawFallbackToggle(GuiGraphics guiGraphics, UiRect rect, boolean enabled, boolean hovered) {
        guiGraphics.fill(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, hovered ? 0x30FFFFFF : 0x1A000000);
        int knobSize = rect.h - 2;
        int knobX = enabled ? rect.x + rect.w - knobSize - 1 : rect.x + 1;
        guiGraphics.fill(knobX, rect.y + 1, knobX + knobSize, rect.y + rect.h - 1, 0xFFDDE8F8);
    }

    private void drawFallbackButton(GuiGraphics guiGraphics, UiRect rect, String text, boolean hovered) {
        TranslucentTrayChrome.drawButton(guiGraphics, this.font, rect.x, rect.y, rect.w, rect.h, text, hovered, true);
    }

    private void saveAndClose() {
        SERVICE.save(modelName, config);
        pendingClose = true;
    }

    private void reloadQuickSlotBindings() {
        quickSlotBindings = List.copyOf(SERVICE.getQuickSlotBindings(modelName));
    }

    private void flushPendingActions(Minecraft minecraft) {
        if (pendingOpenAnimConfig && minecraft.screen == this) {
            pendingOpenAnimConfig = false;
            minecraft.setScreen(new ModelAnimationScreen(modelName, this));
            return;
        }
        if (pendingClose && minecraft.screen == this) {
            pendingClose = false;
            minecraft.setScreen(parentScreen);
        }
    }

    private void closeAfterFailure(Throwable throwable) {
        LOGGER.error("[ModelSettings] Native settings render failed and will close", throwable);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == this) {
            minecraft.setScreen(parentScreen);
        }
    }

    private static String buildQuickSlotLabel(ModelSettingsApplicationService.QuickSlotBinding binding) {
        String base = Component.translatable("gui.mmdskin.model_settings.slot", binding.slot() + 1).getString();
        if (binding.boundToCurrentModel()) {
            return "[x] " + base;
        }
        if (binding.boundModel() != null && !binding.boundModel().isEmpty()) {
            return "[*] " + base;
        }
        return "[ ] " + base;
    }

    private static String shorten(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        if (maxChars <= 3) {
            return value.substring(0, Math.max(0, maxChars));
        }
        return value.substring(0, maxChars - 2) + "..";
    }

}
