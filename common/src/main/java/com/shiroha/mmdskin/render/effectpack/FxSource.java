package com.shiroha.mmdskin.render.effectpack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class FxSource {
    private FxSource() { }

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
