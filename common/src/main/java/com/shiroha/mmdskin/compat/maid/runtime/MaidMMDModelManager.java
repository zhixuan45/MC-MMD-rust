/** 文件职责：管理女仆实体与 MMD 模型绑定关系及已加载模型引用。 */
package com.shiroha.mmdskin.compat.maid.runtime;

import com.shiroha.mmdskin.config.UIConstants;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.model.runtime.ModelInstance;
import com.shiroha.mmdskin.model.runtime.ModelRequestKey;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.asset.catalog.ModelCatalogEntry;
import com.shiroha.mmdskin.ui.config.ModelSelectorConfig;
import com.shiroha.mmdskin.compat.iris.IrisCompat;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MaidMMDModelManager {
    private static final Logger logger = LogManager.getLogger();

    private static final Map<UUID, String> maidModelBindings = new ConcurrentHashMap<>();
    private static final Map<UUID, ManagedModel> loadedModels = new ConcurrentHashMap<>();
    private static final Map<UUID, String> unavailableModels = new ConcurrentHashMap<>();

    public static void init() {
    }

    public static void bindModel(UUID maidUUID, String modelName) {
        if (maidUUID == null) return;
        if (modelName == null || modelName.isEmpty() || UIConstants.DEFAULT_MODEL_NAME.equals(modelName)) {
            maidModelBindings.remove(maidUUID);
            loadedModels.remove(maidUUID);
            unavailableModels.remove(maidUUID);
            return;
        }

        String oldModel = maidModelBindings.get(maidUUID);
        if (modelName.equals(oldModel)) {
            return;
        }

        loadedModels.remove(maidUUID);
        maidModelBindings.put(maidUUID, modelName);
        unavailableModels.remove(maidUUID);
    }

    /** 本地选择先强制落盘，再让查询路径自然解析到该偏好。 */
    public static void selectLocalModel(UUID maidUUID, String modelName) {
        ModelSelectorConfig.getInstance().setMaidModelPreference(maidUUID, modelName);
        loadedModels.remove(maidUUID);
        unavailableModels.remove(maidUUID);
    }

    public static void unbindModel(UUID maidUUID) {
        if (maidUUID == null) return;
        maidModelBindings.remove(maidUUID);
        loadedModels.remove(maidUUID);
        unavailableModels.remove(maidUUID);
    }

    public static String getBindingModelName(UUID maidUUID) {
        if (maidUUID == null) return UIConstants.DEFAULT_MODEL_NAME;
        String localPreference = ModelSelectorConfig.getInstance().getMaidModelPreference(maidUUID);
        return MaidModelBindingResolver.resolve(localPreference, maidModelBindings.get(maidUUID));
    }

    public static boolean hasMMDModel(UUID maidUUID) {
        String modelName = getBindingModelName(maidUUID);
        return !UIConstants.DEFAULT_MODEL_NAME.equals(modelName) && isModelAvailable(maidUUID, modelName);
    }

    public static ManagedModel getModel(UUID maidUUID) {
        String modelName = getBindingModelName(maidUUID);
        if (maidUUID == null || UIConstants.DEFAULT_MODEL_NAME.equals(modelName)) {
            return null;
        }

        ManagedModel model = loadedModels.get(maidUUID);
        if (model != null) {
            if (model.modelInstance() != null && model.modelInstance().getModelHandle() != 0) {
                return model;
            }
            loadedModels.remove(maidUUID);
            logger.warn("Maid model handle became invalid: {}", maidUUID);
        }

        if (!isModelAvailable(maidUUID, modelName)) return null;
        // 阴影绘制阶段仓库会主动跳过模型请求，不把这类空结果当成加载失败。
        if (IrisCompat.isRenderingShadows()) return null;

        ModelRequestKey requestKey = ModelRequestKey.maid(maidUUID, modelName);
        model = ClientRenderRuntime.get().modelRepository().acquire(requestKey);
        if (model != null) {
            loadedModels.put(maidUUID, model);
        }
        return model;
    }

    private static boolean isModelAvailable(UUID maidUUID, String modelName) {
        if (modelName == null || UIConstants.DEFAULT_MODEL_NAME.equals(modelName)) return false;
        if (modelName.equals(unavailableModels.get(maidUUID))) return false;
        boolean present = ModelCatalogEntry.scanModels().stream()
                .anyMatch(entry -> modelName.equals(entry.getDisplayName()));
        if (!present) unavailableModels.put(maidUUID, modelName);
        return present;
    }

    public static void playAnimation(UUID maidUUID, String animId) {
        ManagedModel model = getModel(maidUUID);
        if (model == null) {
            logger.warn("Cannot play maid animation, no model bound: {}", maidUUID);
            return;
        }

        ModelInstance modelInstance = model.modelInstance();
        long animation = model.animationLibrary().animation(animId);
        if (animation != 0L) {
            modelInstance.transitionAnim(animation, 0, 0.25f);
        } else {
            logger.warn("Maid animation not found: {} {}", maidUUID, animId);
        }
    }

    public static void onModelDisposed(ManagedModel disposedModel) {
        if (disposedModel == null) {
            return;
        }
        loadedModels.entrySet().removeIf(entry -> entry.getValue() == disposedModel);
        if (disposedModel.requestKey().subjectKind() == com.shiroha.mmdskin.model.runtime.ModelSubjectKind.MAID) {
            try {
                unavailableModels.remove(UUID.fromString(disposedModel.requestKey().subjectId()));
            } catch (IllegalArgumentException ignored) {
                // 非 UUID 的异常缓存键不影响其他女仆恢复。
            }
        }
    }

    public static void invalidateLoadedModels() {
        loadedModels.clear();
        unavailableModels.clear();
    }

    /** 模型目录显式刷新后解除暂缺抑制，允许下一次渲染重新交给仓库加载。 */
    public static void onModelCatalogRefreshed() {
        unavailableModels.clear();
    }

    public static void clearAll() {
        // 断开连接只丢弃会话远端绑定与加载引用；本地偏好保存在 ModelSelectorConfig。
        maidModelBindings.clear();
        loadedModels.clear();
        unavailableModels.clear();
    }

    public static int getBindingCount() {
        return maidModelBindings.size();
    }

    public static Collection<ManagedModel> getLoadedMaidModels() {
        return loadedModels.values();
    }
}
