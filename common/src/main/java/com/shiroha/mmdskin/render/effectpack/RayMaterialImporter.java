package com.shiroha.mmdskin.render.effectpack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Ray 2.0 的参数协议适配器；自定义实现和未支持的选项必须显式拒绝。 */
final class RayMaterialImporter {
    private static final String COMMON_HASH = "a0383fe1fc8079ed41f5865a61f6d5a69bdcf74e74d85b8dcf88905fe4fc6304";
    private static final Pattern DEFINE = Pattern.compile("(?m)^\\s*#define\\s+(\\w+)\\s+([^\\r\\n]+)");
    private static final Pattern VALUE = Pattern.compile("const\\s+float([234]?)\\s+(\\w+)\\s*=\\s*([^;]+);");
    private static final Pattern INCLUDE = Pattern.compile("#include\\s+\"([^\"]+)\"");

    static FxEffect parse(Path root, Path file, String source, String id) throws IOException {
        String clean = FxSource.withoutComments(source);
        var definitions = new LinkedHashMap<String, String>();
        var constants = new LinkedHashMap<String, float[]>();
        var matcher = DEFINE.matcher(clean);
        while (matcher.find()) {
            if (definitions.put(matcher.group(1), matcher.group(2).trim()) != null)
                throw new IOException("重复宏: " + matcher.group(1));
        }
        matcher = VALUE.matcher(clean);
        while (matcher.find()) {
            int width = matcher.group(1).isEmpty() ? 1 : Integer.parseInt(matcher.group(1));
            constants.put(matcher.group(2), numbers(matcher.group(3), width));
        }
        matcher = INCLUDE.matcher(clean);
        if (!matcher.find()) throw new IOException("缺少 Ray 2.0 公共实现");
        Path common = FxPaths.resolve(root, file.getParent(), matcher.group(1));
        if (Files.size(common) > 1024 * 1024) throw new IOException("Ray 公共源码超过 1 MiB");
        String commonSource = Files.readString(common).replace("\r", "");
        if (!FxSource.sha256(commonSource).equals(COMMON_HASH))
            throw new IOException("Ray 公共实现版本或自定义代码未适配: " + common.getFileName());
        if (matcher.find()) throw new IOException("额外 include 尚未适配");
        // 参数文件只允许声明；不能忽略夹带的自定义函数或条件预处理。
        String remaining = INCLUDE.matcher(VALUE.matcher(DEFINE.matcher(clean).replaceAll("")).replaceAll("")).replaceAll("").trim();
        if (!remaining.isEmpty()) throw new IOException("Ray 参数文件包含未支持的代码/条件宏");

        for (String flag : new String[]{"ALBEDO_SUB_ENABLE", "NORMAL_SUB_MAP_FROM", "PARALLAX_MAP_FROM", "CUSTOM_ENABLE",
                "ALBEDO_MAP_APPLY_MORPH_COLOR", "EMISSIVE_MAP_APPLY_MORPH_COLOR", "EMISSIVE_MAP_APPLY_MORPH_INTENSITY", "EMISSIVE_MAP_APPLY_BLINK"}) {
            if (integer(definitions, flag, 0) != 0) throw new IOException("尚未适配 Ray 选项: " + flag);
        }
        if (integer(definitions, "ALBEDO_MAP_APPLY_DIFFUSE", 1) != 1)
            throw new IOException("当前要求 ALBEDO_MAP_APPLY_DIFFUSE=1");
        if (constants.getOrDefault("alpha", new float[]{1})[0] != 1)
            throw new IOException("Ray 常量 alpha 淡出尚未适配；当前保留 PMX 贴图和材质 morph 的透明度");
        int alphaSource = integer(definitions, "ALPHA_MAP_FROM", 3);
        if (alphaSource != 3 || integer(definitions, "ALPHA_MAP_SWIZZLE", 3) != 3
                || integer(definitions, "ALPHA_MAP_UV_FLIP", 0) != 0
                || !unit(constants.getOrDefault("alphaMapLoopNum", new float[]{1, 1})))
            throw new IOException("当前透明度仅支持原 PMX 贴图 alpha；外置 alpha/UV 变换需额外适配");

        var channels = new LinkedHashMap<String, FxEffect.Channel>();
        add(channels, root, file, definitions, constants, "ALBEDO", "albedo", new float[]{1, 1, 1}, 0);
        add(channels, root, file, definitions, constants, "NORMAL", "normalMapScale", new float[]{1}, 0);
        add(channels, root, file, definitions, constants, "SMOOTHNESS", "smoothness", new float[]{0}, 0);
        add(channels, root, file, definitions, constants, "METALNESS", "metalness", new float[]{0}, 0);
        add(channels, root, file, definitions, constants, "SPECULAR", "specular", new float[]{0.5f, 0.5f, 0.5f}, 0);
        add(channels, root, file, definitions, constants, "OCCLUSION", "occlusion", new float[]{1}, 0);
        if (integer(definitions, "EMISSIVE_ENABLE", 0) != 0) {
            add(channels, root, file, definitions, constants, "EMISSIVE", "emissive", new float[]{1, 1, 1}, 0);
            float intensity = constants.getOrDefault("emissiveIntensity", new float[]{1})[0];
            FxEffect.Channel e = channels.get("EMISSIVE");
            float[] scaled = e.value().clone();
            for (int i = 0; i < scaled.length; i++) scaled[i] *= intensity;
            channels.put("EMISSIVE", new FxEffect.Channel(e.source(), e.file(), scaled, e.uvFlip(), e.repeat(), e.type(), e.component(), e.scaleMode()));
        }
        var notes = new ArrayList<String>();
        notes.add("Ray 材质语义已转换；光照使用宿主环境，不复刻缺失的 Ray 灯光/IBL/后处理。");
        notes.add("贴图 UV 必须与目标模型匹配；不会自动推断 PMX 材质分配。");
        return new FxEffect(id, file.getFileName().toString(), root, FxEffect.Kind.RAY_MATERIAL,
                Map.copyOf(channels), constants.getOrDefault("alpha", new float[]{1})[0], FxEffect.IDENTITY, notes);
    }

