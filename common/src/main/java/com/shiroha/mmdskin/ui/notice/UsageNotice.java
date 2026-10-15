package com.shiroha.mmdskin.ui.notice;

import com.shiroha.mmdskin.ui.config.UsageNoticeConfig;
import net.minecraft.client.Minecraft;

/** 等待进入世界且加载界面关闭，再显示一次可持久确认的原版提示界面。 */
public final class UsageNotice {
    public static final String REPOSITORY_URL = "https://github.com/zhixuan45/MC-MMD-rust";
    public static final String MESSAGE = "本模组由 zhixuan45/shengxi 免费维护与更新，完全开源公开，如果你是付费获取请退款，免费开源地址["
            + REPOSITORY_URL + "]\n该模组禁止使用在R18G等相关场景";
    private static UsageNoticeConfig config;

    private UsageNotice() {}

    public static void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null
                || minecraft.screen != null || minecraft.getOverlay() != null) return;
        if (config == null) {
            config = new UsageNoticeConfig(minecraft.gameDirectory.toPath()
                    .resolve("config/mmdskin/usage_notice.json").toFile());
        }
        if (!config.isAcknowledged()) {
            minecraft.setScreen(new UsageNoticeScreen(config));
        }
    }
}
