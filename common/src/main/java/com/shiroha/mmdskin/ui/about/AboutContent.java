package com.shiroha.mmdskin.ui.about;

import com.shiroha.mmdskin.ui.notice.UsageNotice;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 四个加载器共用“关于”内容，设置界面负责使用原生可展开子菜单展示。 */
public final class AboutContent {
    public static final String SPONSORS_RESOURCE = "/assets/mmdskin/about/sponsors.json";
    private static final Logger LOGGER = LogManager.getLogger();

    private AboutContent() {}

    public static List<Component> projectDetails() {
        return List.of(
                Component.translatable("gui.mmdskin.about.maintainers", "zhixuan45/shengxi"),
                Component.translatable("gui.mmdskin.about.free"),
                Component.translatable("gui.mmdskin.about.source").append("\n")
                        .append(link(UsageNotice.REPOSITORY_URL)),
                Component.translatable("gui.mmdskin.about.license"),
                Component.translatable("gui.mmdskin.about.license_link").append("\n")
                        .append(link(UsageNotice.REPOSITORY_URL + "/blob/HEAD/LICENSE")),
                Component.translatable("gui.mmdskin.about.restriction").withStyle(ChatFormatting.RED));
    }

    public static List<Component> sponsorDetails() {
        List<Component> lines = new ArrayList<>();
        for (SponsorCredits.Sponsor sponsor : CreditsHolder.SPONSORS) {
            var line = Component.literal(sponsor.name());
            if (!sponsor.description().isBlank()) line.append(" — " + sponsor.description());
            if (!sponsor.url().isBlank()) line.append("\n").append(link(sponsor.url()));
            lines.add(line);
        }
        if (lines.isEmpty()) lines.add(Component.translatable("gui.mmdskin.about.sponsors.empty"));
        return List.copyOf(lines);
    }

    private static Component link(String url) {
        return Component.literal(url).withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("gui.mmdskin.about.click_link"))));
    }

    private static List<SponsorCredits.Sponsor> loadSponsors() {
        try (InputStream input = AboutContent.class.getResourceAsStream(SPONSORS_RESOURCE)) {
            if (input == null) return List.of();
            return SponsorCredits.read(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            LOGGER.warn("读取赞助者名单失败，显示默认感谢信息", exception);
            return List.of();
        }
    }

    private static final class CreditsHolder {
        private static final List<SponsorCredits.Sponsor> SPONSORS = loadSponsors();
    }
}
