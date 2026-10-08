package com.shiroha.mmdskin.ui.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shiroha.mmdskin.config.PathConstants;
import com.shiroha.mmdskin.config.UIConstants;
import com.shiroha.mmdskin.player.sync.PlayerModelSyncService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 模型选择配置管理。 */
public class ModelSelectorConfig {
    private static final Logger logger = LogManager.getLogger();
    private static ModelSelectorConfig instance;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final File configFile;
    private ConfigData data;

    private ModelSelectorConfig() {
        this(PathConstants.getModelSelectorConfigFile());
    }

    ModelSelectorConfig(File configFile) {
        this.configFile = configFile;
        load();
    }

    public static synchronized ModelSelectorConfig getInstance() {
        if (instance == null) {
            instance = new ModelSelectorConfig();
        }
        return instance;
    }

    private void load() {
        if (configFile.exists()) {
            int retryCount = 0;
            int maxRetries = 3;

            while (retryCount < maxRetries) {
                try (Reader reader = new InputStreamReader(new FileInputStream(configFile), java.nio.charset.StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    // 女仆字段独立逐项解析，避免单条坏记录使玩家模型与快捷槽位一并丢失。
                    data = parseConfigWithoutMaidPreferences(root);

                    if (data == null || data.playerModels == null) {
                        throw new IOException("配置数据无效");
                    }

                    if (data.quickModelSlots == null) {
                        data.quickModelSlots = new ConcurrentHashMap<>();
                    }
                    data.maidModelPreferences = parseMaidModelPreferences(root.get("maidModelPreferences"));

                    return;
                } catch (Exception e) {
                    retryCount++;
                    logger.warn("加载模型选择配置失败 (尝试 {}/{}): {}", retryCount, maxRetries, e.getMessage());

                    if (retryCount >= maxRetries) {
                        logger.error("配置加载失败，使用默认配置", e);
                        data = new ConfigData();
                        saveInternal();
                    }
                }
            }
        } else {
            data = new ConfigData();
            saveInternal();
        }
    }

    public synchronized void save() {
        saveInternal();
    }

    /** 保存女仆本地偏好，并在磁盘替换失败时向调用方报错。 */
    public synchronized void setMaidModelPreference(UUID maidUUID, String modelName) {
        if (maidUUID == null || modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("女仆 UUID 和模型名不能为空");
        }
        ensureData();
        String key = maidUUID.toString();
        String previous = data.maidModelPreferences.put(key, modelName);
        try {
            saveForced();
        } catch (RuntimeException exception) {
            if (previous == null) data.maidModelPreferences.remove(key);
            else data.maidModelPreferences.put(key, previous);
            throw exception;
        }
    }

    /** 返回指定女仆的本地覆盖；显式 Default 作为真实值返回，null 表示尚无本地选择。 */
    public synchronized String getMaidModelPreference(UUID maidUUID) {
        ensureData();
        return maidUUID == null ? null : data.maidModelPreferences.get(maidUUID.toString());
    }

    /** 清除本地覆盖与选择原版不同：清除后仍可回退到当前连接的远端绑定。 */
    public synchronized void removeMaidModelPreference(UUID maidUUID) {
        if (maidUUID == null) return;
        ensureData();
        String key = maidUUID.toString();
        String previous = data.maidModelPreferences.remove(key);
        if (previous == null) return;
        try {
            saveForced();
        } catch (RuntimeException exception) {
            data.maidModelPreferences.put(key, previous);
            throw exception;
        }
    }

    /** 快照用于诊断和测试，调用方无法修改配置内部映射。 */
    public synchronized Map<UUID, String> getMaidModelPreferences() {
        ensureData();
        Map<UUID, String> copy = new HashMap<>();
        data.maidModelPreferences.forEach((key, value) -> copy.put(UUID.fromString(key), value));
        return Map.copyOf(copy);
    }

    private void saveForced() {
        ensureData();
        writeConfigAtomically();
    }

    private void saveInternal() {

        int retryCount = 0;
        int maxRetries = 3;

        while (retryCount < maxRetries) {
            try {
                writeConfigAtomically();
                logger.debug("模型选择配置保存成功");
                return;
            } catch (Exception e) {
                retryCount++;
                logger.warn("保存模型选择配置失败 (尝试 {}/{}): {}", retryCount, maxRetries, e.getMessage());

                if (retryCount >= maxRetries) {
                    logger.error("配置保存失败", e);
                }
            }
        }
    }

    private void writeConfigAtomically() {
        PathConstants.ensureDirectoryExists(configFile.getParentFile());
        AtomicConfigFileWriter.write(configFile, writer -> gson.toJson(data, writer));
    }

    private ConfigData parseConfigWithoutMaidPreferences(JsonObject root) {
        JsonObject legacyFields = root.deepCopy();
        legacyFields.remove("maidModelPreferences");
        return gson.fromJson(legacyFields, ConfigData.class);
    }

    private Map<String, String> parseMaidModelPreferences(JsonElement element) {
        Map<String, String> preferences = new ConcurrentHashMap<>();
        if (element == null || element.isJsonNull()) return preferences;
        if (!element.isJsonObject()) {
            logger.warn("女仆模型偏好字段格式无效，忽略该字段并保留其他配置");
            return preferences;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            try {
                UUID.fromString(entry.getKey());
                if (!entry.getKey().matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                    throw new IllegalArgumentException("UUID 格式不完整");
                }
                JsonElement value = entry.getValue();
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("模型名不是字符串");
                }
                String modelName = value.getAsString();
                if (modelName.isBlank()) throw new IllegalArgumentException("模型名为空");
                preferences.put(UUID.fromString(entry.getKey()).toString(), modelName);
            } catch (RuntimeException exception) {
                logger.warn("忽略损坏的女仆模型偏好条目 {}: {}", entry.getKey(), exception.getMessage());
            }
        }
        return preferences;
    }

