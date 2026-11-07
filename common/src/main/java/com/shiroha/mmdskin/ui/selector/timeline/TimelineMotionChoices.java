package com.shiroha.mmdskin.ui.selector.timeline;

import com.shiroha.mmdskin.asset.catalog.HeldPmxMotionFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Reference motions supply preview and calibration; only labels and content hashes are persisted. */
public final class TimelineMotionChoices {
    private TimelineMotionChoices() { }

    public static Map<String,Path> list(Path modelDir,Path stageDir) {
        Map<String,Path> result = new TreeMap<>();
        for (String name : HeldPmxMotionFiles.list(modelDir)) {
            Path path = HeldPmxMotionFiles.resolve(modelDir,name);
            if (path != null) result.put("EntityPlayer/" + name,path);
        }
        if (Files.isDirectory(stageDir,LinkOption.NOFOLLOW_LINKS)) {
            try (var packs = Files.list(stageDir)) {
                for (Path pack : packs.filter(p -> Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList()) {
                    try (var files = Files.list(pack)) {
                        files.filter(p -> Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
                                .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".vmd"))
                                .forEach(p -> result.put("StageAnim/" + pack.getFileName() + "/" + p.getFileName(),p));
                    }
                }
            } catch (IOException ignored) { }
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }
}