    private static void add(Map<String, FxEffect.Channel> out, Path root, Path fx, Map<String, String> defs,
                            Map<String, float[]> values, String key, String valueName, float[] fallback, int defaultSource) throws IOException {
        int from = integer(defs, key + "_MAP_FROM", defaultSource);
        if (from != 0 && from != 1 && from != 3) throw new IOException(key + ": 不支持贴图来源 " + from);
        int type = integer(defs, key + "_MAP_TYPE", 0);
        if ((key.equals("NORMAL") && (type < 0 || type > 1)) || (key.equals("SMOOTHNESS") && (type < 0 || type > 2))
                || (key.equals("OCCLUSION") && (type < 0 || type > 1))
                || (key.equals("SPECULAR") && type != 0)) throw new IOException(key + ": 不支持贴图类型 " + type);
        String relative = "";
        if (from == 1) {
            String quoted = defs.getOrDefault(key + "_MAP_FILE", "");
            if (!quoted.matches("\"[^\"]+\"")) throw new IOException(key + ": 贴图文件名无效");
            Path texture = FxPaths.resolve(root, fx.getParent(), quoted.substring(1, quoted.length() - 1));
            if (!Files.isRegularFile(texture)) throw new IOException("缺失活动贴图: " + texture.getFileName());
            relative = root.relativize(texture).toString().replace('\\', '/');
        }
        int flip = integer(defs, key + "_MAP_UV_FLIP", 0), component = integer(defs, key + "_MAP_SWIZZLE", 0);
        int scale = integer(defs, key + "_MAP_APPLY_SCALE", 0);
        if (flip < 0 || flip > 3 || component < 0 || component > 3 || scale < 0 || scale > 2)
            throw new IOException(key + ": UV/通道/缩放选项越界");
        String repeatName = key.toLowerCase(java.util.Locale.ROOT) + "MapLoopNum";
        float[] repeats = values.getOrDefault(repeatName, new float[]{1, 1});
        float[] repeat = new float[]{repeats[0], repeats.length > 1 ? repeats[1] : repeats[0]};
        if (repeat[0] <= 0 || repeat[1] <= 0) throw new IOException(key + ": UV 重复次数必须为正");
        float[] value = values.getOrDefault(valueName, fallback).clone();
        if (value.length != fallback.length) throw new IOException("参数类型不匹配: " + valueName);
        out.put(key, new FxEffect.Channel(from, relative, value, flip, repeat, type, component, scale));
    }

    static int integer(Map<String, String> definitions, String key, int fallback) throws IOException {
        try { return Integer.parseInt(definitions.getOrDefault(key, Integer.toString(fallback)).trim()); }
        catch (NumberFormatException e) { throw new IOException("宏要求整数常量: " + key); }
    }

    private static boolean unit(float[] values) { for (float v : values) if (v != 1) return false; return true; }

    static float[] numbers(String expression, int width) throws IOException {
        String text = expression.trim().replaceFirst("^(?:float[234]|half[234])\\s*\\((.*)\\)$", "$1");
        String[] components = text.split(",");
        if (components.length != 1 && components.length != width) throw new IOException("向量宽度不匹配: " + expression);
        float[] result = new float[width];
        try {
            for (int i = 0; i < width; i++) {
                result[i] = Float.parseFloat(components[components.length == 1 ? 0 : i].trim().replaceFirst("[fF]$", ""));
                if (!Float.isFinite(result[i])) throw new NumberFormatException();
            }
        } catch (NumberFormatException e) { throw new IOException("仅支持有限数值常量: " + expression); }
        return result;
    }
}
