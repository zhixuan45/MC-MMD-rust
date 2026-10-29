package com.shiroha.mmdskin.stage.client;

import com.shiroha.mmdskin.stage.client.camera.port.StagePlayerSyncPort;
import com.shiroha.mmdskin.stage.client.sync.StageAnimSyncHelper;
import net.minecraft.client.Minecraft;

import java.util.Objects;

/** 文件职责：把相机层的舞台逐帧同步请求转接到玩家舞台同步实现。 */
public final class DefaultStagePlayerSyncPort implements StagePlayerSyncPort {
    private final StageAnimSyncHelper animSyncHelper;

    public DefaultStagePlayerSyncPort(StageAnimSyncHelper animSyncHelper) {
        this.animSyncHelper = Objects.requireNonNull(animSyncHelper, "animSyncHelper");
    }

    @Override
    public void syncStageFrame(float frame) {
        animSyncHelper.syncRemoteFrame(frame);
        animSyncHelper.syncLocalFrame(frame);
    }

    @Override
    public void stopLocalStageAnimation() {
        Minecraft minecraft = StageClientContext.minecraft();
        if (minecraft.player != null) {
            animSyncHelper.end(minecraft.player);
        }
    }

    @Override public void beginLocalProp(com.shiroha.mmdskin.stage.domain.model.StagePropAssignment assignment) {
        Minecraft minecraft=StageClientContext.minecraft();
        if (minecraft.player!=null) animSyncHelper.propAnimations().begin(minecraft.player.getUUID(),assignment);
    }
    @Override public void stopLocalProp() {
        Minecraft minecraft=StageClientContext.minecraft();
        if (minecraft.player!=null) animSyncHelper.propAnimations().stop(minecraft.player.getUUID());
    }
}
