package com.shiroha.mmdskin.render.shader;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 复用当前包的光照函数，避免复制一套失去设置同步的颜色常量。 */
final class ComplementaryToonLighting {
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern DO_LIGHTING = Pattern.compile("\\bvoid\\s+DoLighting\\s*\\(([^)]*)\\)");
    private static final Pattern INITIALIZER = Pattern.compile("(?s)\\s*(float|vec[234]|int|mat[234])\\s+(.+);\\s*");
    private static final Pattern MODERN_DIFFUSE = Pattern.compile("shadowMult\\s*\\*=\\s*max\\(\\s*NdotLM\\s*\\*\\s*shadowTime\\s*,");
    private static final Pattern MODERN_SHADOW_GATE = Pattern.compile("if\\s*\\(\\s*NdotLM\\s*>\\s*0\\.0001\\s*\\)");
    private static final Pattern DIFFUSE = Pattern.compile("fullShadow\\s*=\\s*shadow\\s*\\*\\s*max\\(\\s*NdotL\\s*,");
    private static final Pattern SHADOW_GATE = Pattern.compile("\\bNdotL\\s*>\\s*0\\.0\\b");

    private ComplementaryToonLighting() {}

    /** 5.x 直接以纹理颜色调用 DoLighting，不能沿用 4.x 的线性 GetLighting 协议。 */
    static String createModern(String fragment, String vertex) {
        return createModern(fragment, vertex, false);
    }

    /** r5.1.x 的 DoLighting 只有 15 个参数，不能复用新版 18 参数调用。 */
    static String createModernLegacy(String fragment, String vertex) {
        return createModern(fragment, vertex, true);
    }

