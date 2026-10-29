package com.shiroha.mmdskin.ui.selector.port;

import com.shiroha.mmdskin.config.ModelConfigData;

public interface ModelSettingsRuntimeGateway {
    void applyConfigIfSelected(String modelName, ModelConfigData config);

    default void reloadModel(String modelName) { }

    default java.util.List<String> listBoneNames(String modelName) { return java.util.List.of(); }
}
