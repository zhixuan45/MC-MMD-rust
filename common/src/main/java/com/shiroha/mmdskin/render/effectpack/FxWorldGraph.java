package com.shiroha.mmdskin.render.effectpack;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 显式的世界颜色 pass 适配格式；每个节点引用一个已支持的 MME 颜色 FX。 */
public record FxWorldGraph(List<Stage> stages, String output) {
    public record Stage(String name, String input, String output, FxEffect effect) { }
    public FxWorldGraph { stages = List.copyOf(stages); }

    FxRenderGraph compile(int width, int height) {
        var resources = stages.stream().map(s -> new FxRenderGraph.Resource(s.output(), width, height, "RGBA16F")).toList();
        var passes = stages.stream().map(s -> new FxRenderGraph.Pass(s.name(), List.of(s.input()), s.output(), s.name())).toList();
        return FxRenderGraph.compile(resources, passes, Set.of("scene"), output);
    }

    static FxEffect parse(Path root, Path file, String source, String id) throws IOException {
        try {
            var json = JsonParser.parseString(source).getAsJsonObject();
            if (!Set.of("format", "output", "passes").containsAll(json.keySet())) throw new IOException("FX 图包含未支持的字段");
            if (json.get("format").getAsInt() != 1) throw new IOException("不支持的 FX 图版本");
            var entries = json.getAsJsonArray("passes");
            if (entries.isEmpty() || entries.size() > 16) throw new IOException("FX 图需要 1 到 16 个 pass");
            var stages = new ArrayList<Stage>();
            for (var entry : entries) {
                var pass = entry.getAsJsonObject();
                if (!Set.of("name", "input", "output", "effect").containsAll(pass.keySet())) throw new IOException("FX 节点包含未支持的字段");
                Path fx = FxPaths.resolve(root, file.getParent(), pass.get("effect").getAsString());
                if (!Files.isRegularFile(fx) || Files.size(fx) > 1024 * 1024) throw new IOException("FX 节点文件不存在或过大");
                FxEffect effect = MmeColorImporter.parse(root, fx, Files.readString(fx), id + "/" + stages.size());
                stages.add(new Stage(pass.get("name").getAsString(), pass.get("input").getAsString(),
                        pass.get("output").getAsString(), effect));
            }
            FxWorldGraph graph = new FxWorldGraph(stages, json.get("output").getAsString());
            graph.compile(1, 1);
            return new FxEffect(id, file.getFileName().toString(), root, FxEffect.Kind.WORLD_GRAPH,
                    Map.of(), 1, FxEffect.IDENTITY, List.of("世界颜色渲染图；每个 pass 独立写入 RGBA16F，保留画面 alpha。"), graph);
        } catch (RuntimeException e) { throw new IOException("无效 FX 图: " + e.getMessage(), e); }
    }
}
