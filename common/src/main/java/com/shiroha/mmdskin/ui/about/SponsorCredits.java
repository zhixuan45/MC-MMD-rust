package com.shiroha.mmdskin.ui.about;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** 赞助者数据独立于界面和个人配置，便于维护发布包中的感谢名单。 */
public final class SponsorCredits {
    public record Sponsor(String name, String description, String url) {}

    private SponsorCredits() {}

    public static List<Sponsor> read(Reader reader) {
        List<Sponsor> sponsors = new ArrayList<>();
        for (JsonElement element : JsonParser.parseReader(reader).getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            String name = text(entry, "name");
            if (name.isBlank()) continue;
            String url = text(entry, "url");
            if (!isWebUrl(url)) url = "";
            sponsors.add(new Sponsor(name, text(entry, "description"), url));
        }
        return List.copyOf(sponsors);
    }

    private static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString().trim() : "";
    }

    private static boolean isWebUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
