package com.shiroha.mmdskin.ui.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UsageNoticeConfigTest {
    @TempDir
    Path directory;

    @Test
    void confirmationSurvivesRestartAndCreatesMissingDirectories() {
        Path file = directory.resolve("mmdskin/usage_notice.json");
        UsageNoticeConfig firstRun = new UsageNoticeConfig(file.toFile());
        assertFalse(firstRun.isAcknowledged());
        firstRun.acknowledge();
        assertTrue(new UsageNoticeConfig(file.toFile()).isAcknowledged());
    }

    @Test
    void invalidOrNonBooleanRecordsRequireConfirmation() throws Exception {
        Path file = directory.resolve("usage_notice.json");
        for (String content : new String[]{"", "{", "null", "{}", "{\"acknowledged\":false}",
                "{\"acknowledged\":\"true\"}", "{\"acknowledged\":1}"}) {
            Files.writeString(file, content);
            assertFalse(new UsageNoticeConfig(file.toFile()).isAcknowledged(), content);
        }
    }

    @Test
    void failedSaveDoesNotAcknowledgeAndCanBeRetried() throws Exception {
        Path parent = directory.resolve("mmdskin");
        Files.createFile(parent);
        UsageNoticeConfig config = new UsageNoticeConfig(parent.resolve("usage_notice.json").toFile());
        assertThrows(IllegalStateException.class, config::acknowledge);
        assertFalse(config.isAcknowledged());
        Files.delete(parent);
        config.acknowledge();
        assertTrue(new UsageNoticeConfig(parent.resolve("usage_notice.json").toFile()).isAcknowledged());
    }
}
