package com.shiroha.mmdskin.render.effectpack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 将单 pass、单输入的逐像素 MME 颜色运算转为可复用于模型和世界的 GLSL 函数。 */
final class MmeColorImporter {
    private static final Set<String> FUNCTIONS = Set.of("dot", "lerp", "saturate", "pow", "clamp", "min", "max", "abs",
            "sqrt", "exp", "log", "sin", "cos", "floor", "ceil", "frac", "normalize", "length", "step", "smoothstep");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_]\\w*|(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?[fF]?|[.(),;=+*/{}\\-]");
    private static final int MAX_COLOR_TOKENS = 4096;
    private static final Pattern SAMPLER = Pattern.compile("\\bsampler(?:2D)?\\s+(\\w+)\\s*=\\s*sampler_state\\s*\\{([^}]+)}");
    private static final Pattern TEXTURE_BINDING = Pattern.compile("(?i)Texture\\s*=\\s*<\\s*(\\w+)\\s*>");
    private static final Pattern SCENE_TEXTURE = Pattern.compile("\\btexture(?:2D)?\\s+(\\w+)\\s*:\\s*RENDERCOLORTARGET\\b");
    private static final Pattern TYPE = Pattern.compile("(?:float|half)[234]?");
    static final String HELPERS = """
            float mmdFxSaturate(float x) { return clamp(x, 0.0, 1.0); }
            vec2 mmdFxSaturate(vec2 x) { return clamp(x, 0.0, 1.0); }
            vec3 mmdFxSaturate(vec3 x) { return clamp(x, 0.0, 1.0); }
            vec4 mmdFxSaturate(vec4 x) { return clamp(x, 0.0, 1.0); }
            """;

    static FxEffect parse(Path root, Path file, String text, String id) throws IOException {
        String source = FxSource.withoutComments(text);
        if (source.contains("#")) throw new IOException("MME 颜色适配器尚不支持 include/宏，请使用常量声明");
        if (!Pattern.compile("(?i)ScriptClass\\s*=\\s*\"scene\"").matcher(source).find())
            throw new IOException("未识别的 FX：需要 Ray 2.0 参数材质，或 ScriptClass=scene 的逐像素颜色效果");
        var pixels = Pattern.compile("PixelShader\\s*=\\s*compile\\s+ps_[23]_0\\s+(\\w+)\\s*\\(\\s*\\)").matcher(source);
        if (!pixels.find()) throw new IOException("找不到 ps_2_0/ps_3_0 像素入口");
        String entry = pixels.group(1);
        if (pixels.find() || Pattern.compile("\\bpass\\s+\\w*\\s*\\{").matcher(source).results().count() != 1)
            throw new IOException("多 pass MME 需要专用渲染图适配器");
        if (Pattern.compile("VertexShader\\s*=\\s*compile\\b").matcher(source).find())
            throw new IOException("自定义顶点阶段未适配；逐像素颜色适配器要求宿主提供顶点阶段");
        if (Pattern.compile("(?i)RenderColorTarget[0-9]+\\s*=\\s*[^;\"\\s]").matcher(source).find()
                || source.contains("ScriptExternal") || source.contains("DEPTHSTENCILTARGET"))
            throw new IOException("自定义渲染目标/外部绘制未适配");
        var function = Pattern.compile("float4\\s+" + Pattern.quote(entry)
                + "\\s*\\(\\s*(?:in\\s+)?float2\\s+(\\w+)\\s*:\\s*TEXCOORD0\\s*\\)\\s*:\\s*COLOR0\\s*\\{").matcher(source);
        if (!function.find()) throw new IOException("颜色入口要求 float4 PS(float2 uv : TEXCOORD0) : COLOR0");
        String uv = function.group(1);
        int begin = function.end(), end = source.indexOf('}', begin);
        if (end < 0 || source.substring(begin, end).contains("{")) throw new IOException("颜色适配器仅支持顺序的逐像素表达式");
        String body = source.substring(begin, end);
        if (body.length() > 64 * 1024) throw new IOException("颜色入口源码超过 64 KiB");
        var samples = Pattern.compile("tex2D\\s*\\(\\s*(\\w+)\\s*,\\s*" + Pattern.quote(uv) + "\\s*\\)").matcher(body);
        StringBuffer replaced = new StringBuffer();
        var sceneTextures = new HashSet<String>();
        var texture = SCENE_TEXTURE.matcher(source);
        while (texture.find()) sceneTextures.add(texture.group(1));
        var samplerBindings = new HashMap<String, Boolean>();
        var sampler = SAMPLER.matcher(source);
        var binding = TEXTURE_BINDING.matcher(source);
        while (sampler.find()) {
            binding.region(sampler.start(2), sampler.end(2));
            samplerBindings.putIfAbsent(sampler.group(1), binding.find() && sceneTextures.contains(binding.group(1)));
        }
        int sampleCount = 0;
        while (samples.find()) {
            if (++sampleCount > MAX_COLOR_TOKENS) throw new IOException("颜色采样数量超限");
            String samplerName = samples.group(1);
            if (!Boolean.TRUE.equals(samplerBindings.get(samplerName)))
                throw new IOException("颜色效果仅支持 RENDERCOLORTARGET 源画面采样");
            samples.appendReplacement(replaced, "mmdInput");
        }
        samples.appendTail(replaced);
        var variables = new HashSet<String>();
        variables.add("mmdInput");
        StringBuilder constants = new StringBuilder();
        var globals = Pattern.compile("(?:static\\s+)?const\\s+float([234]?)\\s+(\\w+)\\s*=\\s*([^;]+);").matcher(source.substring(0, function.start()));
        int constantCount = 0;
        while (globals.find()) {
            if (++constantCount > 256) throw new IOException("颜色常量数量超限");
            int width = globals.group(1).isEmpty() ? 1 : Integer.parseInt(globals.group(1));
            float[] values = RayMaterialImporter.numbers(globals.group(3), width);
            String name = globals.group(2);
            if (name.startsWith("mmd") || !variables.add(name)) throw new IOException("重复或保留常量名: " + name);
            constants.append("const ").append(width == 1 ? "float" : "vec" + width).append(' ').append(name).append(" = ");
            if (width > 1) constants.append("vec").append(width).append('(');
            for (int i = 0; i < width; i++) { if (i > 0) constants.append(','); constants.append(values[i]); }
            if (width > 1) constants.append(')');
            constants.append(";\n");
        }
        String glsl = translate(replaced.toString(), variables);
        return new FxEffect(id, file.getFileName().toString(), root, FxEffect.Kind.COLOR_FILTER, Map.of(), 1,
                HELPERS + "vec4 mmdFxColor(vec4 mmdInput) {\n" + constants + glsl + "\n}\n",
                List.of("逐像素 RGB 运算已转译；保留宿主覆盖率 alpha。", "顶点阶段使用宿主几何/全屏三角形；不支持采样邻域、深度、多 pass、控制器和动态 uniform。"));
    }

    private static String translate(String source, Set<String> variables) throws IOException {
        var tokens = TOKEN.matcher(source);
        StringBuilder out = new StringBuilder();
        int offset = 0, returns = 0, tokenCount = 0, depth = 0;
        String previous = "";
        boolean declaration = false;
        while (tokens.find()) {
            if (++tokenCount > MAX_COLOR_TOKENS || tokens.end() - tokens.start() > 128)
                throw new IOException("颜色表达式长度超限");
            if (!source.substring(offset, tokens.start()).isBlank()) throw new IOException("不支持的颜色表达式: " + source.substring(offset, tokens.start()).trim());
            String token = tokens.group(), next = token;
            if (token.equals("(") && ++depth > 64) throw new IOException("颜色表达式嵌套层数超限");
            if (token.equals(")") && --depth < 0) throw new IOException("颜色表达式括号不匹配");
            if (Character.isLetter(token.charAt(0)) || token.charAt(0) == '_') {
                boolean type = TYPE.matcher(token).matches();
                if (type) {
                    next = token.equals("float") || token.equals("half") ? "float" : "vec" + token.charAt(token.length() - 1);
                    // 类型后是标识符时为局部声明，后是左括号时为构造器。
                    int nextIndex = tokens.end();
                    while (nextIndex < source.length() && Character.isWhitespace(source.charAt(nextIndex))) nextIndex++;
                    declaration = nextIndex == source.length() || source.charAt(nextIndex) != '(';
                } else if (declaration) {
                    if (token.startsWith("mmd") || !variables.add(token)) throw new IOException("重复或保留局部变量: " + token);
                    declaration = false;
                } else if (previous.equals(".")) {
                    if (!token.matches("[xyzwrgbastpq]{1,4}")) throw new IOException("不支持的 swizzle: " + token);
                } else if (token.equals("return")) { returns++; }
                else if (token.equals("const") || variables.contains(token)) { }
                else if (FUNCTIONS.contains(token)) {
                    next = switch (token) { case "lerp" -> "mix"; case "frac" -> "fract"; case "saturate" -> "mmdFxSaturate"; default -> token; };
                } else throw new IOException("未适配标识符/函数: " + token);
            } else if (Character.isDigit(token.charAt(0)) || token.matches("\\.\\d+.*")) {
                next = token.replaceFirst("[fF]$", "");
                if (!next.contains(".") && !next.contains("e") && !next.contains("E")) next += ".0";
            }
            out.append(next).append(' ');
            previous = token;
            offset = tokens.end();
        }
        if (!source.substring(offset).isBlank() || depth != 0 || returns != 1 || !source.stripTrailing().endsWith(";"))
            throw new IOException("颜色入口要求一个 return 和完整语句");
        return out.toString();
    }
}
