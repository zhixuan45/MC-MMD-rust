package com.shiroha.mmdskin.render.effectpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 三个目标独立选择效果；空 id 表示关闭，旧配置默认不启用世界效果。 */
public final class EffectPackSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public String characters = "";
    public String scenes = "";
    public String world = "";
    public String characterModel = "*";
    public String sceneModel = "*";
    public Map<String, List<MaterialEffectRule>> characterMaterials = new LinkedHashMap<>();
    public Map<String, List<MaterialEffectRule>> sceneMaterials = new LinkedHashMap<>();

    public static EffectPackSettings load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return new EffectPackSettings();
        EffectPackSettings result = GSON.fromJson(Files.readString(path), EffectPackSettings.class);
        if (result == null) result = new EffectPackSettings();
        result.characters = nonNull(result.characters);
        result.scenes = nonNull(result.scenes);
        result.world = nonNull(result.world);
        result.characterModel = nonNull(result.characterModel);
        result.sceneModel = nonNull(result.sceneModel);
        result.characterMaterials = cleanRules(result.characterMaterials);
        result.sceneMaterials = cleanRules(result.sceneMaterials);
        return result;
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "effects-", ".json.tmp");
        try {
            Files.writeString(temporary, GSON.toJson(this));
            try { Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static boolean matches(String selector, String model) {
        return "*".equals(selector) || (selector != null && !selector.isBlank() && selector.equals(model));
    }

    public List<MaterialEffectRule> rules(boolean scene, String model) {
        return (scene ? sceneMaterials : characterMaterials).getOrDefault(model, List.of());
    }

    public MaterialEffectRule rule(boolean scene, String model, int index, String name) {
        for (MaterialEffectRule rule : rules(scene, model)) if (rule.matches(index, name)) return rule;
        return null;
    }

    /** effect=null 删除覆盖并恢复继承；effect="" 只关闭这个材质。 */
    public void bind(boolean scene, String model, int index, String name, String effect) {
        if (model == null || model.isBlank() || index < 0) throw new IllegalArgumentException("模型名与材质序号无效");
        Map<String, List<MaterialEffectRule>> target = scene ? sceneMaterials : characterMaterials;
        var next = new ArrayList<>(target.getOrDefault(model, List.of()));
        next.removeIf(rule -> rule.index() == index);
        if (effect != null) next.add(new MaterialEffectRule(index, nonNull(name), effect));
        next.sort(java.util.Comparator.comparingInt(MaterialEffectRule::index));
        if (next.isEmpty()) target.remove(model); else target.put(model, next);
    }

    private static Map<String, List<MaterialEffectRule>> cleanRules(Map<String, List<MaterialEffectRule>> source) {
        var clean = new LinkedHashMap<String, List<MaterialEffectRule>>();
        if (source != null) source.forEach((model, rules) -> {
            if (model == null || model.isBlank() || rules == null) return;
            var byIndex = new java.util.TreeMap<Integer, MaterialEffectRule>();
            for (MaterialEffectRule rule : rules) if (rule != null && rule.index() >= 0 && rule.effect() != null)
                byIndex.put(rule.index(), new MaterialEffectRule(rule.index(), nonNull(rule.name()), rule.effect()));
            if (!byIndex.isEmpty()) clean.put(model, new ArrayList<>(byIndex.values()));
        });
        return clean;
    }
    private static String nonNull(String value) { return value == null ? "" : value; }
}