    private static String createModern(String fragment, String vertex, boolean legacy) {
        if (vertex == null) return "";
        String source = clean(fragment);
        String vertexSource = clean(vertex);
        Matcher main = MAIN.matcher(source);
        Matcher sun = Pattern.compile("\\bsunVec\\s*=\\s*([^;]+);").matcher(vertexSource);
        if (!main.find() || !sun.find() || !source.contains("vec3 ViewToPlayer(")) return "";
        String lightingCall = doLightingCall(source, legacy);
        if (lightingCall.isEmpty()) return "";
        String sunHelper = "";
        if (sun.group(1).contains("GetSunVector(")) {
            sunHelper = extractSafeSunVectorFunction(vertexSource);
            if (sunHelper.isEmpty()) return "";
        }
        source = source.substring(0, main.start())
                .replaceAll("(?m)^\\s*#(?:version|extension|line)[^\\n]*", "")
                // 去掉片段输入限定符，但保留函数参数的 in/inout。
                .replaceAll("(?m)^\\s*(?:flat\\s+)?in\\s+(vec[234]\\s+[^;]+;)", "$1")
                .replaceAll("\\btexture2D\\b", "texture")
                .replaceAll("\\btexture2DLod\\b", "textureLod");
        source = adaptShadowSampler(source);
        source = source.replaceAll("\\btoonShadow2D\\(\\s*shadowtex1\\s*,", "toonOpaqueShadow2D(shadowtex1,")
                .replaceAll("\\btoonTerrainShadow\\(\\s*shadowtex1\\s*,", "toonTerrainOpaqueShadow(shadowtex1,");
        // 原包颜色解码依赖逐片段光图参数；等 colorMult/colorPow
        // 就绪后只做一次 3x3 过滤，避免在每次原生采样里嵌套 PCF。
        boolean transmission = source.contains("toonShadowColor(shadowcolor0,")
                && source.contains("toonOpaqueShadow2D(shadowtex1,")
                && Pattern.compile("(?:\\bshadow\\s*=\\s*Sample(?:TAA)?FilteredShadow\\s*\\(|\\breturn\\s+SampleShadow\\s*\\()")
                        .matcher(source).find();
        if (transmission) {
            source = source.replace(
                    "if (ToonTerrainShadowEnabled && ToonWorldShadowSoftness > 0.0) return vec3(toonFilteredTerrainShadow(shadowPos));",
                    "if (ToonTerrainShadowEnabled && !ToonTerrainShadowTransmissionEnabled && ToonWorldShadowSoftness > 0.0)"
                            + " return vec3(toonFilteredTerrainShadow(shadowPos));");
            source = source.replaceAll("(float\\s+colorPow\\s*=\\s*[^;]+;)",
                    "$1\nif (ToonTerrainShadowEnabled && ToonTerrainShadowTransmissionEnabled"
                            + " && ToonWorldShadowSoftness > 0.0)"
                            + " return toonComplementaryFilteredTransmission(shadowPos, colorMult, colorPow);\n");
        }
        // 半透明程序包含旧固定管线雾函数；即使调用被优化掉，也须能在 core GLSL 中解析。
        if (source.contains("gl_Fog.")) {
            source = "uniform float iris_FogStart;\nuniform float iris_FogEnd;\n" + source
                    .replace("gl_Fog.start", "iris_FogStart")
                    .replace("gl_Fog.scale", "(1.0 / max(iris_FogEnd - iris_FogStart, 0.0001))");
        }
        int inputs = source.indexOf("vec2 texCoord;");
        if (inputs < 0) return "";
        StringBuilder before = new StringBuilder(), after = new StringBuilder();
        String head = moveInitializers(source.substring(0, inputs), before);
        String tail = moveInitializers(source.substring(inputs), after);
        // 保留阴影采样偏移的连续法线，仅对采样后的直射光权重分档。
        boolean sunlight = MODERN_DIFFUSE.matcher(tail).find() && MODERN_SHADOW_GATE.matcher(tail).find()
                && Pattern.compile("shadowMult\\s*\\*=\\s*GetShadow\\s*\\(").matcher(tail).find();
        tail = tail.replaceAll("\\bGetShadowPos\\(\\s*playerPosM\\s*\\)",
                "GetShadowPos(ToonTerrainShadowEnabled ? playerPos : playerPosM)");
        if (sunlight) tail = MODERN_SHADOW_GATE.matcher(tail)
                .replaceAll("if (ToonTerrainShadowEnabled || toonSunlightStrength > 0.0 || NdotLM > 0.0001)");
        tail = MODERN_DIFFUSE.matcher(tail).replaceAll(sunlight
                ? "shadowMult *= max(toonSunDiffuseRamp(NdotLM, shadowMult) * shadowTime,"
                : "shadowMult *= max(toonDiffuseRamp(NdotLM) * shadowTime,");
        return shadowSamplerWrapper(true) + (transmission ? modernTransmissionFilter(legacy) : "")
                + head + sunHelper + tail + "\nvec3 applyPackLighting(vec3 raw, vec3 n, vec3 p) {\n"
                + before + "\nupVec = normalize(gbufferModelView[1].xyz);\n"
                + "northVec = normalize(gbufferModelView[2].xyz); eastVec = normalize(gbufferModelView[0].xyz);\n"
                + "sunVec = " + sun.group(1) + ";\n"
                + "normal = n; texCoord = texCoord0; lmCoord = ToonLightmap; glColor = vec4(1.0);\n"
                + after + "\nfloat ndl = max(dot(n, lightVec), 0.0);\n"
                + (sunlight ? "toonPrepareSunlight(sunVec, lightVec, upVec);\n" : "")
                + "float band = toonDiffuseRamp(ndl);\n"
                + "vec4 lit = vec4(1.0);\n"
                + "vec3 shadow = vec3(1.0); vec3 player = ViewToPlayer(p);\n"
                + "vec3 sampleNormal = n;\n"
                + "vec3 worldNormal = normalize(mat3(gbufferModelViewInverse) * sampleNormal);\n"
                + (lightingCall.contains("purkinjeOverwrite") ? "float purkinjeOverwrite = 0.0;\n" : "")
                + (lightingCall.contains("enderDragonDead") ? "float enderDragonDead = 1.0;\n" : "")
                + lightingCall
                + "return shadeToonSurface(raw, n, lightVec, p, max(lit.rgb, vec3(0.0)), "
                + "band);\n}\n";
    }

