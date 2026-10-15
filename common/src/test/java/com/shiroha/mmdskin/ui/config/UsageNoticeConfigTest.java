package com.shiroha.mmdskin.ui.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UsageNoticeConfigTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BUYER = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @TempDir
    Path directory;

    @Test
    void confirmationSurvivesRestartAndCreatesMissingDirectories() {
        Path file = directory.resolve("mmdskin/usage_notice.json");
        UsageNoticeConfig firstRun = new UsageNoticeConfig(file.toFile());
        assertFalse(firstRun.isAcknowledged(OWNER));
        firstRun.acknowledge(OWNER);
        assertTrue(new UsageNoticeConfig(file.toFile()).isAcknowledged(OWNER));
    }

    @Test
    void bundledConfirmationDoesNotAcknowledgeAnotherAccount() throws Exception {
        Path original = directory.resolve("original.json");
        new UsageNoticeConfig(original.toFile()).acknowledge(OWNER);
        Path bundled = directory.resolve("bundled.json");
        Files.copy(original, bundled);
        UsageNoticeConfig copied = new UsageNoticeConfig(bundled.toFile());
        assertTrue(copied.isAcknowledged(OWNER));
        assertFalse(copied.isAcknowledged(BUYER));
    }

    @Test
    void multipleAccountsRetainTheirOwnConfirmations() {
        Path file = directory.resolve("usage_notice.json");
        UsageNoticeConfig config = new UsageNoticeConfig(file.toFile());
        config.acknowledge(OWNER);
        new UsageNoticeConfig(file.toFile()).acknowledge(BUYER);
        UsageNoticeConfig restarted = new UsageNoticeConfig(file.toFile());
        assertTrue(restarted.isAcknowledged(OWNER));
        assertTrue(restarted.isAcknowledged(BUYER));
    }

    @Test
    void legacyAndMalformedRecordsRequireConfirmation() throws Exception {
        Path file = directory.resolve("usage_notice.json");
        for (String content : new String[]{"", "{", "null", "{}", "{\"acknowledged\":true}",
                "{\"acknowledgedAccounts\":true}", "{\"acknowledgedAccounts\":[true,1,null,\"invalid\"]}"}) {
            Files.writeString(file, content);
            assertFalse(new UsageNoticeConfig(file.toFile()).isAcknowledged(OWNER), content);
        }
    }

    @Test
    void malformedAccountEntryDoesNotDiscardOtherAccounts() throws Exception {
        Path file = directory.resolve("usage_notice.json");
        Files.writeString(file, "{\"acknowledgedAccounts\":[null,\"invalid\",\"" + OWNER + "\",false]}");
        UsageNoticeConfig config = new UsageNoticeConfig(file.toFile());
        assertTrue(config.isAcknowledged(OWNER));
        assertFalse(config.isAcknowledged(BUYER));
    }

    @Test
    void failedSaveDoesNotAcknowledgeAndCanBeRetried() throws Exception {
        Path parent = directory.resolve("mmdskin");
        Files.createFile(parent);
        UsageNoticeConfig config = new UsageNoticeConfig(parent.resolve("usage_notice.json").toFile());
        assertThrows(IllegalStateException.class, () -> config.acknowledge(OWNER));
        assertFalse(config.isAcknowledged(OWNER));
        Files.delete(parent);
        config.acknowledge(OWNER);
        assertTrue(new UsageNoticeConfig(parent.resolve("usage_notice.json").toFile()).isAcknowledged(OWNER));
    }
}
