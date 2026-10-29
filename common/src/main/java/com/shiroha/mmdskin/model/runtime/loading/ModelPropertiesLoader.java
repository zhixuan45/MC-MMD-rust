package com.shiroha.mmdskin.model.runtime.loading;

import com.shiroha.mmdskin.asset.catalog.ModelCatalogEntry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class ModelPropertiesLoader {

    private static final Logger logger = LogManager.getLogger();

    private ModelPropertiesLoader() {
    }

    public static void load(String modelName, Properties properties) {
        properties.clear();

        ModelCatalogEntry info = ModelCatalogEntry.findByFolderName(modelName);
        if (info == null) {
            logger.warn("模型属性加载失败，模型未找到: {}", modelName);
            return;
        }

        loadFromDirectory(info.getFolderPath(), properties);
    }

    public static void loadFromDirectory(String modelDir, Properties properties) {
        properties.clear();
        if (modelDir == null || modelDir.isBlank()) {
            return;
        }
        File pathToProperties = new File(modelDir, "model.properties");
        try (InputStream stream = new FileInputStream(pathToProperties)) {
            properties.load(stream);
            logger.debug("模型属性加载成功: {}", modelDir);
        } catch (IOException e) {
            logger.debug("模型属性文件未找到: {}", modelDir);
        }
    }
}
