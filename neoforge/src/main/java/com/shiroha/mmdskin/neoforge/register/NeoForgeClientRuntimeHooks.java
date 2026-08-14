package com.shiroha.mmdskin.neoforge.register;

import com.shiroha.mmdskin.config.UIConstants;
import com.shiroha.mmdskin.debug.client.PerformanceHud;
import com.shiroha.mmdskin.neoforge.network.MmdSkinNetworkPack;
import com.shiroha.mmdskin.player.runtime.MmdSkinRendererPlayerHelper;
import com.shiroha.mmdskin.player.sync.PlayerModelSyncService;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.stage.client.StageClientRuntime;
import com.shiroha.mmdskin.stage.client.camera.MMDCameraController;
import com.shiroha.mmdskin.ui.QuickModelSwitcher;
import com.shiroha.mmdskin.ui.config.ModelSelectorConfig;
import com.shiroha.mmdskin.ui.network.NetworkOpCode;
import com.shiroha.mmdskin.ui.wheel.ConfigWheelScreen;
import com.shiroha.mmdskin.ui.wheel.MaidConfigWheelScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 承接 NeoForge 客户端运行时事件并执行业务钩子。
 */
@OnlyIn(Dist.CLIENT)
final class NeoForgeClientRuntimeHooks {
    private final KeyMapping keyConfigWheel;
    private final KeyMapping keyMaidConfigWheel;
    private final KeyMapping[] keyQuickModels;

    private boolean configWheelKeyWasDown;
    private boolean maidConfigWheelKeyWasDown;

    NeoForgeClientRuntimeHooks(KeyMapping keyConfigWheel, KeyMapping keyMaidConfigWheel, KeyMapping[] keyQuickModels) {
        this.keyConfigWheel = keyConfigWheel;
        this.keyMaidConfigWheel = keyMaidConfigWheel;
        this.keyQuickModels = keyQuickModels;
    }

    void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        ClientRenderRuntime.get().modelRepository().tick();
        StageClientRuntime.get().animSyncHelper().tickPending();

        if (minecraft.screen == null || minecraft.screen instanceof ConfigWheelScreen) {
            boolean keyDown = keyConfigWheel.isDown();
            if (keyDown && !configWheelKeyWasDown) {
                minecraft.setScreen(new ConfigWheelScreen(keyConfigWheel));
            }
            configWheelKeyWasDown = keyDown;
        } else {
            configWheelKeyWasDown = false;
        }

        if (minecraft.screen == null) {
            for (int i = 0; i < keyQuickModels.length; i++) {
                while (keyQuickModels[i].consumeClick()) {
                    QuickModelSwitcher.switchToSlot(i);
                }
            }
        }

        if (minecraft.screen == null || minecraft.screen instanceof MaidConfigWheelScreen) {
            boolean keyDown = keyMaidConfigWheel.isDown();
            if (keyDown && !maidConfigWheelKeyWasDown) {
                tryOpenMaidConfigWheel(minecraft);
            }
            maidConfigWheelKeyWasDown = keyDown;
        } else {
            maidConfigWheelKeyWasDown = false;
        }
    }

    void onPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        String selectedModel = ModelSelectorConfig.getInstance().getPlayerModel(minecraft.player.getName().getString());
        if (selectedModel != null && !selectedModel.isEmpty() && !selectedModel.equals(UIConstants.DEFAULT_MODEL_NAME)) {
            PlayerModelSyncService.broadcastLocalModelSelection(minecraft.player.getUUID(), selectedModel);
        }
        PacketDistributor.sendToServer(
                new MmdSkinNetworkPack(NetworkOpCode.REQUEST_ALL_MODELS, minecraft.player.getUUID(), ""));
    }

    void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MMDCameraController.getInstance().exitStageMode();
        PlayerModelSyncService.onDisconnect();
        MmdSkinRendererPlayerHelper.onDisconnect();
        StageClientRuntime.get().sessionService().onDisconnect();
    }

    void onPlayerDeath(LivingDeathEvent event) {
        exitStageModeIfLocalPlayer(event.getEntity().getUUID());
    }

    void onRenderGui(RenderGuiEvent.Post event) {
        PerformanceHud.render(event.getGuiGraphics());
    }

    void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        exitStageModeIfLocalPlayer(event.getEntity().getUUID());
    }

    private void exitStageModeIfLocalPlayer(UUID playerId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && playerId.equals(minecraft.player.getUUID())) {
            MMDCameraController controller = MMDCameraController.getInstance();
            if (controller.isInStageMode()) {
                controller.exitStageMode();
            }
        }
    }

    private void tryOpenMaidConfigWheel(Minecraft minecraft) {
        HitResult hitResult = minecraft.hitResult;
        if (hitResult == null || hitResult.getType() != HitResult.Type.ENTITY) {
            return;
        }

        Entity target = ((EntityHitResult) hitResult).getEntity();
        String className = target.getClass().getName();
        if (className.contains("EntityMaid") || className.contains("touhoulittlemaid")) {
            String maidName = target.getName().getString();
            minecraft.setScreen(new MaidConfigWheelScreen(target.getUUID(), target.getId(), maidName, keyMaidConfigWheel));
        }
    }
}
