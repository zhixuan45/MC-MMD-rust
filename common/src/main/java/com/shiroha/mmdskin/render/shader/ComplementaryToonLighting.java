package com.shiroha.mmdskin.render.shader;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 复用当前包的光照函数，避免复制一套失去设置同步的颜色常量。 */
final class ComplementaryToonLighting {
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern INITIALIZER = Pattern.compile("(?s)\\s*(float|vec[234]|int|mat[234])\\s+(.+);\\s*");
    private static final Pattern DIFFUSE = Pattern.compile("fullShadow\\s*=\\s*shadow\\s*\\*\\s*max\\(\\s*NdotL\\s*,");

    private ComplementaryToonLighting() {}

    static String create(String fragment, String vertex) {
        if (vertex == null) return "";
        String source = clean(fragment);
        String vertexSource = clean(vertex);
        Matcher main = MAIN.matcher(source);
        if (!main.find() || !source.contains("void GetLighting(") || !source.contains("vec3 ScreenToView(")) return "";
        int sunStart = vertexSource.indexOf("const vec2 sunRotationData");
        Matcher up = Pattern.compile("upVec\\s*=\\s*normalize\\([^;]+;").matcher(vertexSource);
        if (sunStart < 0 || !up.find(sunStart)) return "";
        String sunSetup = vertexSource.substring(sunStart, up.end());
        Matcher time = Pattern.compile("float\\s+timeAngleM\\s*=\\s*([^;]+);").matcher(vertexSource);
        if (!time.find()) return "";
        sunSetup = "float timeAngleM = " + time.group(1) + ";\n" + sunSetup;
        source = source.substring(0, main.start()).replaceAll("(?m)^\\s*#(?:version|extension|line)[^\\n]*", "")
                .replaceAll("\\btexture\\b", "ToonPackTexture")
                .replaceAll("\\btexture2D\\b", "texture")
                .replaceAll("\\btexture2DLod\\b", "textureLod")
                .replaceAll("\\bshadow2D\\b", "toonShadow2D");
        // 太阳与上方向沿用原顶点程序的矩阵，不能依赖被手部程序优化掉的逆矩阵。
        if (!Pattern.compile("\\buniform\\s+mat4\\s+[^;]*\\bgbufferModelView\\b[^;]*;").matcher(source).find()) {
            source = "uniform mat4 gbufferModelView;\n" + source;
        }
        int varyingStart = source.indexOf("varying ");
        if (varyingStart < 0) return "";
        StringBuilder before = new StringBuilder(), after = new StringBuilder();
        String head = moveInitializers(source.substring(0, varyingStart), before);
        String tail = moveInitializers(source.substring(varyingStart).replaceAll("\\bvarying\\s+", ""), after);
        // 阴影偏移保留连续 NdotL，仅对采样后的漫反射权重分档。
        Matcher diffuse = DIFFUSE.matcher(tail);
        if (diffuse.find()) {
            if (diffuse.find()) return "";
            tail = DIFFUSE.matcher(tail).replaceFirst("fullShadow = shadow * max(toonDiffuseRamp(NdotL),");
        } else if (!tail.contains("vec3 sceneLighting = normalize(sqrt(max(fogColor,")) {
            // Nether 只有环境光；其它未知漫反射形式不套用此适配。
            return "";
        }
        return "vec4 toonShadow2D(sampler2DShadow s, vec3 p) { return vec4(texture(s, p)); }\n"
                + head + tail + "\nvec3 applyPackLighting(vec3 raw, vec3 n, vec3 p) {\n"
                + before + sunSetup + "\nnormal = n; texCoord = texCoord0; lmCoord = ToonLightmap; color = vec4(1.0);\n"
                + after + "\nlmCoord.x -= max(lmCoord.x - 0.825, 0.0) * 0.75;\n"
                + "float ndl = clamp(dot(n, lightVec) * 1.01 - 0.01, 0.0, 1.0);\n"
                + "float band = toonDiffuseRamp(ndl);\n"
                + "vec3 lit = vec3(1.0);\n"
                + "vec3 shadow = vec3(1.0), lightAlbedo = vec3(0.0);\n"
                + "vec3 world = (gbufferModelViewInverse * vec4(p, 1.0)).xyz;\n"
                // 固定上方向遮蔽，避免色阶之外再叠一层连续的方块式面明暗。
                + "GetLighting(lit, shadow, lightAlbedo, p, length(p), world, lmCoord, 1.0, ndl, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0);\n"
                + "vec3 illumination = pow(max(lit, vec3(0.0)), vec3(1.0 / 2.2));\n"
                + "return shadeToonSurface(raw, n, lightVec, p, illumination, band);\n}\n";
    }

    private static String clean(String source) {
        return COMMENTS.matcher(source).replaceAll(" ");
    }

    /** 非常量全局初值必须在 sunVec 和当前片段输入赋值以后执行。 */
    private static String moveInitializers(String source, StringBuilder initialization) {
        StringBuilder output = new StringBuilder();
        int depth = 0, start = 0;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') depth++;
            if (ch == '}' && --depth == 0) {
                output.append(source, start, i + 1);
                start = i + 1;
            }
            if (ch != ';' || depth != 0) continue;
            String statement = source.substring(start, i + 1);
            Matcher match = INITIALIZER.matcher(statement);
            if (match.matches() && match.group(2).contains("=")) {
                List<String> variables = splitVariables(match.group(2));
                output.append('\n').append(match.group(1)).append(' ');
                for (int j = 0; j < variables.size(); j++) {
                    String variable = variables.get(j);
                    int equals = variable.indexOf('=');
                    String name = equals < 0 ? variable.trim() : variable.substring(0, equals).trim();
                    if (j > 0) output.append(", ");
                    output.append(name);
                    if (equals >= 0) initialization.append(name.replaceAll("\\[[^]]+]", ""))
                            .append(variable.substring(equals)).append(";\n");
                }
                output.append(';');
            } else output.append(statement);
            start = i + 1;
        }
        return output.append(source.substring(start)).toString();
    }

    private static List<String> splitVariables(String declarations) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < declarations.length(); i++) {
            char ch = declarations.charAt(i);
            if (ch == '(' || ch == '[') depth++;
            if (ch == ')' || ch == ']') depth--;
            if (ch == ',' && depth == 0) {
                parts.add(declarations.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(declarations.substring(start));
        return parts;
    }
}
