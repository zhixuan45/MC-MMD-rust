package com.shiroha.mmdskin.ui.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 本地使用须知确认记录，独立于模型配置，只有成功保存后才视为已确认。 */
public final class UsageNoticeConfig {
    private static final Logger LOGGER = LogManager.getLogger();
    private final File file;
    private boolean acknowledged;

    public UsageNoticeConfig(File file) {
        this.file = file;
        if (!file.exists()) return;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement value = JsonParser.parseReader(reader).getAsJsonObject().get("acknowledged");
            acknowledged = value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
        } catch (Exception exception) {
            LOGGER.warn("读取模组使用须知确认记录失败，将重新显示提示", exception);
        }
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public void acknowledge() {
        AtomicConfigFileWriter.write(file, writer -> writer.write("{\n  \"acknowledged\": true\n}\n"));
        acknowledged = true;
    }
}
