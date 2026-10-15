package com.shiroha.mmdskin.ui.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 按启动账号保存须知确认，复制其他账号的配置不会跳过提示。 */
public final class UsageNoticeConfig {
    private static final Logger LOGGER = LogManager.getLogger();
    private final File file;
    private final Set<UUID> acknowledgedAccounts = new LinkedHashSet<>();

    public UsageNoticeConfig(File file) {
        this.file = file;
        if (!file.exists()) return;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonElement accounts = root.get("acknowledgedAccounts");
            // 旧版全局 acknowledged=true 不能代表当前账号已经阅读。
            if (accounts == null || !accounts.isJsonArray()) return;
            for (JsonElement value : accounts.getAsJsonArray()) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
                try {
                    String text = value.getAsString();
                    UUID accountId = UUID.fromString(text);
                    if (accountId.toString().equalsIgnoreCase(text)) acknowledgedAccounts.add(accountId);
                } catch (IllegalArgumentException ignored) {
                    // 单条损坏记录不影响其他账号。
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("读取模组使用须知确认记录失败，将重新显示提示", exception);
        }
    }

    public boolean isAcknowledged(UUID accountId) {
        return acknowledgedAccounts.contains(Objects.requireNonNull(accountId, "accountId"));
    }

    public void acknowledge(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        if (acknowledgedAccounts.contains(accountId)) return;
        JsonArray accounts = new JsonArray();
        acknowledgedAccounts.forEach(id -> accounts.add(id.toString()));
        accounts.add(accountId.toString());
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.add("acknowledgedAccounts", accounts);
        AtomicConfigFileWriter.write(file, writer -> new GsonBuilder().setPrettyPrinting().create().toJson(root, writer));
        // 只有落盘成功才更新内存，写入失败允许重试。
        acknowledgedAccounts.add(accountId);
    }
}
