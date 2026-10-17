package com.shiroha.mmdskin.player.render;

import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.model.runtime.ModelRequestKey;
import com.shiroha.mmdskin.player.animation.AnimationStateManager;
import com.shiroha.mmdskin.render.backend.BaseModelInstance;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.render.pipeline.NativeEvaluationBatch;
import com.shiroha.mmdskin.render.pipeline.RenderPerformanceProfiler;
import com.shiroha.mmdskin.render.scene.MutableRenderPose;
import net.minecraft.client.player.AbstractClientPlayer;

/** 为跨包预处理封装玩家渲染内部策略。 */
public final class PlayerEvaluationAdapter {
    private PlayerEvaluationAdapter() {}

    /** 只查询当前已加载且允许渲染的远端玩家模型。 */
    public static ManagedModel peekLoadedRemoteModel(AbstractClientPlayer player) {
        PlayerRenderSelection selection = PlayerRenderSelectionResolver.resolve(player, false);
        if (selection.hasTerminalAction() || selection.selectedModel() == null) return null;
        ManagedModel data = ClientRenderRuntime.get().modelRepository()
                .peek(ModelRequestKey.player(player, selection.selectedModel()));
        if (data == null || data.entityState().playStageAnim) return null;
        return data;
    }

    /** 在渲染线程同步玩家输入后准备本次原生求值。 */
    public static NativeEvaluationBatch.Work prepare(AbstractClientPlayer player, ManagedModel data,
            float tickDelta, RenderPerformanceProfiler.Scene scene) {
        if (!(data.modelInstance() instanceof BaseModelInstance model)) return null;
        AnimationStateManager.updateAnimationState(player, data);
        PlayerModelRenderCoordinator.consumePendingSignals(player, data, false);
        MutableRenderPose pose = PlayerRenderHelper.calculateMutableRenderPose(player, data, tickDelta);
        return model.prepareWorldEvaluation(player, pose.bodyYaw, tickDelta, scene);
    }
}
