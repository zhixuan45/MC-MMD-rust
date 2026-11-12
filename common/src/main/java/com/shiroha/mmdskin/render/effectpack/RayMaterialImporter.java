package com.shiroha.mmdskin.render.effectpack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Ray 2.0 的参数协议适配器；自定义实现和未支持的选项必须显式拒绝。 */
final class RayMaterialImporter {
    private static final String COMMON_HASH = "a0383fe1fc8079ed41f5865a61f6d5a69bdcf74e74d85b8dcf88905fe4fc6304";
    static FxEffect parse(Path root, Path file, String source, String id) throws IOException {
        var parameters = RayMaterialParameters.parse(source);
        if (parameters.includes().isEmpty()) throw new IOException("缺少 Ray 2.0 公共实现");
        if (parameters.includes().size() != 1) throw new IOException("额外 include 尚未适配");
        Path common = FxPaths.resolve(root, file.getParent(), parameters.includes().get(0));
        if (Files.size(common) > 1024 * 1024) throw new IOException("Ray 公共源码超过 1 MiB");
        String commonSource = Files.readString(common).replace("\r", "");
        if (!FxSource.sha256(commonSource).equals(COMMON_HASH))
            throw new IOException("Ray 公共实现版本或自定义代码未适配: " + common.getFileName());
        return convert(root, file, parameters, id);
    }

