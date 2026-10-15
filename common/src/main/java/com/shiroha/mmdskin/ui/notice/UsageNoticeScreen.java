package com.shiroha.mmdskin.ui.notice;

import com.shiroha.mmdskin.ui.config.UsageNoticeConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/** 原版多行提示与确认按钮；关闭或切换世界不会误记为已确认。 */
final class UsageNoticeScreen extends Screen {
    private static final Logger LOGGER = LogManager.getLogger();
    private final UsageNoticeConfig config;
    private final UUID accountId;
    private MultiLineLabel message = MultiLineLabel.EMPTY;
    private MultiLineLabel error = MultiLineLabel.EMPTY;
    private int messageY;
    private int errorY;

    UsageNoticeScreen(UsageNoticeConfig config, UUID accountId) {
        super(Component.literal("模组使用须知"));
        this.config = config;
        this.accountId = accountId;
    }

    @Override
    protected void init() {
        int textWidth = Math.max(1, Math.min(560, width - 32));
        message = MultiLineLabel.create(font, Component.literal(UsageNotice.MESSAGE), textWidth);
        messageY = Math.max(32, (height - message.getLineCount() * 10 - 60) / 2);
        errorY = messageY + message.getLineCount() * 10 + 8;
        int buttonWidth = Math.max(1, Math.min(200, width - 32));
        addRenderableWidget(Button.builder(Component.literal("确认"), button -> confirm())
                .bounds((width - buttonWidth) / 2, Math.min(height - 24, errorY + 30), buttonWidth, 20)
                .build());
    }

    private void confirm() {
        try {
            config.acknowledge(accountId);
            minecraft.setScreen(null);
        } catch (Exception exception) {
            LOGGER.error("保存模组使用须知确认记录失败", exception);
            error = MultiLineLabel.create(font,
                    Component.literal("确认记录保存失败，请检查配置目录权限后重试。"),
                    Math.max(1, Math.min(560, width - 32)));
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        // 必须通过确认按钮保存记录；ESC 不视为确认。
    }

    @Override
    public Component getNarrationMessage() {
        return Component.literal(title.getString() + "\n" + UsageNotice.MESSAGE);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xE0101010);
        // 1.21 的基类会处理背景模糊，必须在绘制标题、正文和错误提示之前执行。
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, Math.max(8, messageY - 24), 0xFFFFFF);
        message.renderCentered(graphics, width / 2, messageY, 10, 0xFFFFFF);
        error.renderCentered(graphics, width / 2, errorY, 10, 0xFF7777);
    }
}
