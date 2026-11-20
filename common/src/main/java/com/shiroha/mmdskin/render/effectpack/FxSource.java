package com.shiroha.mmdskin.render.effectpack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class FxSource {
    private FxSource() { }

    static final class Budget {
        private static final int MAX_FILE_BYTES = 1024 * 1024;
        private int remaining = 64 * MAX_FILE_BYTES;

        String read(Path file) throws IOException {
            if (remaining <= 0) throw new IOException("本次扫描的 FX 源码总量超过 64 MiB");
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES)
                throw new IOException("FX 源码不是普通文件或超过 1 MiB");
            int limit = Math.min(MAX_FILE_BYTES, remaining);
            try (var input = Files.newInputStream(file)) {
                byte[] bytes = input.readNBytes(limit + 1);
                remaining -= bytes.length;
                if (bytes.length > limit) throw new IOException("FX 源码超出读取预算");
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            }
        }
    }

    static String withoutComments(String text) {
        // 引号中的路径不能被当作注释；保留换行便于诊断和预处理器匹配。
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') { quoted = !quoted; out.append(c); }
            else if (!quoted && c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (!quoted && c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i += 2;
                while (i < text.length() && !(text.charAt(i) == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/')) {
                    if (text.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i++;
                out.append(' ');
            } else out.append(c);
        }
        return out.toString().replace("\uFEFF", "");
    }

    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
