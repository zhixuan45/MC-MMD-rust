package com.shiroha.mmdskin.neoforge.config;

import com.shiroha.mmdskin.config.UIConstants;
import com.shiroha.mmdskin.ui.config.ModelSelectorConfig;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * 玩家独立模型替换配置条目。
 * 支持在线/离线状态展示，可自由切换基于 UUID 或 玩家名称 进行模型绑定。
 */
public final class PlayerReplacementListEntry extends AbstractConfigListEntry<String> {
    private static final int ENTRY_HEIGHT = 26;
    private static final int BIND_BUTTON_WIDTH = 58;
    private static final int CHOOSE_BUTTON_WIDTH = 52;
    private static final int RESET_BUTTON_WIDTH = 44;
    private static final int BUTTON_GAP = 4;
    private static final int COLOR_ONLINE = 0x55FF55;
    private static final int COLOR_OFFLINE = 0xAAAAAA;
    private static final int COLOR_VALUE = 0xA0A0A0;

    private final UUID uuid;
    private final String playerName;
    private final boolean isOnline;
    private boolean bindByUuid;

    private final Button bindModeButton;
    private final Button chooseButton;
    private final Button resetButton;

    private String originalValue;
    private String value;
    private boolean originalBindByUuid;

    public PlayerReplacementListEntry(UUID uuid, String playerName, boolean isOnline,
                                      boolean initialBindByUuid, String initialModel) {
        super(createDisplayName(uuid, playerName, isOnline), false);
        this.uuid = uuid;
        this.playerName = playerName;
        this.isOnline = isOnline;
        this.bindByUuid = (uuid != null && (initialBindByUuid || playerName == null || playerName.isBlank()));
        this.originalBindByUuid = this.bindByUuid;
        this.originalValue = normalize(initialModel);
        this.value = this.originalValue;

        this.bindModeButton = Button.builder(
                getBindModeButtonText(),
                button -> toggleBindMode())
            .bounds(0, 0, BIND_BUTTON_WIDTH, 20)
            .build();

        this.chooseButton = Button.builder(
                Component.translatable("gui.mmdskin.mod_settings.mob_replacement.choose"),
                button -> openPicker())
            .bounds(0, 0, CHOOSE_BUTTON_WIDTH, 20)
            .build();

        this.resetButton = Button.builder(
                Component.translatable("gui.mmdskin.mod_settings.mob_replacement.reset"),
                button -> setValue(UIConstants.DEFAULT_MODEL_NAME))
            .bounds(0, 0, RESET_BUTTON_WIDTH, 20)
            .build();

        updateButtons();
    }

    private static Component createDisplayName(UUID uuid, String playerName, boolean isOnline) {
        String statusPrefix = isOnline
            ? "§a[" + Component.translatable("gui.mmdskin.mod_settings.player_replacement.online").getString() + "] "
            : "§7[" + Component.translatable("gui.mmdskin.mod_settings.player_replacement.offline").getString() + "] ";

        if (playerName != null && !playerName.isBlank()) {
            if (uuid != null) {
                String shortUuid = uuid.toString().substring(0, 8);
                return Component.literal(statusPrefix + "§f" + playerName + " §8(" + shortUuid + "...)");
            }
            return Component.literal(statusPrefix + "§f" + playerName);
        } else if (uuid != null) {
            return Component.literal(statusPrefix + "§f" + uuid.toString().substring(0, 13) + "...");
        }
        return Component.literal(statusPrefix + "§f未知玩家");
    }

    private Component getBindModeButtonText() {
        if (bindByUuid) {
            return Component.literal("§b" + Component.translatable("gui.mmdskin.mod_settings.player_replacement.bind_uuid").getString());
        } else {
            return Component.literal("§e" + Component.translatable("gui.mmdskin.mod_settings.player_replacement.bind_name").getString());
        }
    }

    private void toggleBindMode() {
        if (uuid != null && playerName != null && !playerName.isBlank()) {
            bindByUuid = !bindByUuid;
            bindModeButton.setMessage(getBindModeButtonText());
        }
    }

