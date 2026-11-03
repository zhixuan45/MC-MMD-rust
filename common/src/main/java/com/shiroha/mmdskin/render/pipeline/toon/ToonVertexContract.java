package com.shiroha.mmdskin.render.pipeline.toon;

import java.util.regex.Pattern;

/** Deliberately small positive contract. Uniform agreement alone cannot rule out vertex jitter/warping. */
public final class ToonVertexContract {
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
    private ToonVertexContract() {}

    public static boolean canonical(String source) {
        String uncommented = COMMENTS.matcher(source).replaceAll(" ");
        if (Pattern.compile("(?m)^\\s*#\\s*(?:define|undef)\\s+(?:vec4|gl_Position|iris_ftransform|ChunkOffset|(?:iris_)?(?:Position|ProjMat|ModelViewMat))\\b")
                .matcher(uncommented).find()) return false;
        String code = uncommented.replaceAll("\\s+", "");
        if (code.contains("gl_ClipDistance") || code.contains("gl_CullDistance")
                || code.contains("gl_Layer") || code.contains("gl_ViewportIndex")) return false;
        // One complete position assignment; reject component writes, extra writes and inout uses.
        if (code.split("gl_Position", -1).length != 2) return false;
        for (String prefix : new String[]{"iris_", ""}) {
            String position = prefix + "Position", projection = prefix + "ProjMat", view = prefix + "ModelViewMat";
            if (!code.contains("invec3" + position + ";")) continue;
            for (String input : new String[]{position, position + "+ChunkOffset"}) {
                for (String one : new String[]{"1", "1.0"}) {
                    String transform = projection + "*" + view + "*vec4(" + input + "," + one + ")";
                    if (code.contains("gl_Position=" + transform + ";")) return true;
                    if (code.contains("gl_Position=iris_ftransform();")
                            && code.contains("vec4iris_ftransform(){return" + transform + ";}")) return true;
                }
            }
        }
        return false;
    }
}