    private void ensureData() {
        if (data == null) data = new ConfigData();
        if (data.maidModelPreferences == null) data.maidModelPreferences = new ConcurrentHashMap<>();
    }

    public String getSelectedModel() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            return getPlayerModelByUuidOrName(mc.player.getUUID(), mc.player.getName().getString());
        }
        return UIConstants.DEFAULT_MODEL_NAME;
    }

    public String getPlayerModel(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return UIConstants.DEFAULT_MODEL_NAME;
        }
        return data.playerModels.getOrDefault(playerName, UIConstants.DEFAULT_MODEL_NAME);
    }

    /**
     * 按玩家 UUID（优先）或玩家名称查找本地配置的模型。
     */
    public String getPlayerModelByUuidOrName(java.util.UUID playerUuid, String playerName) {
        return resolvePlayerModel(data == null ? null : data.playerModels, playerUuid, playerName);
    }

    static String resolvePlayerModel(Map<String, String> playerModels, java.util.UUID playerUuid, String playerName) {
        if (playerModels == null) {
            return UIConstants.DEFAULT_MODEL_NAME;
        }
        if (playerUuid != null) {
            String uuidModel = playerModels.get(playerUuid.toString());
            if (uuidModel != null && !uuidModel.isBlank() && !UIConstants.DEFAULT_MODEL_NAME.equals(uuidModel)) {
                return uuidModel;
            }
        }
        if (playerName != null && !playerName.isEmpty()) {
            String nameModel = playerModels.get(playerName);
            if (nameModel != null && !nameModel.isBlank() && !UIConstants.DEFAULT_MODEL_NAME.equals(nameModel)) {
                return nameModel;
            }
        }
        return UIConstants.DEFAULT_MODEL_NAME;
    }

    public void setSelectedModel(String modelName) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            String playerName = mc.player.getName().getString();
            if (getRawModel(mc.player.getUUID().toString()) != null) {
                setPlayerModelByUuid(mc.player.getUUID(), modelName);
                removePlayerModel(playerName);
            } else {
                setPlayerModel(playerName, modelName);
            }
        }
    }

    public void setPlayerModelByUuid(java.util.UUID playerUuid, String modelName) {
        if (playerUuid != null) {
            setPlayerModel(playerUuid.toString(), modelName);
        }
    }

    public void setPlayerModel(String playerName, String modelName) {
        if (playerName == null || playerName.isEmpty()) {
            logger.warn("尝试为空玩家名设置模型");
            return;
        }

        if (modelName == null) {
            modelName = UIConstants.DEFAULT_MODEL_NAME;
        }

        data.playerModels.put(playerName, modelName);
        save();

        broadcastLocalModelIfBound(playerName);
    }

    public String getRawModel(String key) {
        if (data == null || data.playerModels == null || key == null) {
            return null;
        }
        return data.playerModels.get(key);
    }

    public void removePlayerModel(String playerName) {
        if (data.playerModels.remove(playerName) != null) {
            save();
            broadcastLocalModelIfBound(playerName);
        }
    }

    public void removePlayerModelByUuid(java.util.UUID uuid) {
        if (uuid != null) {
            removePlayerModel(uuid.toString());
        }
    }

    private void broadcastLocalModelIfBound(String key) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && (mc.player.getName().getString().equals(key)
                || mc.player.getUUID().toString().equals(key))) {
            PlayerModelSyncService.broadcastLocalModelSelection(mc.player.getUUID(),
                    getPlayerModelByUuidOrName(mc.player.getUUID(), mc.player.getName().getString()));
        }
    }

    public Map<String, String> getAllPlayerModels() {
        return new ConcurrentHashMap<>(data.playerModels);
    }

    public static final int QUICK_SLOT_COUNT = 4;

    public String getQuickSlotModel(int slot) {
        if (slot < 0 || slot >= QUICK_SLOT_COUNT) return null;
        String key = String.valueOf(slot);
        return data.quickModelSlots.get(key);
    }

    public void setQuickSlotModel(int slot, String modelName) {
        if (slot < 0 || slot >= QUICK_SLOT_COUNT) return;
        String key = String.valueOf(slot);
        if (modelName == null || modelName.isEmpty()) {
            data.quickModelSlots.remove(key);
        } else {
            data.quickModelSlots.put(key, modelName);
        }
        save();
    }

    public int getQuickSlotForModel(String modelName) {
        if (modelName == null) return -1;
        for (int i = 0; i < QUICK_SLOT_COUNT; i++) {
            String bound = data.quickModelSlots.get(String.valueOf(i));
            if (modelName.equals(bound)) return i;
        }
        return -1;
    }

    private static class ConfigData {
        Map<String, String> playerModels = new ConcurrentHashMap<>();

        Map<String, String> quickModelSlots = new ConcurrentHashMap<>();

        Map<String, String> maidModelPreferences = new ConcurrentHashMap<>();
    }
}
