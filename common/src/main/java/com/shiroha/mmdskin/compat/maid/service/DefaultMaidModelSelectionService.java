/** 文件职责：编排女仆模型选择后的绑定、语音事件与远端同步。 */
package com.shiroha.mmdskin.compat.maid.service;

import com.shiroha.mmdskin.asset.catalog.ModelCatalogEntry;
import com.shiroha.mmdskin.compat.maid.network.MaidModelNetworkHandler;
import com.shiroha.mmdskin.compat.maid.runtime.MaidMMDModelManager;
import com.shiroha.mmdskin.config.UIConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.BiPredicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DefaultMaidModelSelectionService implements MaidModelSelectionService {
    private static final Logger logger = LogManager.getLogger();
    private final Supplier<List<String>> availableModelsSupplier;
    private final Function<UUID, String> currentModelReader;
    private final MaidModelBindingPort bindingPort;
    private final MaidModelSyncPort syncPort;
    private final BiPredicate<UUID, Integer> entitySyncEligibility;

    public DefaultMaidModelSelectionService() {
        this(DefaultMaidModelSelectionService::scanAvailableModels, MaidMMDModelManager::getBindingModelName,
                MaidMMDModelManager::selectLocalModel, MaidModelNetworkHandler.getInstance(),
                DefaultMaidModelSelectionService::isCurrentMaidEntity);
    }

    DefaultMaidModelSelectionService(Supplier<List<String>> availableModelsSupplier,
                                     Function<UUID, String> currentModelReader,
                                     MaidModelBindingPort bindingPort,
                                     MaidModelSyncPort syncPort) {
        this(availableModelsSupplier, currentModelReader, bindingPort, syncPort,
                (maidUUID, entityId) -> entityId > 0);
    }

    DefaultMaidModelSelectionService(Supplier<List<String>> availableModelsSupplier,
                                     Function<UUID, String> currentModelReader,
                                     MaidModelBindingPort bindingPort,
                                     MaidModelSyncPort syncPort,
                                     BiPredicate<UUID, Integer> entitySyncEligibility) {
        this.availableModelsSupplier = Objects.requireNonNull(availableModelsSupplier, "availableModelsSupplier");
        this.currentModelReader = Objects.requireNonNull(currentModelReader, "currentModelReader");
        this.bindingPort = Objects.requireNonNull(bindingPort, "bindingPort");
        this.syncPort = Objects.requireNonNull(syncPort, "syncPort");
        this.entitySyncEligibility = Objects.requireNonNull(entitySyncEligibility, "entitySyncEligibility");
    }

    @Override
    public List<String> loadAvailableModels() {
        List<String> availableModels = List.copyOf(availableModelsSupplier.get());
        MaidMMDModelManager.onModelCatalogRefreshed();
        return availableModels;
    }

    @Override
    public String getCurrentModel(UUID maidUUID) {
        String currentModel = currentModelReader.apply(maidUUID);
        return currentModel == null || currentModel.isEmpty() ? UIConstants.DEFAULT_MODEL_NAME : currentModel;
    }

    @Override
    public void selectModel(UUID maidUUID, int maidEntityId, String modelName) {
        if (maidUUID == null || modelName == null || modelName.isEmpty()) {
            return;
        }
        // 本地偏好先同步原子落盘；失败会抛出异常给界面显示，不能伪装成成功。
        bindingPort.bindModel(maidUUID, modelName);
        // 旧载荷要求实体 ID；暂时不可见的女仆仅保存本地偏好，不发送无效 ID。
        if (maidEntityId > 0) {
            try {
                if (entitySyncEligibility.test(maidUUID, maidEntityId)) {
                    syncPort.syncMaidModel(maidEntityId, modelName);
                }
            } catch (RuntimeException exception) {
                // 网络同步是附加行为，异常不撤销已经保存的本地选择。
                logger.warn("女仆本地偏好已保存，但网络同步失败: {}", maidUUID, exception);
            }
        }
    }

    private static List<String> scanAvailableModels() {
        // 选择器显式刷新时立即重扫目录，随后释放暂缺模型的加载抑制状态。
        ModelCatalogEntry.invalidateCache();
        List<String> models = new ArrayList<>();
        models.add(UIConstants.DEFAULT_MODEL_NAME);
        for (ModelCatalogEntry info : ModelCatalogEntry.scanModels()) {
            models.add(info.getDisplayName());
        }
        return models;
    }

    private static boolean isCurrentMaidEntity(UUID maidUUID, int entityId) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (entityId <= 0 || maidUUID == null || minecraft.getConnection() == null || minecraft.level == null) {
            return false;
        }
        net.minecraft.world.entity.Entity entity = minecraft.level.getEntity(entityId);
        return entity != null && maidUUID.equals(entity.getUUID());
    }

    interface MaidModelBindingPort {
        void bindModel(UUID maidUUID, String modelName);
    }
}
