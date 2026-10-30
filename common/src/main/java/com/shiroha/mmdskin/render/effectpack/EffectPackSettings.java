package com.shiroha.mmdskin.render.effectpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 三个目标独立选择效果；空 id 表示关闭，旧配置默认不启用世界效果。 */
public final class EffectPackSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public String characters = "";
    public String scenes = "";
    public String world = "";
    public String characterModel = "*";
    public String sceneModel = "*";

    public static EffectPackSettings load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return new EffectPackSettings();
        EffectPackSettings result = GSON.fromJson(Files.readString(path), EffectPackSettings.class);
        if (result == null) result = new EffectPackSettings();
        result.characters = nonNull(result.characters);
        result.scenes = nonNull(result.scenes);
        result.world = nonNull(result.world);
        result.characterModel = nonNull(result.characterModel);
        result.sceneModel = nonNull(result.sceneModel);
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
    private static String nonNull(String value) { return value == null ? "" : value; }
}