    static FxEffect convert(Path root, Path file, RayMaterialParameters parameters, String id) throws IOException {
        var definitions = parameters.definitions();
        var constants = parameters.constants();
        for (String flag : new String[]{"PARALLAX_MAP_FROM",
                "ALBEDO_MAP_APPLY_MORPH_COLOR", "EMISSIVE_MAP_APPLY_MORPH_COLOR", "EMISSIVE_MAP_APPLY_MORPH_INTENSITY", "EMISSIVE_MAP_APPLY_BLINK"}) {
            if (integer(definitions, flag, 0) != 0) throw new IOException("尚未适配 Ray 选项: " + flag);
        }
        int custom = integer(definitions, "CUSTOM_ENABLE", 0);
        if (custom < 0 || custom > 9) throw new IOException("Ray 着色模型越界: CUSTOM_ENABLE=" + custom);
        int applyDiffuse = integer(definitions, "ALBEDO_MAP_APPLY_DIFFUSE", 1);
        if (applyDiffuse < 0 || applyDiffuse > 1) throw new IOException("ALBEDO_MAP_APPLY_DIFFUSE 要求 0 或 1");
        int alphaSource = integer(definitions, "ALPHA_MAP_FROM", 3);
        if (alphaSource != 0 && alphaSource != 1 && (alphaSource != 3 || integer(definitions, "ALPHA_MAP_SWIZZLE", 3) != 3
                || integer(definitions, "ALPHA_MAP_UV_FLIP", 0) != 0
                || !unit(constants.getOrDefault("alphaMapLoopNum", new float[]{1, 1}))))
            throw new IOException("当前透明度支持常量、原 PMX 贴图 alpha 或外置 alpha 贴图");

        var channels = new LinkedHashMap<String, FxEffect.Channel>();
        if (alphaSource == 0 || alphaSource == 1)
            add(channels, root, file, definitions, constants, "ALPHA", "alpha", new float[]{1}, 1);
        add(channels, root, file, definitions, constants, "ALBEDO", "albedo", new float[]{1, 1, 1}, 0);
        FxEffect.Channel albedo = channels.get("ALBEDO");
        // ALBEDO's type field carries the diffuse multiplier switch; Ray has no albedo map type.
        channels.put("ALBEDO", new FxEffect.Channel(albedo.source(), albedo.file(), albedo.value(), albedo.uvFlip(),
                albedo.repeat(), applyDiffuse, albedo.component(), albedo.scaleMode()));
        int subMode = integer(definitions, "ALBEDO_SUB_ENABLE", 0);
        if (subMode < 0 || subMode > 5) throw new IOException("尚未适配 Ray 选项: ALBEDO_SUB_ENABLE=" + subMode);
        if (subMode != 0) {
            add(channels, root, file, definitions, constants, "ALBEDO_SUB", "albedoSub", new float[]{1, 1, 1}, 0);
            FxEffect.Channel sub = channels.get("ALBEDO_SUB");
            channels.put("ALBEDO_SUB", new FxEffect.Channel(sub.source(), sub.file(), sub.value(), sub.uvFlip(), sub.repeat(), subMode, sub.component(), sub.scaleMode()));
        }
        add(channels, root, file, definitions, constants, "NORMAL", "normalMapScale", new float[]{1}, 0);
        add(channels, root, file, definitions, constants, "NORMAL_SUB", "normalSubMapScale", new float[]{1}, 0);
        add(channels, root, file, definitions, constants, "SMOOTHNESS", "smoothness", new float[]{0}, 0);
        add(channels, root, file, definitions, constants, "METALNESS", "metalness", new float[]{0}, 0);
        add(channels, root, file, definitions, constants, "SPECULAR", "specular", new float[]{0.5f, 0.5f, 0.5f}, 0);
        add(channels, root, file, definitions, constants, "OCCLUSION", "occlusion", new float[]{1}, 0);
        if (custom != 0) {
            FxEffect.Channel specular = channels.get("SPECULAR");
            if (specular.source() != 0 && specular.type() < 2)
                throw new IOException("Ray 专用模型的 SPECULAR 贴图要求 TYPE=2 或 3（单通道）");
            // Model 2 ignores both custom inputs; clear coat ignores B. Do not load inactive maps.
            if (custom != 2) add(channels, root, file, definitions, constants, "CUSTOM_A", "customA", new float[]{0}, 0);
            else channels.put("CUSTOM_A", new FxEffect.Channel(0, "", new float[]{0}, 0, new float[]{1,1}, 0,0,0));
            if (custom != 2 && custom != 6) add(channels, root, file, definitions, constants, "CUSTOM_B", "customB", new float[]{0, 0, 0}, 0);
            for (String key : new String[]{"CUSTOM_A", "CUSTOM_B"}) {
                if (!channels.containsKey(key)) continue;
                int flip = custom == 2 ? 0 : integer(definitions, key + "_MAP_COLOR_FLIP", 0);
                if (flip < 0 || flip > 1) throw new IOException(key + ": COLOR_FLIP 要求 0 或 1");
                FxEffect.Channel channel = channels.get(key);
                channels.put(key, new FxEffect.Channel(channel.source(), channel.file(), channel.value(), channel.uvFlip(),
                        channel.repeat(), key.equals("CUSTOM_A") ? custom : channel.type(), channel.component(), channel.scaleMode(), flip));
            }
        }
        if (integer(definitions, "EMISSIVE_ENABLE", 0) != 0) {
            add(channels, root, file, definitions, constants, "EMISSIVE", "emissive", new float[]{1, 1, 1}, 0);
            float intensity = constants.getOrDefault("emissiveIntensity", new float[]{1})[0];
            FxEffect.Channel e = channels.get("EMISSIVE");
            if (intensity < 0) throw new IOException("emissiveIntensity 必须为非负数");
            // Keep intensity separate: color is converted from sRGB before intensity is applied.
            float[] colorIntensity = java.util.Arrays.copyOf(e.value(), 4);
            colorIntensity[3] = intensity;
            channels.put("EMISSIVE", new FxEffect.Channel(e.source(), e.file(), colorIntensity, e.uvFlip(), e.repeat(), e.type(), e.component(), e.scaleMode()));
        }
        var notes = new ArrayList<String>();
        notes.add("Ray 材质语义已转换；光照使用宿主环境，不复刻缺失的 Ray 灯光/IBL/后处理。");
        notes.add("贴图 UV 必须与目标模型匹配；不会自动推断 PMX 材质分配。");
        if (custom != 0) notes.add("Ray 着色模型 " + custom + " 已转换；CustomA 使用线性空间，CustomB 使用 sRGB。光照来自宿主环境。");
        if (custom == 1 || custom == 7) notes.add("皮肤/次表面使用局部曲率散射与透射；不包含 Ray 全场景屏幕空间散射和 IBL。");
        if (custom == 4) notes.add("玻璃使用当前背景快照折射并保留 PMX 透明排序；PMX 透明度需小于 0.999。多层玻璃和后绘制场景的折射不等同于 Ray 全场景管线。");
        if (alphaSource == 0) notes.add("常量 alpha 替换 PMX 贴图 alpha，并保留材质透明度；阴影程序不支持时在运行时报告。");
        if (alphaSource == 1) notes.add("外置 alpha 用于主体透明及深度；阴影支持唯一主纹理的两参数采样，不支持的阴影程序在运行时报告。");
        return new FxEffect(id, file.getFileName().toString(), root, FxEffect.Kind.RAY_MATERIAL,
                Map.copyOf(channels), constants.getOrDefault("alpha", new float[]{1})[0], FxEffect.IDENTITY, notes);
    }

