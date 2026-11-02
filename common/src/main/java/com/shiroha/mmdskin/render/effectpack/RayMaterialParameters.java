package com.shiroha.mmdskin.render.effectpack;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Parses declarations separately from the trusted Ray implementation include. */
record RayMaterialParameters(Map<String, String> definitions, Map<String, float[]> constants, List<String> includes) {
    private static final Pattern DEFINE = Pattern.compile("(?m)^[ \\t]*#define[ \\t]+(\\w+)(?:\\(([\\w, \\t]*)\\))?[ \\t]+([^\\r\\n]+)");
    private static final Pattern VALUE = Pattern.compile("\\b(?:static\\s+)?const\\s+(?:float|half)([234]?)\\s+(\\w+)\\s*=\\s*([^;]+);");
    private static final Pattern INCLUDE = Pattern.compile("(?m)^[ \\t]*#include[ \\t]+\"([^\"]+)\"");

    static RayMaterialParameters parse(String source) throws IOException {
        String clean = FxSource.withoutComments(source);
        var definitions = new LinkedHashMap<String, String>();
        var functions = new LinkedHashMap<String, FxConstantExpression.Function>();
        var names = new java.util.HashSet<String>();
        var matcher = DEFINE.matcher(clean);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!names.add(name)) throw new IOException("重复宏: " + name);
            if (names.size() > 512) throw new IOException("Ray 宏数量超限");
            if (matcher.group(2) == null) definitions.put(name, matcher.group(3).trim());
            else {
                List<String> parameters = matcher.group(2).isBlank() ? List.of()
                        : Arrays.stream(matcher.group(2).split(",", -1)).map(String::trim).toList();
                if (parameters.size() > 8 || parameters.stream().anyMatch(p -> !p.matches("[A-Za-z_]\\w*"))
                        || parameters.stream().distinct().count() != parameters.size())
                    throw new IOException("无效的函数式宏参数: " + name);
                functions.put(name, new FxConstantExpression.Function(parameters, matcher.group(3).trim()));
            }
        }
        var includes = new java.util.ArrayList<String>();
        matcher = INCLUDE.matcher(clean);
        while (matcher.find()) {
            String include = matcher.group(1);
            if (include.replace('\\', '/').endsWith("/material_editor.fxsub") || include.equals("material_editor.fxsub"))
                throw new IOException("Ray Editor 依赖 MMD CONTROLOBJECT 控制器，尚未适配: " + include);
            includes.add(include);
        }
        String declarations = INCLUDE.matcher(DEFINE.matcher(clean).replaceAll("")).replaceAll("");
        String remaining = VALUE.matcher(declarations).replaceAll("").trim();
        if (!remaining.isEmpty()) throw new IOException("Ray 参数文件包含未支持的代码/条件宏");
        var constants = new LinkedHashMap<String, float[]>();
        var evaluator = new FxConstantExpression(constants, definitions, functions);
        matcher = VALUE.matcher(declarations);
        while (matcher.find()) {
            if (constants.size() >= 512) throw new IOException("Ray 常量数量超限");
            String name = matcher.group(2);
            if (constants.containsKey(name) || names.contains(name)) throw new IOException("重复/宏冲突常量: " + name);
            int width = matcher.group(1).isEmpty() ? 1 : Integer.parseInt(matcher.group(1));
            try { constants.put(name, evaluator.evaluate(matcher.group(3), width)); }
            catch (IOException e) { throw new IOException("参数 " + name + ": " + e.getMessage(), e); }
        }
        return new RayMaterialParameters(Map.copyOf(definitions), Map.copyOf(constants), List.copyOf(includes));
    }
}
