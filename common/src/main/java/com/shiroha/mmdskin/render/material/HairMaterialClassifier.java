package com.shiroha.mmdskin.render.material;

import java.util.Locale;
import java.util.regex.Pattern;

/** 保守识别发片；发饰保持全局阈值。 */
final class HairMaterialClassifier {
    private static final Pattern CAMEL_CASE = Pattern.compile("([a-z])([A-Z])");
    private static final Pattern ENGLISH_HAIR = Pattern.compile("(?:^|[^a-z])(?:hair|bangs|ponytail)(?:[^a-z]|$)");
    private static final String[] ACCESSORIES = {
            "accessor", "ornament", "ribbon", "hairpin", "hairband", "hairclip", "hairbow",
            "clip", "bow", "band", "饰", "飾", "リボン", "アクセ", "ピン"
    };

    private HairMaterialClassifier() {}

    static boolean matches(String name) {
        if (name == null || name.isBlank()) return false;
        String label = CAMEL_CASE.matcher(name).replaceAll("$1 $2").toLowerCase(Locale.ROOT);
        for (String accessory : ACCESSORIES) if (label.contains(accessory)) return false;
        return label.contains("髪") || label.contains("髮") || label.contains("发")
                || label.contains("ヘア") || label.contains("かみ") || ENGLISH_HAIR.matcher(label).find();
    }
}
