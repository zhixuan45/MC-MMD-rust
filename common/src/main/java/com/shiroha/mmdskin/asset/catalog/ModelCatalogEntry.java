package com.shiroha.mmdskin.asset.catalog;

import com.shiroha.mmdskin.config.PathConstants;
import com.shiroha.mmdskin.config.ModelConfigData;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;

/** 文件职责：扫描并缓存本地模型目录的元数据条目。 */
public class ModelCatalogEntry {
    private static final Logger logger = LogManager.getLogger();

    private record CacheSnapshot(List<ModelCatalogEntry> models, long timestamp) {}
    private static volatile CacheSnapshot cache = null;
    private record PmxSnapshot(List<String> files, long timestamp) {}
    private static final ConcurrentHashMap<String, PmxSnapshot> PMX_FILES = new ConcurrentHashMap<>();
    private static final long CACHE_TTL = 5000;

    private final String folderName;
    private final String folderPath;
    private final String modelFilePath;
    private final String modelFileName;
    private final boolean isPMD;
    private final boolean isVRM;
    private final long fileSize;

    public ModelCatalogEntry(String folderName, String folderPath, String modelFilePath, String modelFileName, boolean isPMD, boolean isVRM, long fileSize) {
        this.folderName = folderName;
        this.folderPath = folderPath;
        this.modelFilePath = modelFilePath;
        this.modelFileName = modelFileName;
        this.isPMD = isPMD;
        this.isVRM = isVRM;
        this.fileSize = fileSize;
    }

    public String getFolderName() { return folderName; }
    public String getDisplayName() { return folderName; }
    public String getFolderPath() { return folderPath; }
    public String getModelFilePath() { return modelFilePath; }
    public String getModelFileName() { return modelFileName; }
    public boolean isPMD() { return isPMD; }
    public boolean isVRM() { return isVRM; }
    public long getFileSize() { return fileSize; }
    public String getCatalogKey() { return folderName; }

    public String getFormattedSize() {
        if (fileSize < 1024) {
            return fileSize + " B";
        } else if (fileSize < 1024 * 1024) {
            return String.format("%.1f KB", fileSize / 1024.0);
        } else {
            return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
        }
    }

    public String getFormatDescription() {
        if (isVRM) return "VRM";
        return isPMD ? "PMD" : "PMX";
    }

    public static List<ModelCatalogEntry> scanModels() {
        CacheSnapshot snapshot = cache;
        long now = System.currentTimeMillis();
        if (snapshot != null && (now - snapshot.timestamp()) < CACHE_TTL) {
            return snapshot.models();
        }

        List<ModelCatalogEntry> models = new ArrayList<>();
        File entityPlayerDir = PathConstants.getEntityPlayerDir();

        if (!entityPlayerDir.exists() || !entityPlayerDir.isDirectory()) {
            logger.warn("EntityPlayer 目录不存在: {}", entityPlayerDir.getAbsolutePath());
            return models;
        }

        File[] modelDirs = entityPlayerDir.listFiles(File::isDirectory);
        if (modelDirs == null) {
            return models;
        }

        for (File modelDir : modelDirs) {
            ModelCatalogEntry info = scanModelFolder(modelDir);
            if (info != null) {
                models.add(info);
                logger.debug("发现模型: {} -> {}", info.getFolderName(), info.getModelFileName());
            }
        }

        models.sort((a, b) -> a.getFolderName().compareToIgnoreCase(b.getFolderName()));
        cache = new CacheSnapshot(models, System.currentTimeMillis());
        return models;
    }

    public static void invalidateCache() {
        cache = null;
        PMX_FILES.clear();
    }

