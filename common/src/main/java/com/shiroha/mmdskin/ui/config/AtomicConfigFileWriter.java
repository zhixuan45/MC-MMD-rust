package com.shiroha.mmdskin.ui.config;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 文件职责：用同目录临时文件原子替换 JSON 配置，避免写入中断截断原文件。 */
final class AtomicConfigFileWriter {
    @FunctionalInterface
    interface ContentWriter {
        void write(Writer writer) throws Exception;
    }

    private AtomicConfigFileWriter() {
    }

    static void write(File destination, ContentWriter contentWriter) {
        Path target = destination.toPath().toAbsolutePath();
        Path parent = target.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary.toFile());
                 Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                contentWriter.write(writer);
                writer.flush();
                output.getFD().sync();
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                // 某些文件系统不支持原子移动，仍使用同目录替换并保留完整临时文件内容。
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("模型选择配置原子保存失败: " + destination, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // 临时文件清理失败不覆盖主要保存错误；下次保存会创建新的唯一临时文件。
                }
            }
        }
    }
}
