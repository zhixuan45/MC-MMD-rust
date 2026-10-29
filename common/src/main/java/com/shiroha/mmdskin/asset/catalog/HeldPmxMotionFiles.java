package com.shiroha.mmdskin.asset.catalog;

import com.shiroha.mmdskin.config.ModelConfigData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** The prop uses a separate VMD, resolved only in its character folder (anims/ takes precedence). */
public final class HeldPmxMotionFiles {
    private HeldPmxMotionFiles() { }

    public static List<String> list(Path modelDir) {
        if (Files.isSymbolicLink(modelDir)) return List.of();
        var names = new TreeSet<String>();
        for (Path dir : List.of(modelDir.resolve("anims"), modelDir)) {
            if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir)) continue;
            try (var files = Files.list(dir)) {
                files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .map(p -> p.getFileName().toString())
                        .filter(name -> !ModelConfigData.normalizeVmdFileName(name).isEmpty()).forEach(names::add);
            } catch (IOException ignored) { }
        }
        var result = new ArrayList<>(names);
        result.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder()));
        return List.copyOf(result);
    }

    public static Path resolve(Path modelDir, String name) {
        if (ModelConfigData.normalizeVmdFileName(name).isEmpty() || Files.isSymbolicLink(modelDir)) return null;
        for (Path dir : List.of(modelDir.resolve("anims"), modelDir)) {
            Path path = dir.resolve(name);
            if (!Files.isSymbolicLink(dir) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return path;
        }
        return null;
    }
}
