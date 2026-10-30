package com.shiroha.mmdskin.render.effectpack;

import com.shiroha.mmdskin.config.PathConstants;
import com.shiroha.mmdskin.compat.iris.IrisCompat;
import com.shiroha.mmdskin.render.scene.RenderScene;
import com.shiroha.mmdskin.texture.runtime.TextureRepository;
import org.apache.logging.log4j.LogManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仅在客户端渲染线程访问；磁盘扫描和纹理加载不进入逐材质的热路径。 */
public final class EffectPackRuntime {
    private static EffectPackRuntime instance;
    private final Path library;
    private final Path config;
    private EffectPackSettings settings;
    private FxPackLibrary.Scan scan = new FxPackLibrary.Scan(List.of(), List.of());
    private Map<String, FxEffect> effects = Map.of();
    private final Map<String, Integer> textures = new LinkedHashMap<>();
    private final Map<String, String> problems = new LinkedHashMap<>();
    private FxEffect current;

    private EffectPackRuntime() {
        this(PathConstants.getSkinRootDir().toPath().resolve("effectpacks"), PathConstants.getConfigRootPath().resolve("effectpacks.json"));
    }

    EffectPackRuntime(Path library, Path config) {
        this.library = library;
        this.config = config;
        try { settings = EffectPackSettings.load(config); }
        catch (Exception e) { settings = new EffectPackSettings(); problem("config", "配置读取失败: " + e.getMessage()); }
        reload();
    }

    public static EffectPackRuntime get() {
        if (instance == null) instance = new EffectPackRuntime();
        return instance;
    }
    public static EffectPackRuntime active() { return instance; }
    public Path library() { return library; }
    public EffectPackSettings settings() { return settings; }
    public FxPackLibrary.Scan scan() { return scan; }
    public List<String> problems() { return List.copyOf(problems.values()); }
    public FxEffect effect(String id) { return effects.get(id); }
    public FxEffect current() { return current; }
    public boolean enabled() { return current != null; }
    public FxEffect world() {
        FxEffect result = effects.get(settings.world);
        return result != null && result.supportsWorld() ? result : null;
    }

    public void save() {
        try { settings.save(config); problems.remove("save"); problems.remove("config"); }
        catch (IOException e) { problem("save", "配置保存失败: " + e.getMessage()); }
    }

    public void reload() {
        releaseTextures();
        problems.keySet().removeIf(key -> !key.equals("config") && !key.equals("save"));
        try {
            scan = FxPackLibrary.scan(library);
            var next = new LinkedHashMap<String, FxEffect>();
            for (FxEffect effect : scan.effects()) next.put(effect.id(), effect);
            effects = Map.copyOf(next);
            problems.remove("scan");
            for (String id : new String[]{settings.characters, settings.scenes, settings.world})
                if (!id.isEmpty() && !effects.containsKey(id)) problem("missing:" + id, "所选效果未加载: " + id);
            FxEffect worldEffect = effect(settings.world);
            if (worldEffect != null && !worldEffect.supportsWorld()) problem("world-kind", "所选效果只有模型材质通道，无法作用于世界: " + worldEffect.name());
        } catch (IOException e) { problem("scan", "效果库扫描失败: " + e.getMessage()); }
    }

    public Scope enter(RenderScene scene, String model) {
        FxEffect previous = current;
        current = null;
        if (scene != null && !IrisCompat.isRenderingShadows() && scene.sceneType() != RenderScene.SceneType.ITEM) {
            boolean placed = scene.isPlacedScene();
            String selector = placed ? settings.sceneModel : settings.characterModel;
            if (EffectPackSettings.matches(selector, model)) current = effect(placed ? settings.scenes : settings.characters);
            // 全屏颜色效果已包含世界中的人物，避免相同效果应用两次；GUI 仍可单独预览。
            if (current != null && current.supportsWorld() && scene.isWorldScene() && world() != null
                    && current.id().equals(world().id())) current = null;
        }
        return new Scope(previous);
    }

    public final class Scope implements AutoCloseable {
        private final FxEffect previous;
        private Scope(FxEffect previous) { this.previous = previous; }
        @Override public void close() { current = previous; }
    }

    public String colorFunction() {
        return current != null && current.kind() == FxEffect.Kind.COLOR_FILTER ? current.colorFunction() : FxEffect.IDENTITY;
    }
    public String variantKey() {
        return current == null ? "" : ":fx:" + (current.kind() == FxEffect.Kind.RAY_MATERIAL ? "ray2" : FxSource.sha256(colorFunction()).substring(0, 16));
    }

    public int texture(FxEffect effect, FxEffect.Channel channel) {
        String path = effect.root().resolve(channel.file()).toString();
        Integer existing = textures.get(path);
        if (existing != null) return existing;
        var texture = TextureRepository.GetTexture(path, "fx-material", effect.id());
        int id = texture == null ? 0 : texture.tex;
        if (id > 0) TextureRepository.addRef(path);
        else problem("texture:" + path, "FX 贴图加载失败: " + channel.file());
        textures.put(path, id);
        return id;
    }

    public void problem(String key, String message) {
        if (!message.equals(problems.put(key, message))) LogManager.getLogger().warn("[MMD FX] {}", message);
        while (problems.size() > 32) problems.remove(problems.keySet().iterator().next());
    }
    private void releaseTextures() {
        textures.forEach((path, id) -> { if (id > 0) TextureRepository.release(path); });
        textures.clear();
    }

    public static void release() { if (instance != null) instance.releaseTextures(); }
}
