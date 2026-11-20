package com.shiroha.mmdskin.asset.catalog;

import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.config.PathConstants;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Resolves a prop independently of its owner's character folder. */
public final class HeldPmxAssets {
    private HeldPmxAssets() { }

    public static String modelName(String owner, String reference) {
        String safe = ModelConfigData.normalizeHeldPmxReference(reference);
        int separator = safe.indexOf('/');
        return separator < 0 ? owner : safe.substring(0, separator);
    }

    public static String fileName(String reference) {
        String safe = ModelConfigData.normalizeHeldPmxReference(reference);
        return safe.substring(safe.indexOf('/') + 1);
    }

    public static Path motionDirectory(String owner, String reference) {
        return modelDirectory(PathConstants.getEntityPlayerDir().toPath(), modelName(owner, reference));
    }

    public static ModelCatalogEntry resolve(String owner, String reference) {
        return resolve(PathConstants.getEntityPlayerDir().toPath(), owner, reference);
    }

    static ModelCatalogEntry resolve(Path root, String owner, String reference) {
        if (ModelConfigData.normalizeHeldPmxReference(reference).isEmpty()) return null;
        String folder = modelName(owner, reference);
        Path directory = modelDirectory(root, folder);
        if (directory == null) return null;
        var file = ModelCatalogEntry.resolvePmxFile(directory.toFile(), fileName(reference));
        if (file == null) return null;
        try {
            Path realFile = file.toPath().toRealPath();
            if (!realFile.getParent().equals(directory)) return null;
            return new ModelCatalogEntry(folder, realFile.getParent().toString(), realFile.toString(),
                    file.getName(), false, false, Files.size(realFile));
        } catch (IOException unavailable) {
            return null;
        }
    }

    private static Path modelDirectory(Path root, String folder) {
        if (folder == null || ModelConfigData.normalizeHeldPmxReference(folder + "/model.pmx").isEmpty()) return null;
        Path directory = root.resolve(folder);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            Path realRoot = root.toRealPath();
            Path realDirectory = directory.toRealPath();
            return realDirectory.equals(realRoot.resolve(folder)) ? realDirectory : null;
        } catch (IOException unavailable) {
            return null;
        }
    }

    public static List<String> list(String owner) {
        List<String> result = new ArrayList<>(ModelCatalogEntry.listPmxFiles(owner));
        for (ModelCatalogEntry entry : ModelCatalogEntry.scanModels()) {
            if (entry.getFolderName().equals(owner)) continue;
            for (String file : ModelCatalogEntry.listPmxFiles(entry.getFolderName())) {
                String reference = entry.getFolderName() + "/" + file;
                if (!ModelConfigData.normalizeHeldPmxReference(reference).isEmpty()) result.add(reference);
            }
        }
        return List.copyOf(result);
    }
}
