package com.shiroha.mmdskin;

import com.shiroha.mmdskin.config.PathConstants;
import org.apache.commons.io.FileUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class MmdClientResourceBootstrap {
    private static final int BUFFER = 512;
    private static final long TOO_BIG = 0x6400000;
    private static final int TOO_MANY = 1024;
    private static final long MAX_STOCK_ANIM_BYTES = 256 * 1024;
    private static final Map<String, Set<String>> PREVIOUS_STOCK_ANIMS = Map.of(
            "walk.vmd", Set.of(
                    "85c2f2dda216609291772b06c2c2949b27306ecc8169dc46e70176c2586e51d1",
                    "36d4ca6307610e9edfff6e49433bced443effad0003bdb7913b8de4e16a9a648",
                    "c27c4f581a873f64171052d2526402585ef313ba272bc4d3745d898f3bcdb2e3",
                    "8a460f0264ca4f933907289f82ea82db99fbe2d8923468a32c314775701501f5",
                    "e3556d6c9d38a553f7b4fb237ff6c431096eca2a854c7fe163d1312e33d91bc3"),
            "sprint.vmd", Set.of(
                    "626f33919cba977069d8e9454e1d0649cd3e1a01c0b100690a28321098a1bf76",
                    "24c5f4bcbfe129f8268b3f4b3580cafcb6ad5ac3a4c63d9c7f7aa9edc7f82a2b",
                    "2d1aa8ae25441a66270f3b59810b7e2dfe82ef4342faaac237a2cfc1118611ca",
                    "78edcb6e6c083103b1676458beb20f5fe92d1a4cd35b865417a55684c7bf8076",
                    "f04a45f86f6e9e413da378827a25eaf6493fd7ce5cd7d4173b23bcc9f36d9e5a",
                    "25b9bb99f7f8ae7b35b398bd7e6d8889121a2bbe6048255de1dc0f466f197386",
                    "c119737ca21f32e483e19dedbaeece9a9e06a7f369f7b92cf2b8ea7c3610a265"),
            "sprintStop.vmd", Set.of(
                    "c93a22e1709f7fbffb84d0a97ae3ec004ed7482e951cf73ca0f0dfe2e2d07fea",
                    "27a59410037078a0429d098a119f12f2a8a7589640a3ee5282da91788e28a629"));
    private static final String[] DEFAULT_ANIM_FILES = {
            "Drink.vmd", "crawl.vmd", "die.vmd", "elytraFly.vmd", "idle.vmd",
            "itemActive_minecraft.bow_Left_using.vmd", "itemActive_minecraft.iron_sword_Right_swinging.vmd",
            "itemActive_minecraft.shield_Left_using.vmd", "itemActive_minecraft.shield_Right_using.vmd",
            "lieDown.vmd", "onClimbable.vmd", "onClimbableDown.vmd", "onClimbableUp.vmd",
            "onHorse.vmd", "ride.vmd", "sleep.vmd", "sneak.vmd",
            "sprint.vmd", "sprintStop.vmd", "swim.vmd", "swingLeft.vmd", "swingRight.vmd", "walk.vmd",
            // v5 使用重新录制的挂点动作，并由运行时兼容不同模型的 Dummy 名称。
            "tacz_hold_rifle_ads_v5.vmd"
    };

    private MmdClientResourceBootstrap() {
    }

    static void initialize() {
        check3DSkinFolder();
        extractDefaultAnimIfNeeded();
        ensureEntityPlayerDirectory();
    }

    private static void ensureEntityPlayerDirectory() {
        PathConstants.ensureDirectoryExists(PathConstants.getEntityPlayerDir());
        PathConstants.ensureDirectoryExists(PathConstants.getCustomAnimDir());
        PathConstants.ensureDirectoryExists(PathConstants.getCustomMorphDir());
        PathConstants.ensureDirectoryExists(PathConstants.getDefaultMorphDir());
        PathConstants.ensureDirectoryExists(PathConstants.getSceneModelDir());
    }

    private static void extractDefaultAnimIfNeeded() {
        File defaultAnimDir = PathConstants.getDefaultAnimDir();
        PathConstants.ensureDirectoryExists(defaultAnimDir);

        for (String fileName : DEFAULT_ANIM_FILES) {
            File targetFile = new File(defaultAnimDir, fileName);
            Path target = targetFile.toPath();
            try {
                boolean exists = targetFile.exists();
                if (exists && !isPreviousStockAnim(fileName, target)) {
                    continue;
                }
                try (InputStream is = MmdSkinClient.class.getResourceAsStream("/assets/mmdskin/default_anim/" + fileName)) {
                    if (is == null) {
                        continue;
                    }
                    if (exists) {
                        if (replaceStockAnim(fileName, is, target)) {
                            MmdSkinClient.logger.info("更新内置默认动画: {}", fileName);
                        }
                    } else {
                        Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                        MmdSkinClient.logger.info("提取默认动画: {}", fileName);
                    }
                }
            } catch (IOException e) {
                MmdSkinClient.logger.warn("提取动画文件失败: " + fileName, e);
            }
        }
    }

    private static boolean isPreviousStockAnim(String fileName, Path target) throws IOException {
        Set<String> hashes = PREVIOUS_STOCK_ANIMS.get(fileName);
        if (hashes == null || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) > MAX_STOCK_ANIM_BYTES) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target));
            return hashes.contains(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean replaceStockAnim(String fileName, InputStream source, Path target) throws IOException {
        Path staged = Files.createTempFile(target.getParent(), "anim-", ".tmp");
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            if (!isPreviousStockAnim(fileName, target)) {
                return false;
            }
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void check3DSkinFolder() {
        File skin3DFolder = PathConstants.getSkinRootDir();
        if (skin3DFolder.exists()) {
            return;
        }

        skin3DFolder.mkdir();
        String gameDir = PathConstants.getGameDirectory();
        File zipFile = new File(gameDir, PathConstants.RESOURCE_ZIP_NAME);

        boolean downloadSuccess = false;
        try {
            FileUtils.copyURLToFile(new URL(PathConstants.RESOURCE_DOWNLOAD_URL), zipFile, 30000, 30000);
            downloadSuccess = true;
        } catch (IOException e) {
            MmdSkinClient.logger.error("下载 3d-skin.zip 失败: {}", e.getMessage());
        }

        if (downloadSuccess) {
            try {
                unzip(zipFile.getAbsolutePath(), PathConstants.getSkinRootPath() + "/");
            } catch (IOException e) {
                MmdSkinClient.logger.error("解压 3d-skin.zip 失败: {}", e.getMessage());
            }
        }

        try {
            zipFile.delete();
        } catch (Exception ignored) {
        }
    }

    private static void unzip(String filename, String targetDir) throws IOException {
        ZipEntry entry;
        int entries = 0;
        long total = 0;
        try (FileInputStream fis = new FileInputStream(filename);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(fis))) {
            while ((entry = zis.getNextEntry()) != null) {
                int count;
                byte[] data = new byte[BUFFER];
                String name = validateFilename(targetDir + entry.getName(), targetDir);
                File targetFile = new File(name);
                if (entry.isDirectory()) {
                    new File(name).mkdir();
                    continue;
                }
                if (!targetFile.getParentFile().exists()) {
                    targetFile.getParentFile().mkdirs();
                }
                try (FileOutputStream fos = new FileOutputStream(name);
                     BufferedOutputStream dest = new BufferedOutputStream(fos, BUFFER)) {
                    while (total + BUFFER <= TOO_BIG && (count = zis.read(data, 0, BUFFER)) != -1) {
                        dest.write(data, 0, count);
                        total += count;
                    }
                    dest.flush();
                }
                zis.closeEntry();
                entries++;
                if (entries > TOO_MANY) {
                    throw new IllegalStateException("解压文件数量过多");
                }
                if (total + BUFFER > TOO_BIG) {
                    throw new IllegalStateException("解压文件体积过大");
                }
            }
        }
    }

    private static String validateFilename(String filename, String intendedDir) throws IOException {
        File file = new File(filename);
        String canonicalPath = file.getCanonicalPath();
        String canonicalDir = new File(intendedDir).getCanonicalPath();
        if (canonicalPath.startsWith(canonicalDir)) {
            return canonicalPath;
        }
        throw new IllegalStateException("文件在目标解压目录之外");
    }
}
