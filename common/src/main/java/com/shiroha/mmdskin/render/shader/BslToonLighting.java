package com.shiroha.mmdskin.render.shader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从已确认的 BSL 协议提取照明，保留原包阴影变形、天气、维度与灯光设置。 */
final class BslToonLighting {
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern LIGHTING = Pattern.compile("void\\s+GetLighting\\s*\\(([^)]*)\\)\\s*\\{");
    private static final Pattern DIFFUSE = Pattern.compile("fullShadow\\s*=\\s*max\\(\\s*shadow\\s*\\*\\s*NoL\\s*,");

    private BslToonLighting() {}

    static String create(String fragment, String vertex, boolean hand) {
        if (vertex == null) return "";
        String source = ComplementaryToonLighting.clean(fragment);
        String vertexSource = ComplementaryToonLighting.clean(vertex);
        Matcher main = MAIN.matcher(source);
        Matcher lighting = LIGHTING.matcher(source);
        int sunStart = vertexSource.indexOf("const vec2 sunRotationData");
        Matcher east = Pattern.compile("eastVec\\s*=\\s*normalize\\([^;]+;").matcher(vertexSource);
        if (!main.find() || !lighting.find() || sunStart < 0 || !east.find(sunStart)
                || !source.contains("vec3 ToWorld(")) return "";
        String[] parameters = lighting.group(1).split(",");
        if (parameters.length != 13) return "";
        Matcher shadowParameter = Pattern.compile("\\b(?:out|inout)\\s+vec3\\s+(\\w+)\\s*$").matcher(parameters[1]);
        if (!shadowParameter.find()) return "";
        String shadowName = shadowParameter.group(1);
        String sunSetup = vertexSource.substring(sunStart, east.end());
        boolean nether = Pattern.compile("vec3\\s+sceneLighting\\s*=\\s*netherColSqrt").matcher(source).find();
        String calls = source.substring(main.start());
        source = source.substring(0, main.start())
                .replaceAll("(?m)^\\s*#(?:version|extension|line)[^\\n]*", "")
                .replaceAll("\\btexture\\b", "ToonPackTexture")
                .replaceAll("\\btexture2DGradARB\\b", "textureGrad")
                .replaceAll("\\btexture2DLod\\b", "textureLod")
                .replaceAll("\\btexture2D\\b", "texture")
                .replaceAll("\\bshadow2D\\b", "toonShadow2D");
        if (source.contains("gl_Fog.")) {
            source = "uniform float iris_FogStart;\nuniform float iris_FogEnd;\n" + source
                    .replace("gl_Fog.start", "iris_FogStart")
                    .replace("gl_Fog.scale", "(1.0 / max(iris_FogEnd - iris_FogStart, 0.0001))");
        }
        // BSL 的 out shadow 在背光分支可能未赋值；不能让未定义值污染颜色。
        source = LIGHTING.matcher(source).replaceFirst("$0\n" + shadowName + " = vec3(0.0);");
        source = DIFFUSE.matcher(source).replaceAll("fullShadow = max(shadow * toonDiffuseRamp(NoL),");
        int varyingStart = source.indexOf("varying ");
        if (varyingStart < 0) return "";
        StringBuilder before = new StringBuilder(), after = new StringBuilder();
        String head = ComplementaryToonLighting.moveInitializers(source.substring(0, varyingStart), before);
        String tail = ComplementaryToonLighting.moveInitializers(
                source.substring(varyingStart).replaceAll("\\bvarying\\s+", ""), after);
        String extra = "";
        if (calls.contains("lightmap = ApplyDynamicHandlight(")) {
            extra += "lmCoord = ApplyDynamicHandlight(lmCoord, world);\n";
        }
        if (calls.contains("blocklightCol = ApplyMultiColoredBlocklight(")) {
            Matcher colored = Pattern.compile("vec3\\s+ApplyMultiColoredBlocklight\\s*\\(([^)]*)\\)").matcher(source);
            if (!colored.find()) return "";
            String screen = "vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), gl_FragCoord.z)";
            int count = colored.group(1).split(",").length;
            if (count == 2) extra += "blocklightCol = ApplyMultiColoredBlocklight(blocklightCol, " + screen + ");\n";
            else if (count == 5) extra += "blocklightCol = ApplyMultiColoredBlocklight(blocklightCol, " + screen
                    + ", " + (hand ? "vec3(0.0)" : "world") + ", n, " + (hand ? "1.0" : "0.0") + ");\n";
            else return "";
        }
        return "vec4 toonShadow2D(sampler2DShadow s, vec3 p) { return vec4(texture(s, p)); }\n"
                + head + tail + "\nvec3 applyPackLighting(vec3 raw, vec3 n, vec3 p) {\n"
                + before + (nether ? "sunVec = viewLightDir; upVec = vec3(0.0, 1.0, 0.0); eastVec = vec3(1.0, 0.0, 0.0);" : sunSetup)
                + "\nnormal = n; texCoord = texCoord0; color = vec4(1.0);\n"
                + "lmCoord = clamp((ToonLightmap - vec2(0.03125)) * 1.06667, vec2(0.0), vec2(0.9333, 1.0));\n"
                + after + "\nvec3 world = ToWorld(p);\n" + extra
                + "vec3 toonLight = " + (nether ? "viewLightDir" : "lightVec") + ";\n"
                + "float ndl = clamp(dot(n, toonLight), 0.0, 1.0);\n"
                + "vec3 lit = vec3(1.0), shadow = vec3(0.0);\n"
                + "GetLighting(lit, shadow, p, world, " + (hand ? "vec3(0.0)" : "n")
                + ", lmCoord, 1.0, ndl, 1.0, 1.0, 0.0, 0.0, 0.0);\n"
                + "return shadeToonSurface(raw, n, toonLight, p, pow(max(lit, vec3(0.0)), vec3(1.0 / 2.2)), toonDiffuseRamp(ndl));\n}\n";
    }
}
