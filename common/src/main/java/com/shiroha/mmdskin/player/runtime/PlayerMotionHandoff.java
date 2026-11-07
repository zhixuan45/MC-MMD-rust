package com.shiroha.mmdskin.player.runtime;

import com.shiroha.mmdskin.model.runtime.ModelInstance;

/** Return layer zero's clock to ordinary animation before replacing a stage clip. */
public final class PlayerMotionHandoff {
    private PlayerMotionHandoff() { }

    public static void leaveStage(ModelInstance model, EntityAnimState state) {
        leaveStage(model,state,() -> {});
    }

    public static void leaveStage(ModelInstance model, EntityAnimState state,Runnable releaseStage) {
        boolean wasStage=state.playStageAnim;
        state.playStageAnim=false;
        if(wasStage) releaseStage.run();
        model.setLayerSpeed(0,1.0f);
    }
}
