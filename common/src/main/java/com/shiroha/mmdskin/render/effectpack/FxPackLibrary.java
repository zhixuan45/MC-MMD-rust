package com.shiroha.mmdskin.render.effectpack;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;

/** 有界资源导入与独立 FX 诊断；不会执行 MME Script 或附件中的命令。 */
public final class FxPackLibrary {
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final int MAX_FILES = 4096;
    public record Failure(String file, String reason) { }
    public record Scan(List<FxEffect> effects, List<Failure> failures) { }

    public static synchronized Scan scan(Path library) throws IOException {
        library = library.toAbsolutePath().normalize();
        return scan(library, library);
    }

    private static Scan scan(Path library, Path searchRoot) throws IOException {
        var effects = new ArrayList<FxEffect>();
        var failures = new ArrayList<Failure>();
        if (!Files.isDirectory(library)) return new Scan(List.of(), List.of());
        var budget = new FxSource.Budget();
        try (var paths = Files.walk(searchRoot, 32)) {
            List<Path> entries = paths.limit(MAX_FILES * 4L + 1).toList();
            if (entries.size() > MAX_FILES * 4) throw new IOException("效果库目录条目过多");
            List<Path> files = entries.stream().filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".fx")
                            || p.toString().toLowerCase(Locale.ROOT).endsWith(".fxgraph.json"))
                    .sorted().limit(MAX_FILES + 1).toList();
            if (files.size() > MAX_FILES) throw new IOException("FX 文件数量超限");
            for (Path file : files) {
                String id = library.relativize(file).toString().replace('\\', '/');
                try {
                    FxPaths.resolve(library, file.getParent(), file.getFileName().toString());
                    Path packRoot = library.resolve(library.relativize(file).getName(0));
                    // 库中的每个顶层目录为独立包，不允许跨包 include/贴图。
                    if (!Files.isDirectory(packRoot)) throw new IOException("FX 必须放在效果包子目录内");
                    String source = budget.read(file);
                    FxEffect effect = file.toString().toLowerCase(Locale.ROOT).endsWith(".fxgraph.json")
                            ? FxWorldGraph.parse(packRoot, file, source, id, budget)
                            : source.contains("material_common_2.0.fxsub")
                            ? RayMaterialImporter.parse(packRoot, file, source, id, budget)
                            : MmeColorImporter.parse(packRoot, file, source, id);
                    effects.add(effect);
                } catch (IOException | IllegalArgumentException e) { failures.add(new Failure(id, e.getMessage())); }
            }
        }
        return new Scan(List.copyOf(effects), List.copyOf(failures));
    }

    public static synchronized Path importPack(Path source, Path library) throws IOException {
        source = source.toAbsolutePath().normalize();
        library = library.toAbsolutePath().normalize();
        if (!Files.exists(source) || source.startsWith(library) || library.startsWith(source))
            throw new IOException("请选择效果库以外的 ZIP、FX 或资源目录");
        Files.createDirectories(library);
        String base = source.getFileName().toString().replaceAll("[^\\p{L}\\p{N}._-]", "_").replaceFirst("(?i)\\.(zip|fx)$", "");
        if (base.isBlank()) base = "effects";
        Path destination = Files.createTempDirectory(library, base + "-");
        long[] size = {0};
        int[] count = {0};
        try {
            if (Files.isDirectory(source)) {
                try (var paths = Files.walk(source)) {
                    List<Path> entries = paths.limit(MAX_FILES * 4L + 1).sorted().toList();
                    if (entries.size() > MAX_FILES * 4) throw new IOException("资源目录条目过多");
                    for (Path path : entries) {
                        FxPaths.resolve(source, source, source.relativize(path).toString().isEmpty() ? "." : source.relativize(path).toString());
                        Path target = FxPaths.resolve(destination, destination, source.relativize(path).toString().isEmpty() ? "." : source.relativize(path).toString());
                        if (Files.isSymbolicLink(path)) throw new IOException("不接受符号链接");
                        if (Files.isRegularFile(path)) try (InputStream in = Files.newInputStream(path)) { copy(in, target, size, count); }
                    }
                }
            } else if (source.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source), StandardCharsets.UTF_8)) {
                    java.util.zip.ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        Path target = FxPaths.resolve(destination, destination, entry.getName());
                        if (entry.isDirectory()) {
                            countEntry(count);
                            if (zip.read() != -1) throw new IOException("ZIP 目录条目必须为空");
                        } else {
                            copy(zip, target, size, count);
                        }
                    }
                }
            } else if (source.toString().toLowerCase(Locale.ROOT).endsWith(".fx")) {
                // 单文件导入只复制该 FX 的显式相邻依赖，不能把整个 Downloads 目录复制进游戏。
                var queue = new ArrayList<Path>();
                var seen = new java.util.HashSet<Path>();
                var budget = new FxSource.Budget();
                queue.add(source);
                for (int i = 0; i < queue.size(); i++) {
                    Path path = queue.get(i);
                    if (!seen.add(path)) continue;
                    Path target = FxPaths.resolve(destination, destination, source.getParent().relativize(path).toString());
                    try (InputStream in = Files.newInputStream(path)) { copy(in, target, size, count); }
                    if (!path.toString().matches("(?i).*\\.(fx|fxsub)$")) continue;
                    String code = FxSource.withoutComments(budget.read(path));
                    var refs = java.util.regex.Pattern.compile("#include\\s+\"([^\"]+)\"|#define\\s+\\w+_FILE\\s+\"([^\"]+)\"").matcher(code);
                    while (refs.find()) {
                        String name = refs.group(1) != null ? refs.group(1) : refs.group(2);
                        Path dependency = FxPaths.resolve(source.getParent(), path.getParent(), name);
                        if (Files.isRegularFile(dependency)) queue.add(dependency);
                        if (queue.size() > MAX_FILES) throw new IOException("依赖文件数量超限");
                    }
                }
            } else throw new IOException("请选择 ZIP、FX 或资源目录");
            Scan result = scan(library, destination);
            String prefix = destination.getFileName() + "/";
            var report = new java.util.LinkedHashMap<String, Object>();
            report.put("format", 1);
            report.put("supported", result.effects().stream().filter(e -> e.id().startsWith(prefix)).map(e -> java.util.Map.of("id", e.id(), "kind", e.kind(), "notes", e.notes())).toList());
            report.put("unsupported", result.failures().stream().filter(f -> f.file().startsWith(prefix)).toList());
            var gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(destination.resolve("import-report.json"), gson.toJson(report));
            var converted = new ArrayList<MapEntry>();
            for (FxEffect effect : result.effects()) {
                if (!effect.id().startsWith(prefix)) continue;
                String local = effect.id().substring(prefix.length());
                String generated = "_converted/" + local + (effect.graph() != null ? ".graph.json" : effect.supportsWorld() ? ".glsl" : ".material.json");
                Path output = FxPaths.resolve(destination, destination, generated);
                Files.createDirectories(output.getParent());
                Files.writeString(output, effect.graph() != null ? gson.toJson(java.util.Map.of("format", 1, "output", effect.graph().output(),
                        "passes", effect.graph().stages().stream().map(s -> java.util.Map.of("name", s.name(), "input", s.input(), "output", s.output(), "colorFunction", s.effect().colorFunction())).toList()))
                        : effect.supportsWorld() ? effect.colorFunction()
                        : gson.toJson(java.util.Map.of("format", 1, "channels", effect.channels(), "alpha", effect.alpha())));
                converted.add(new MapEntry(local, effect.kind().name(), generated));
            }
            Files.writeString(destination.resolve("effectpack.json"), gson.toJson(java.util.Map.of("format", 1, "effects", converted)));
            return destination;
        } catch (IOException | RuntimeException e) {
            // 只清理本次创建的临时包目录，保留已有包和原始资源。
            try (var paths = Files.walk(destination)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
            throw e;
        }
    }
    private record MapEntry(String source, String kind, String converted) { }

    private static void countEntry(int[] count) throws IOException {
        if (++count[0] > MAX_FILES) throw new IOException("效果包条目数量超过 " + MAX_FILES);
    }

    private static void copy(InputStream in, Path destination, long[] size, int[] count) throws IOException {
        countEntry(count);
        if (Files.exists(destination)) throw new IOException("重复资源路径: " + destination.getFileName());
        Files.createDirectories(destination.getParent());
        byte[] buffer = new byte[16384];
        try (var out = Files.newOutputStream(destination)) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                size[0] += n;
                if (size[0] > MAX_BYTES) throw new IOException("效果包解压大小超过 256 MiB");
                out.write(buffer, 0, n);
            }
        }
    }
    private FxPackLibrary() { }
}
