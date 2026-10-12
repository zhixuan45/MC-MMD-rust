package com.shiroha.mmdskin.render.material;

import java.util.Locale;
import java.util.regex.Pattern;

/** 材质语义补充眼骨遮罩；共享脸贴图不参与判断。 */
public final class EyeMaterialMask {
    private static final String[] EXCLUDED = {
            "sclera", "eyewhite", "eye_white", "whiteeye", "white_eye", "眼白", "白目", "白眼",
            "lens", "glass", "spectacle", "镜片", "眼镜", "眼鏡", "メガネ", "レンズ", "角膜",
            "eyelash", "eyelid", "eyebrow", "eyeline", "eyeliner", "eye_shadow", "eyeshadow",
            "眉", "睫", "眼睑", "眼瞼", "まつげ", "アイライン", "アイラッシュ"
    };
    private static final Pattern IRIS_OR_PUPIL = Pattern.compile("(?:^|[^a-z])(?:iris|pupil)(?:[^a-z]|$)");
    private static final Pattern EYE_HIGHLIGHT = Pattern.compile(
            "(?:^|[^a-z])eyes?[_ .-]?(?:hi(?:ghlight)?[0-9]*|highlight)(?:[^a-z]|$)");

    private EyeMaterialMask() {}

    /** -1 排除，0 按眼骨，1 整个明确眼瞳材质。 */
    public static float classify(String name) {
        if (name == null || name.isBlank()) return 0;
        String label = name.toLowerCase(Locale.ROOT);
        // 排除优先，防止 iris_lens 等叠加镜片被当作虹膜。
        for (String token : EXCLUDED) if (label.contains(token)) return -1;
        if (IRIS_OR_PUPIL.matcher(label).find() || EYE_HIGHLIGHT.matcher(label).find()
                || label.contains("瞳") || label.contains("虹彩") || label.contains("虹膜")
                || label.contains("黒目") || label.contains("黑目") || label.contains("眼内高光")) return 1;
        return 0;
    }
}