    /** Only regular PMX files directly inside the selected model directory are eligible. */
    public static List<String> listPmxFiles(String folderName) {
        ModelCatalogEntry entry = findByFolderName(folderName);
        if (entry == null) return List.of();
        long now = System.currentTimeMillis();
        PmxSnapshot cached = PMX_FILES.get(folderName);
        if (cached != null && now - cached.timestamp() < CACHE_TTL) return cached.files();
        List<String> files = listPmxFiles(new File(entry.getFolderPath()));
        PMX_FILES.put(folderName, new PmxSnapshot(files, now));
        return files;
    }

    static List<String> listPmxFiles(File folder) {
        if (folder == null || !folder.isDirectory()) return List.of();
        File[] files = folder.listFiles(f -> f.isFile()
                && Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)
                && !ModelConfigData.normalizePmxFileName(f.getName()).isEmpty());
        if (files == null) return List.of();
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return Arrays.stream(files).map(File::getName).toList();
    }

    static File resolvePmxFile(File folder, String fileName) {
        if (folder == null || ModelConfigData.normalizePmxFileName(fileName).isEmpty()) return null;
        File candidate = new File(folder, fileName);
        return candidate.getParentFile().equals(folder)
                && Files.isRegularFile(candidate.toPath(), LinkOption.NOFOLLOW_LINKS) ? candidate : null;
    }

    /** Override the file without changing the folder-based model identity. */
    public static ModelCatalogEntry resolveModelFile(String folderName, String heldVariant) {
        ModelCatalogEntry base = findByFolderName(folderName);
        if (base == null) return null;
        String choice = heldVariant;
        if (choice == null || choice.isEmpty()) {
            choice = com.shiroha.mmdskin.config.ModelConfigManager.getConfig(folderName).mainPmxFile;
        }
        File selected = resolvePmxFile(new File(base.folderPath), choice);
        if (selected == null) return heldVariant != null && !heldVariant.isEmpty() ? null : base;
        return new ModelCatalogEntry(folderName, base.folderPath, selected.getAbsolutePath(),
                selected.getName(), false, false, selected.length());
    }

    private static ModelCatalogEntry scanModelFolder(File modelDir) {
        FileFilter pmxFilter = file -> file.isFile() && file.getName().toLowerCase().endsWith(".pmx");
        FileFilter pmdFilter = file -> file.isFile() && file.getName().toLowerCase().endsWith(".pmd");
        FileFilter vrmFilter = file -> file.isFile() && file.getName().toLowerCase().endsWith(".vrm");

        File[] pmxFiles = modelDir.listFiles(pmxFilter);
        if (pmxFiles != null && pmxFiles.length > 0) {
            File selected = findPreferredModel(pmxFiles);
            return new ModelCatalogEntry(modelDir.getName(), modelDir.getAbsolutePath(),
                    selected.getAbsolutePath(), selected.getName(), false, false, selected.length());
        }

        File[] pmdFiles = modelDir.listFiles(pmdFilter);
        if (pmdFiles != null && pmdFiles.length > 0) {
            File selected = findPreferredModel(pmdFiles);
            return new ModelCatalogEntry(modelDir.getName(), modelDir.getAbsolutePath(),
                    selected.getAbsolutePath(), selected.getName(), true, false, selected.length());
        }

        File[] vrmFiles = modelDir.listFiles(vrmFilter);
        if (vrmFiles != null && vrmFiles.length > 0) {
            File selected = findPreferredModel(vrmFiles);
            return new ModelCatalogEntry(modelDir.getName(), modelDir.getAbsolutePath(),
                    selected.getAbsolutePath(), selected.getName(), false, true, selected.length());
        }

        return null;
    }

    private static File findPreferredModel(File[] files) {
        if (files.length == 1) {
            return files[0];
        }
        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (name.equals("model.pmx") || name.equals("model.pmd")) {
                return file;
            }
        }
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return files[0];
    }

    public static ModelCatalogEntry findByFolderName(String folderName) {
        List<ModelCatalogEntry> models = scanModels();
        for (ModelCatalogEntry info : models) {
            if (info.getFolderName().equals(folderName)) {
                return info;
            }
        }
        return null;
    }
}
