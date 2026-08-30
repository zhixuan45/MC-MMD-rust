package com.shiroha.mmdskin.neoforge.config;

import com.shiroha.mmdskin.config.UIConstants;
import com.shiroha.mmdskin.ui.config.ModelSelectorConfig;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 允许用户手动输入任意玩家名或 UUID 添加独立模型绑定的配置条目。
 */
public final class AddCustomPlayerEntry extends AbstractConfigListEntry<String> {
    private static final int ENTRY_HEIGHT = 28;
    private static final int CHOOSE_BUTTON_WIDTH = 52;
    private static final int ADD_BUTTON_WIDTH = 48;
    private static final int BUTTON_GAP = 4;
    private static final int COLOR_LABEL = 0xFFFFAA;
    private static final int COLOR_VALUE = 0xA0A0A0;

    private final EditBox inputField;
    private final Button chooseButton;
    private final Button addButton;
    private final Runnable onAddedCallback;

    private String selectedModel = UIConstants.DEFAULT_MODEL_NAME;

    public AddCustomPlayerEntry(Runnable onAddedCallback) {
        super(Component.translatable("gui.mmdskin.mod_settings.player_replacement.add_custom"), false);
        this.onAddedCallback = onAddedCallback;

        Minecraft mc = Minecraft.getInstance();
        this.inputField = new EditBox(mc.font, 0, 0, 110, 18,
            Component.translatable("gui.mmdskin.mod_settings.player_replacement.add_dialog.input"));
        this.inputField.setMaxLength(48);
        this.inputField.setHint(Component.translatable("gui.mmdskin.mod_settings.player_replacement.add_dialog.input"));
        this.inputField.setResponder(text -> updateAddButtonState());

        this.chooseButton = Button.builder(
                Component.translatable("gui.mmdskin.mod_settings.mob_replacement.choose"),
                button -> openPicker())
            .bounds(0, 0, CHOOSE_BUTTON_WIDTH, 20)
            .build();

        this.addButton = Button.builder(
                Component.translatable("gui.mmdskin.mod_settings.player_replacement.add_dialog.confirm"),
                button -> performAdd())
            .bounds(0, 0, ADD_BUTTON_WIDTH, 20)
            .build();

        updateAddButtonState();
    }

    private void openPicker() {
        Screen parent = Minecraft.getInstance().screen;
        if (parent == null) return;
        Component title = Component.literal("§e" + inputField.getValue().trim());
        Minecraft.getInstance().setScreen(new PlayerReplacementModelPickerScreen(parent, title, selectedModel, model -> {
            this.selectedModel = model;
            updateAddButtonState();
        }));
    }

    private void performAdd() {
        String input = inputField.getValue().trim();
        if (input.isEmpty() || UIConstants.DEFAULT_MODEL_NAME.equals(selectedModel)) {
            return;
        }

        UUID uuid = null;
        try {
            uuid = UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {}

        ModelSelectorConfig config = ModelSelectorConfig.getInstance();
        if (uuid != null) {
            config.setPlayerModelByUuid(uuid, selectedModel);
        } else {
            config.setPlayerModel(input, selectedModel);
        }

        inputField.setValue("");
        selectedModel = UIConstants.DEFAULT_MODEL_NAME;
        updateAddButtonState();

        if (onAddedCallback != null) {
            onAddedCallback.run();
        }
    }

    private void updateAddButtonState() {
        String input = inputField.getValue().trim();
        addButton.active = !input.isEmpty() && !UIConstants.DEFAULT_MODEL_NAME.equals(selectedModel);
    }

    @Override
    public String getValue() {
        return inputField.getValue();
    }

    @Override
    public Optional<String> getDefaultValue() {
        return Optional.of("");
    }

    @Override
    public void save() {
        performAdd();
    }

    @Override
    public boolean isEdited() {
        return !inputField.getValue().trim().isEmpty() && !UIConstants.DEFAULT_MODEL_NAME.equals(selectedModel);
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return List.of(inputField, chooseButton, addButton);
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
        return List.of(inputField, chooseButton, addButton);
    }

    @Override
    public int getItemHeight() {
        return ENTRY_HEIGHT;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int index, int y, int x, int entryWidth, int entryHeight,
                       int mouseX, int mouseY, boolean isHovered, float delta) {
        int addX = x + entryWidth - ADD_BUTTON_WIDTH;
        int chooseX = addX - BUTTON_GAP - CHOOSE_BUTTON_WIDTH;
        int inputWidth = Math.min(130, chooseX - x - 120);
        int inputX = chooseX - BUTTON_GAP - Math.max(80, inputWidth);
        int buttonY = y + 2;

        inputField.setX(inputX);
        inputField.setY(buttonY + 1);
        inputField.setWidth(Math.max(80, inputWidth));

        chooseButton.setX(chooseX);
        chooseButton.setY(buttonY);
        addButton.setX(addX);
        addButton.setY(buttonY);

        Minecraft minecraft = Minecraft.getInstance();
        guiGraphics.drawString(minecraft.font, Component.translatable("gui.mmdskin.mod_settings.player_replacement.add_custom"), x, y + 7, COLOR_LABEL, false);

        String summary = ModConfigScreen.toModelSelectionComponent(selectedModel).getString();
        if (!UIConstants.DEFAULT_MODEL_NAME.equals(selectedModel)) {
            int summaryWidth = minecraft.font.width(summary);
            guiGraphics.drawString(minecraft.font, summary, inputX - 6 - summaryWidth, y + 7, COLOR_VALUE, false);
        }

        inputField.render(guiGraphics, mouseX, mouseY, delta);
        chooseButton.render(guiGraphics, mouseX, mouseY, delta);
        addButton.render(guiGraphics, mouseX, mouseY, delta);
    }
}
