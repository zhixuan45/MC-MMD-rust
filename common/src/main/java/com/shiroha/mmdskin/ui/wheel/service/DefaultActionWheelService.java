/* 职责：驱动玩家动作轮盘的动作执行与同步。 */
package com.shiroha.mmdskin.ui.wheel.service;

import com.shiroha.mmdskin.player.runtime.MmdSkinRendererPlayerHelper;
import com.shiroha.mmdskin.player.sync.PlayerActionSyncService;
import com.shiroha.mmdskin.ui.config.ActionWheelConfig;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class DefaultActionWheelService implements ActionWheelService {
    private final Supplier<List<ActionWheelConfig.ActionEntry>> actionEntriesSupplier;
    private final ActionRuntimePort runtimePort;
    private final ActionSyncPort syncPort;

    public DefaultActionWheelService() {
        this(() -> ActionWheelConfig.getInstance().getDisplayedActions(), new MinecraftActionRuntimePort(),
                PlayerActionSyncService.getInstance());
    }

    DefaultActionWheelService(Supplier<List<ActionWheelConfig.ActionEntry>> actionEntriesSupplier,
                              ActionRuntimePort runtimePort,
                              ActionSyncPort syncPort) {
        this.actionEntriesSupplier = Objects.requireNonNull(actionEntriesSupplier, "actionEntriesSupplier");
        this.runtimePort = Objects.requireNonNull(runtimePort, "runtimePort");
        this.syncPort = Objects.requireNonNull(syncPort, "syncPort");
    }

    @Override
    public List<ActionOption> loadActions() {
        var actions = new java.util.ArrayList<ActionOption>();
        for (var entry : actionEntriesSupplier.get()) actions.add(new ActionOption(entry.name, entry.animId));
        if (actions.stream().anyMatch(action -> action.animId() != null
                && action.animId().startsWith(com.shiroha.mmdskin.asset.catalog.AnimationInfo.VPD_ACTION_PREFIX))) {
            actions.add(new ActionOption(net.minecraft.network.chat.Component.translatable("gui.mmdskin.reset_pose").getString(),
                    com.shiroha.mmdskin.asset.catalog.AnimationInfo.RESET_POSE_ACTION));
        }
        return List.copyOf(actions);
    }

    @Override
    public void selectAction(String animId) {
        if (animId == null || animId.isEmpty()) {
            return;
        }
        if (runtimePort.playLocalAction(animId)) {
            syncPort.syncAction(animId);
        }
    }

    interface ActionRuntimePort {
        boolean playLocalAction(String animId);
    }

    private static final class MinecraftActionRuntimePort implements ActionRuntimePort {
        @Override
        public boolean playLocalAction(String animId) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return false;
            }
            return MmdSkinRendererPlayerHelper.tryCustomAnim(mc.player, animId);
        }
    }
}
