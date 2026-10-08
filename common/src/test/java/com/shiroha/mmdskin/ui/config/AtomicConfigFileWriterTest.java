package com.shiroha.mmdskin.ui.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 写入中途失败必须保留原配置，防止仅验证路径不可写而遗漏截断回归。 */
class AtomicConfigFileWriterTest {
    @TempDir
    Path directory;

    @Test
    void partialWriteFailurePreservesExistingFileAndRemovesTemporaryFile() throws Exception {
        Path target = directory.resolve("model_selector.json");
        String original = "{\"playerModels\":{\"player\":\"Original\"}}";
        Files.writeString(target, original);

        assertThrows(IllegalStateException.class, () -> AtomicConfigFileWriter.write(target.toFile(), writer -> {
            writer.write("{\"playerModels\":");
            throw new IOException("模拟写入过程中磁盘失败");
        }));

        assertEquals(original, Files.readString(target));
        // 清理失败临时文件后，目录中仍只应有此前完整的配置文件。
        try (var files = Files.list(directory)) {
            assertEquals(1L, files.count());
        }
    }
}
