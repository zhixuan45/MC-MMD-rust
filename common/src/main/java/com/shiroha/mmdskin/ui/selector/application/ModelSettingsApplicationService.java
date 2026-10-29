package com.shiroha.mmdskin.ui.selector.application;

import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.asset.catalog.ModelCatalogEntry;
import java.util.Objects;
import com.shiroha.mmdskin.ui.config.ModelSelectorConfig;
import com.shiroha.mmdskin.ui.selector.port.ModelSettingsGateway;
import com.shiroha.mmdskin.ui.selector.port.ModelSettingsRuntimeGateway;

import java.util.ArrayList;
import java.util.List;

public class ModelSettingsApplicationService {
    private final ModelSettingsGateway gateway;
    private final ModelSettingsRuntimeGateway runtimeGateway;

    public ModelSettingsApplicationService(ModelSettingsGateway gateway,
                                           ModelSettingsRuntimeGateway runtimeGateway) {
        this.gateway = gateway;
        this.runtimeGateway = runtimeGateway;
    }

    public ModelConfigData loadEditableConfig(String modelName) {
        return gateway.loadConfig(modelName).copy();
    }

    public void save(String modelName, ModelConfigData config) {
        ModelConfigData previous = gateway.loadConfig(modelName);
        ModelConfigData normalized = config.normalizedCopy();
        List<String> available = ModelCatalogEntry.listPmxFiles(modelName);
        // Never persist a filename that does not exist in this model folder.
        if (!normalized.mainPmxFile.isEmpty() && !available.contains(normalized.mainPmxFile)) {
            normalized.mainPmxFile = "";
        }
        if (!normalized.heldPmxFile.isEmpty() && !available.contains(normalized.heldPmxFile)) {
            normalized.heldPmxFile = "";
        }
        gateway.saveConfig(modelName, normalized);
        if (!Objects.equals(previous.mainPmxFile, normalized.mainPmxFile)
                || !Objects.equals(previous.heldPmxFile, normalized.heldPmxFile)) {
            runtimeGateway.reloadModel(modelName);
        }
        runtimeGateway.applyConfigIfSelected(modelName, normalized);
    }

    public List<String> listPmxFiles(String modelName) {
        return ModelCatalogEntry.listPmxFiles(modelName);
    }

    public List<String> listBoneNames(String modelName) { return runtimeGateway.listBoneNames(modelName); }

    public List<String> listPropMotions(String modelName) {
        return com.shiroha.mmdskin.asset.catalog.HeldPmxMotionFiles.list(
                com.shiroha.mmdskin.config.PathConstants.getModelDir(modelName).toPath());
    }

    public ModelConfigData resetToDefaults() {
        return new ModelConfigData();
    }

    public List<QuickSlotBinding> getQuickSlotBindings(String modelName) {
        List<QuickSlotBinding> bindings = new ArrayList<>();
        for (int i = 0; i < ModelSelectorConfig.QUICK_SLOT_COUNT; i++) {
            String boundModel = gateway.getQuickSlotModel(i);
            bindings.add(new QuickSlotBinding(i, boundModel, modelName.equals(boundModel)));
        }
        return bindings;
    }

    public void toggleQuickSlot(String modelName, int slot) {
        String currentBound = gateway.getQuickSlotModel(slot);
        if (modelName.equals(currentBound)) {
            gateway.setQuickSlotModel(slot, null);
            return;
        }
        gateway.setQuickSlotModel(slot, modelName);
    }

    public record QuickSlotBinding(int slot, String boundModel, boolean boundToCurrentModel) {
    }
}