    private void openPicker() {
        Screen parent = Minecraft.getInstance().screen;
        if (parent == null) {
            return;
        }
        Component title = createDisplayName(uuid, playerName, isOnline);
        Minecraft.getInstance().setScreen(new PlayerReplacementModelPickerScreen(parent, title, value, this::setValue));
    }

    private void setValue(String value) {
        this.value = normalize(value);
        updateButtons();
    }

    private void updateButtons() {
        this.bindModeButton.active = (uuid != null && playerName != null && !playerName.isBlank());
        this.bindModeButton.setMessage(getBindModeButtonText());
        this.resetButton.active = !UIConstants.DEFAULT_MODEL_NAME.equals(this.value);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return UIConstants.DEFAULT_MODEL_NAME;
        }
        return value;
    }

    private static String trimToWidth(String value, int maxWidth) {
        if (value == null || value.isEmpty() || maxWidth <= 0) {
            return "";
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.font.width(value) <= maxWidth) {
            return value;
        }
        String ellipsis = "...";
        int ellipsisWidth = minecraft.font.width(ellipsis);
        String trimmed = value;
        while (!trimmed.isEmpty() && minecraft.font.width(trimmed) + ellipsisWidth > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? ellipsis : trimmed + ellipsis;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public Optional<String> getDefaultValue() {
        return Optional.of(UIConstants.DEFAULT_MODEL_NAME);
    }

    @Override
    public void save() {
        ModelSelectorConfig config = ModelSelectorConfig.getInstance();
        if (UIConstants.DEFAULT_MODEL_NAME.equals(value) || value.isBlank()) {
            // 清理对应键
            if (uuid != null) {
                config.removePlayerModelByUuid(uuid);
            }
            if (playerName != null && !playerName.isBlank()) {
                config.removePlayerModel(playerName);
            }
        } else {
            if (bindByUuid && uuid != null) {
                config.setPlayerModelByUuid(uuid, value);
                if (playerName != null && !playerName.isBlank()) {
                    config.removePlayerModel(playerName);
                }
            } else if (playerName != null && !playerName.isBlank()) {
                config.setPlayerModel(playerName, value);
                if (uuid != null) {
                    config.removePlayerModelByUuid(uuid);
                }
            }
        }
        originalValue = value;
        originalBindByUuid = bindByUuid;
        updateButtons();
    }

    @Override
    public boolean isEdited() {
        return !Objects.equals(originalValue, value) || (originalBindByUuid != bindByUuid);
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return List.of(bindModeButton, chooseButton, resetButton);
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
        return List.of(bindModeButton, chooseButton, resetButton);
    }

    @Override
    public int getItemHeight() {
        return ENTRY_HEIGHT;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int index, int y, int x, int entryWidth, int entryHeight,
                       int mouseX, int mouseY, boolean isHovered, float delta) {
        int resetX = x + entryWidth - RESET_BUTTON_WIDTH;
        int chooseX = resetX - BUTTON_GAP - CHOOSE_BUTTON_WIDTH;
        int bindX = chooseX - BUTTON_GAP - BIND_BUTTON_WIDTH;
        int buttonY = y + 2;

        bindModeButton.setX(bindX);
        bindModeButton.setY(buttonY);
        chooseButton.setX(chooseX);
        chooseButton.setY(buttonY);
        resetButton.setX(resetX);
        resetButton.setY(buttonY);

        Minecraft minecraft = Minecraft.getInstance();
        Component label = createDisplayName(uuid, playerName, isOnline);
        guiGraphics.drawString(minecraft.font, label, x, y + 7, isOnline ? COLOR_ONLINE : COLOR_OFFLINE, false);

        String summary = trimToWidth(
            ModConfigScreen.toModelSelectionComponent(value).getString(),
            Math.max(30, bindX - x - 12)
        );
        int summaryWidth = minecraft.font.width(summary);
        guiGraphics.drawString(minecraft.font, summary, bindX - 8 - summaryWidth, y + 7, COLOR_VALUE, false);

        bindModeButton.render(guiGraphics, mouseX, mouseY, delta);
        chooseButton.render(guiGraphics, mouseX, mouseY, delta);
        resetButton.render(guiGraphics, mouseX, mouseY, delta);
    }
}