    private static void add(Map<String, FxEffect.Channel> out, Path root, Path fx, Map<String, String> defs,
                            Map<String, float[]> values, String key, String valueName, float[] fallback, int defaultSource) throws IOException {
        int from = integer(defs, key + "_MAP_FROM", defaultSource);
        boolean materialInput = key.startsWith("CUSTOM_") || key.equals("SPECULAR") || key.equals("EMISSIVE");
        if (from != 0 && from != 1 && from != 3 && !(key.equals("SMOOTHNESS") && from == 9)
                && !(materialInput && (from == 7 || from == 8)))
            throw new IOException(key + ": 不支持贴图来源 " + from);
        int type = integer(defs, key + "_MAP_TYPE", 0);
        if (((key.equals("NORMAL") || key.equals("NORMAL_SUB")) && (type < 0 || type > 1)) || (key.equals("SMOOTHNESS") && (type < 0 || type > 2))
                || (key.equals("OCCLUSION") && (type < 0 || type > 1))
                || (key.equals("CUSTOM_B") && (type < 0 || type > 1))
                || (key.equals("SPECULAR") && (type < 0 || type > 4 || (from != 0 && type == 4)))) throw new IOException(key + ": 不支持贴图类型 " + type);
        String relative = "";
        if (from == 1) {
            String quoted = defs.getOrDefault(key + "_MAP_FILE", "");
            if (!quoted.matches("\"[^\"]+\"")) throw new IOException(key + ": 贴图文件名无效");
            Path texture = FxPaths.resolve(root, fx.getParent(), quoted.substring(1, quoted.length() - 1));
            if (!Files.isRegularFile(texture)) throw new IOException("缺失活动贴图: " + texture.getFileName());
            relative = root.relativize(texture).toString().replace('\\', '/');
        }
        int flip = integer(defs, key + "_MAP_UV_FLIP", 0), component = integer(defs, key + "_MAP_SWIZZLE", key.equals("ALPHA") ? 3 : 0);
        int scale = integer(defs, key + "_MAP_APPLY_SCALE", 0);
        if (flip < 0 || flip > 3 || component < 0 || component > 3 || scale < 0 || scale > 2)
            throw new IOException(key + ": UV/通道/缩放选项越界");
        String repeatName = switch (key) {
            case "ALBEDO_SUB" -> "albedoSubMapLoopNum";
            case "NORMAL_SUB" -> "normalSubMapLoopNum";
            case "CUSTOM_A" -> "customAMapLoopNum";
            case "CUSTOM_B" -> "customBMapLoopNum";
            default -> key.toLowerCase(java.util.Locale.ROOT) + "MapLoopNum";
        };
        float[] repeats = values.getOrDefault(repeatName, new float[]{1, 1});
        float[] repeat = new float[]{repeats[0], repeats.length > 1 ? repeats[1] : repeats[0]};
        if (repeat[0] <= 0 || repeat[1] <= 0) throw new IOException(key + ": UV 重复次数必须为正");
        float[] value = values.getOrDefault(valueName, fallback).clone();
        if (value.length != fallback.length) throw new IOException("参数类型不匹配: " + valueName);
        out.put(key, new FxEffect.Channel(from, relative, value, flip, repeat, type, component, scale));
    }

    static int integer(Map<String, String> definitions, String key, int fallback) throws IOException {
        if (!definitions.containsKey(key)) return fallback;
        float value = new FxConstantExpression(Map.of(), definitions, Map.of()).evaluate(definitions.get(key), 1)[0];
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || (double) value > Integer.MAX_VALUE)
            throw new IOException("宏要求整数常量: " + key);
        return (int) value;
    }

    private static boolean unit(float[] values) { for (float v : values) if (v != 1) return false; return true; }

    static float[] numbers(String expression, int width) throws IOException {
        return new FxConstantExpression(Map.of(), Map.of(), Map.of()).evaluate(expression, width);
    }
}
