package com.shiroha.mmdskin.player.runtime;

import com.shiroha.mmdskin.expression.ExpressionApplicationService;
import com.shiroha.mmdskin.expression.ExpressionSelection;
import com.shiroha.mmdskin.model.runtime.ManagedModel;

/** An action-wheel pose owns the character's pose until normal animation resumes. */
public final class VpdPosePlayback {
    private VpdPosePlayback() { }

    public static ExpressionApplicationService.VpdResult apply(ManagedModel model, String path, String playerName) {
        return apply(model.modelInstance(), model.entityState(), path, playerName);
    }

    static ExpressionApplicationService.VpdResult apply(com.shiroha.mmdskin.model.runtime.ModelInstance model,
                                                         EntityAnimState state, String path, String playerName) {
        var result = ExpressionApplicationService.applyVpdWithResult(model.getModelHandle(), path, playerName);
        if (result.applied() && result.bones() > 0 && !state.playStageAnim) {
            // Keep ticking physics/skinning, but remove idle, held-item and damage VMD layers.
            for (int layer = 0; layer < state.stateLayers.length; layer++) {
                model.changeAnim(0, layer);
            }
            state.playVpdPose = true;
            state.playCustomAnim = true;
            state.invalidateStateLayers();
        } else if (result.applied() && result.bones() == 0 && state.playVpdPose) {
            state.playVpdPose = false;
            state.playCustomAnim = false;
            state.invalidateStateLayers();
        }
        return result;
    }

    public static void resumeAfterReset(ManagedModel model) {
        resumeAfterReset(model.modelInstance(), model.entityState());
    }

    static void resumeAfterReset(com.shiroha.mmdskin.model.runtime.ModelInstance model, EntityAnimState state) {
        if (!state.playVpdPose) return;
        state.playVpdPose = false;
        state.playCustomAnim = false;
        state.invalidateStateLayers();
        model.setLayerSpeed(0, 1.0f);
    }

    public static void leave(ManagedModel model) {
        if (model.entityState().playVpdPose) {
            ExpressionApplicationService.apply(model.modelInstance().getModelHandle(), ExpressionSelection.resetPose(), model.modelName());
            model.entityState().playVpdPose = false;
        }
    }
}