    private static String extractSafeSunVectorFunction(String vertexSource) {
        Matcher definition = Pattern.compile("\\bvec3\\s+GetSunVector\\s*\\(\\s*\\)\\s*\\{").matcher(vertexSource);
        if (!definition.find()) return "";
        int depth = 1, index = definition.end();
        while (index < vertexSource.length() && depth > 0) {
            char ch = vertexSource.charAt(index++);
            if (ch == '{') depth++;
            else if (ch == '}') depth--;
        }
        if (depth != 0) return "";
        String function = vertexSource.substring(definition.start(), index);
        if (definition.find(index)) return "";
        String compact = function.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        if (!compact.contains("sunpathrotation")
                || (!compact.contains("returnnormalize(") && !compact.contains("returnvec3(0.0);"))
                || Pattern.compile("\\b(?:texture|imagetexel|atomic|buffer|shadow|gl_fragdata)\\b")
                        .matcher(function.toLowerCase(java.util.Locale.ROOT)).find()) return "";
        return function + "\n";
    }

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
                .replaceAll("\\btexture2DLod\\b", "textureLod");
        source = adaptShadowSampler(source);
        // 太阳与上方向沿用原顶点程序的矩阵，不能依赖被手部程序优化掉的逆矩阵。
        if (!Pattern.compile("\\buniform\\s+mat4\\s+[^;]*\\bgbufferModelView\\b[^;]*;").matcher(source).find()) {
            source = "uniform mat4 gbufferModelView;\n" + source;
        }
        int varyingStart = source.indexOf("varying ");
        if (varyingStart < 0) return "";
        StringBuilder before = new StringBuilder(), after = new StringBuilder();
        String head = moveInitializers(source.substring(0, varyingStart), before);
        String tail = moveInitializers(source.substring(varyingStart).replaceAll("\\bvarying\\s+", ""), after);
        tail = tail.replaceAll("(\\bfloat\\s+NdotLm\\s*=\\s*)([^;]+);",
                "$1(ToonTerrainShadowEnabled ? 1.0 : ($2));");
        // 阴影偏移保留连续 NdotL，仅对采样后的漫反射权重分档。
        Matcher diffuse = DIFFUSE.matcher(tail);
        boolean sunlight = false;
        if (diffuse.find()) {
            if (diffuse.find()) return "";
            sunlight = SHADOW_GATE.matcher(tail).find()
                    && Pattern.compile("\\bshadow\\s*=\\s*GetShadow\\s*\\(").matcher(tail).find();
            if (sunlight) tail = SHADOW_GATE.matcher(tail)
                    .replaceAll("(ToonTerrainShadowEnabled || toonSunlightStrength > 0.0 || NdotL > 0.0)");
            tail = DIFFUSE.matcher(tail).replaceFirst(sunlight
                    ? "fullShadow = shadow * max(toonSunDiffuseRamp(NdotL, shadow),"
                    : "fullShadow = shadow * max(toonDiffuseRamp(NdotL),");
        } else if (!tail.contains("vec3 sceneLighting = normalize(sqrt(max(fogColor,")) {
            // Nether 只有环境光；其它未知漫反射形式不套用此适配。
            return "";
        }
        return shadowSamplerWrapper() + head + tail + "\nvec3 applyPackLighting(vec3 raw, vec3 n, vec3 p) {\n"
                + before + sunSetup + "\nnormal = n; texCoord = texCoord0; lmCoord = ToonLightmap; color = vec4(1.0);\n"
                + after + "\nlmCoord.x -= max(lmCoord.x - 0.825, 0.0) * 0.75;\n"
                + (sunlight ? "toonPrepareSunlight(sunVec, lightVec, upVec);\n" : "")
                + "float ndl = clamp(dot(n, lightVec) * 1.01 - 0.01, 0.0, 1.0);\n"
                + "float band = toonDiffuseRamp(ndl);\n"
                + "vec3 lit = vec3(1.0);\n"
                + "vec3 shadow = vec3(1.0), lightAlbedo = vec3(0.0);\n"
                + "vec3 world = (gbufferModelViewInverse * vec4(p, 1.0)).xyz;\n"
                // 固定上方向遮蔽，避免色阶之外再叠一层连续的方块式面明暗。
                + "GetLighting(lit, shadow, lightAlbedo, p, length(p), world, lmCoord, 1.0, ndl, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0);\n"
                + "vec3 illumination = pow(max(lit, vec3(0.0)), vec3(1.0 / 2.2));\n"
                + "return shadeToonSurface(raw, n, lightVec, p, illumination, "
                + "band);\n}\n";
    }

    static String adaptShadowSampler(String source) {
        source = source.replaceAll("\\bshadow2D\\b", "toonShadow2D");
        Matcher samplers = Pattern.compile("\\buniform\\s+sampler2DShadow\\s+(\\w+)\\s*;").matcher(source);
        String adapted = source;
        while (samplers.find()) {
            adapted = adapted.replaceAll("\\btexture\\s*\\(\\s*" + Pattern.quote(samplers.group(1)) + "\\s*,",
                    "toonTerrainShadow(" + samplers.group(1) + ",");
        }
        // Opaque terrain has no transmission color; host colors can contain later entity casters.
        adapted = adapted.replaceAll("\\btexture\\s*\\(\\s*(shadowcolor\\d+)\\s*,", "toonShadowColor($1,");
        // Filter once at the pack's final biased shadow coordinate, rather than nesting PCF per tap.
        return adapted.replaceAll(
                "(\\bvec3\\s+(?:GetShadow|SampleBasicShadow|SampleFilteredShadow)\\s*\\(\\s*vec3\\s+shadowPos\\b[^)]*\\)\\s*\\{)",
                "$1\nif (ToonTerrainShadowEnabled && ToonWorldShadowSoftness > 0.0) return vec3(toonFilteredTerrainShadow(shadowPos));\n");
    }

    static String shadowSamplerWrapper() {
        return shadowSamplerWrapper(false);
    }

    static String shadowSamplerWrapper(boolean transmission) {
        return "float toonTerrainShadow(sampler2DShadow s, vec3 p) {\n"
                + "    if (ToonTerrainShadowEnabled) toonHasTerrainShadowSample = true;\n"
                + "    return ToonTerrainShadowEnabled ? "
                + (transmission ? "(ToonTerrainShadowTransmissionEnabled ? texture(ToonTerrainShadowTranslucentDepth, p)"
                        + " : texture(ToonTerrainShadowDepth, p))" : "texture(ToonTerrainShadowDepth, p)")
                + " : texture(s, p);\n"
                + "}\n"
                + "float toonTerrainOpaqueShadow(sampler2DShadow s, vec3 p) {\n"
                + "    if (ToonTerrainShadowEnabled) toonHasTerrainShadowSample = true;\n"
                + "    float visibility = ToonTerrainShadowEnabled ? texture(ToonTerrainShadowDepth, p) : texture(s, p);\n"
                + (transmission ? "    if (ToonTerrainShadowEnabled && ToonTerrainShadowTransmissionEnabled)"
                        + " toonRecordOpaqueSunVisibility(visibility, 1.0);\n" : "")
                + "    return visibility;\n"
                + "}\n"
                + "vec4 toonShadow2D(sampler2DShadow s, vec3 p) { return vec4(toonTerrainShadow(s, p)); }\n"
                + "vec4 toonOpaqueShadow2D(sampler2DShadow s, vec3 p) { return vec4(toonTerrainOpaqueShadow(s, p)); }\n"
                + "vec4 toonShadowColor(sampler2D s, vec2 p) {\n"
                + "    return ToonTerrainShadowEnabled ? (ToonTerrainShadowTransmissionEnabled"
                + " ? texture(ToonTerrainShadowColor, p) : vec4(0.0)) : texture(s, p);\n"
                + "}\n";
    }

    private static String modernTransmissionFilter(boolean legacy) {
        String opaqueCondition = legacy
                ? "if (full < 1.0 && opaque > 0.9999)"
                : "if (opaque * 0.9999 > full)";
        return """
                vec3 toonComplementaryFilteredTransmission(vec3 p, float colorMult, float colorPow) {
                    vec2 radius = clamp(ToonWorldShadowSoftness, 0.0, 4.0)
                            / vec2(textureSize(ToonTerrainShadowDepth, 0));
                    vec3 shadow = vec3(0.0);
                    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
                        vec2 uv = p.xy + vec2(x, y) * radius;
                        float full = texture(ToonTerrainShadowTranslucentDepth, vec3(uv, p.z));
                        float opaque = texture(ToonTerrainShadowDepth, vec3(uv, p.z));
                        vec3 transmission = vec3(0.0);
                        %s
                            transmission = pow(max(texture(ToonTerrainShadowColor, uv).rgb * opaque * colorMult,
                                    vec3(0.0)), vec3(colorPow));
                        float weight = (x == 0 ? 2.0 : 1.0) * (y == 0 ? 2.0 : 1.0);
                        toonRecordOpaqueSunVisibility(opaque, weight);
                        shadow += (transmission * (1.0 - full) + full) * weight;
                    }
                    return shadow * 0.0625;
                }
                """.formatted(opaqueCondition);
    }

    static String clean(String source) {
        return COMMENTS.matcher(source).replaceAll(" ");
    }

    /** 非常量全局初值必须在 sunVec 和当前片段输入赋值以后执行。 */
    static String moveInitializers(String source, StringBuilder initialization) {
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

    private static String doLightingCall(String source, boolean legacy) {
        Matcher signature = DO_LIGHTING.matcher(source);
        if (!signature.find()) return "";
        String rawParams = signature.group(1).trim();
        if (rawParams.isEmpty()) {
            return legacy
                    ? "DoLighting(lit, shadow, player, p, length(p), sampleNormal, lmCoord,\n"
                    + "           false, true, true, true, 0, 0.0, 0.0, 0.0);\n"
                    : "DoLighting(lit, shadow, player, p, length(p), sampleNormal, sampleNormal, 0.5, worldNormal, lmCoord,\n"
                    + "           false, true, true, true, 0, 0.0, 0.0, 0.0);\n";
        }
        String[] parameters = rawParams.split(",");
        if (legacy && parameters.length == 15) {
            return "DoLighting(lit, shadow, player, p, length(p), sampleNormal, lmCoord,\n"
                    + "           false, true, true, true, 0, 0.0, 0.0, 0.0);\n";
        }
        List<String> arguments = new ArrayList<>(parameters.length);
        for (String parameter : parameters) {
            String[] tokens = parameter.trim().split("\\s+");
            String name = tokens[tokens.length - 1].toLowerCase(java.util.Locale.ROOT);
            String argument = switch (name) {
                case "color" -> "lit";
                case "shadowmult" -> "shadow";
                case "playerpos" -> "player";
                case "viewpos" -> "p";
                case "lviewpos" -> "length(p)";
                case "geonormal", "normalm" -> "sampleNormal";
                case "dither" -> "0.5";
                case "worldgeonormal" -> "worldNormal";
                case "lightmap" -> "lmCoord";
                case "nosmoothlighting" -> "false";
                case "nodirectionalshading", "novanillaao", "centershadowbias" -> "true";
                case "subsurfacemode" -> "0";
                case "smoothnessg", "highlightmult", "emission" -> "0.0";
                case "purkinjeoverwrite" -> "purkinjeOverwrite";
                case "islightsource" -> "false";
                case "enderdragondead" -> "enderDragonDead";
                case "metalhighlighttint" -> "vec3(1.0)";
                default -> null;
            };
            if (argument == null) return "";
            arguments.add(argument);
        }
        return "DoLighting(" + String.join(", ", arguments) + ");\n";
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
