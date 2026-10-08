package com.shiroha.mmdskin.ui.config;

import com.shiroha.mmdskin.compat.maid.model.MaidModelRepositoryExtension;
import com.shiroha.mmdskin.compat.maid.runtime.MaidMMDModelManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 确认远端会话绑定不写成本地偏好，仓库重载保留远端绑定，断连保留本地选择。 */
class MaidModelSessionLifecycleTest {
    @TempDir
    Path temporaryDirectory;

    private ModelSelectorConfig previousConfig;

    @AfterEach
    void resetSharedState() throws ReflectiveOperationException {
        MaidMMDModelManager.clearAll();
        replaceSingleton(previousConfig);
    }

    @Test
    void remoteBindingStaysSessionOnlyAcrossReloadAndClearAllPreservesLocalPreference() throws ReflectiveOperationException {
        UUID maidId = UUID.randomUUID();
        ModelSelectorConfig config = new ModelSelectorConfig(temporaryDirectory.resolve("model_selector.json").toFile());
        previousConfig = replaceSingleton(config);
        config.setMaidModelPreference(maidId, "LocalModel");

        MaidMMDModelManager.bindModel(maidId, "RemoteModel");
        assertEquals("LocalModel", config.getMaidModelPreference(maidId));
        assertEquals("LocalModel", MaidMMDModelManager.getBindingModelName(maidId));

        config.removeMaidModelPreference(maidId);
        assertEquals("RemoteModel", MaidMMDModelManager.getBindingModelName(maidId));
        MaidModelRepositoryExtension.INSTANCE.onRepositoryReloadAll();
        assertEquals("RemoteModel", MaidMMDModelManager.getBindingModelName(maidId));

        config.setMaidModelPreference(maidId, "LocalModel");
        MaidMMDModelManager.clearAll();
        assertEquals("LocalModel", config.getMaidModelPreference(maidId));
        assertEquals("LocalModel", MaidMMDModelManager.getBindingModelName(maidId));
    }

    private static ModelSelectorConfig replaceSingleton(ModelSelectorConfig config) throws ReflectiveOperationException {
        var field = ModelSelectorConfig.class.getDeclaredField("instance");
        field.setAccessible(true);
        ModelSelectorConfig previous = (ModelSelectorConfig) field.get(null);
        field.set(null, config);
        return previous;
    }
}
