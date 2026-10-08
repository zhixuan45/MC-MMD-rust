package com.shiroha.mmdskin.ui.selector.adapter;

import com.shiroha.mmdskin.bridge.runtime.NativeScenePort;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.model.runtime.ModelRequestKey;
import com.shiroha.mmdskin.model.port.ModelDiagnosticsPort;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.ui.config.ModelSelectorConfig;
import com.shiroha.mmdskin.ui.selector.port.ModelSettingsRuntimeGateway;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/** 文件职责：把设置应用到选中玩家模型及所有同名已加载实例。 */
public class DefaultModelSettingsRuntimeGateway implements ModelSettingsRuntimeGateway {
    private final Supplier<? extends NativeScenePort> nativeScenePortSupplier;

    public DefaultModelSettingsRuntimeGateway(NativeScenePort nativeScenePort) {
        this(() -> nativeScenePort);
    }

    public DefaultModelSettingsRuntimeGateway(Supplier<? extends NativeScenePort> nativeScenePortSupplier) {
        this.nativeScenePortSupplier = nativeScenePortSupplier;
    }

    @Override
    public void applyConfigIfSelected(String modelName, ModelConfigData config) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        var runtime = ClientRenderRuntime.get();
        NativeScenePort nativeScenePort = nativeScenePortSupplier.get();
        String selectedModel = ModelSelectorConfig.getInstance().getSelectedModel();
        if (modelName.equals(selectedModel)) {
            ManagedModel playerModel = runtime.modelRepository()
                    .acquire(ModelRequestKey.player(minecraft.player, selectedModel));
            if (playerModel != null) {
                long handle = playerModel.modelInstance().getModelHandle();
                nativeScenePort.setEyeTrackingEnabled(handle, config.eyeTrackingEnabled);
                nativeScenePort.setEyeMaxAngle(handle, config.eyeMaxAngle);
            }
        }

        ModelDiagnosticsPort diagnostics = runtime.modelDiagnostics();
        List<TailPhysicsTarget> targets = diagnostics.loadedModels().stream()
                .map(loadedModel -> new TailPhysicsTarget(
                        loadedModel.modelName(), loadedModel.modelInstance().getModelHandle()))
                .toList();
        // 同名设置同步到仓储和扩展持有的全部已加载实例。
        applyTailOptionsToMatchingModels(modelName, config, targets, nativeScenePort);
    }

    static void applyTailOptionsToMatchingModels(
            String modelName, ModelConfigData config, Iterable<TailPhysicsTarget> targets, NativeScenePort port) {
        for (TailPhysicsTarget target : targets) {
            if (modelName.equals(target.modelName())) {
                port.setTailPhysicsOptions(
                        target.modelHandle(), config.tailIdleLiftEnabled, config.tailMovementBoostEnabled);
            }
        }
    }

    record TailPhysicsTarget(String modelName, long modelHandle) {
    }
}
