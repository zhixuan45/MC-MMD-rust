package com.shiroha.mmdskin.ui.paperdoll;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.config.ModelConfigManager;
import com.shiroha.mmdskin.config.PaperDollDisplayMode;
import com.shiroha.mmdskin.config.PaperDollRotationMode;
import com.shiroha.mmdskin.config.UIConstants;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.model.runtime.ModelRequestKey;
import com.shiroha.mmdskin.player.animation.AnimationStateManager;
import com.shiroha.mmdskin.player.render.ItemRenderHelper;
import com.shiroha.mmdskin.player.render.PaperDollRenderScope;
import com.shiroha.mmdskin.player.render.PlayerRenderHelper;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.render.scene.RenderScene;
import com.shiroha.mmdskin.ui.config.ModelSelectorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * 文件职责：管理并执行屏幕角落纸娃娃（Paperdoll）的 3D 模型渲染。
 */
public final class PaperDollRenderer {

    /** 动作触发后的延迟隐藏缓冲时间（毫秒），避免动作停顿瞬间频繁闪烁 */
    private static final long ACTION_HOLD_TIME_MS = 1200L;
    private static long lastActionTimeMs = 0L;

    private PaperDollRenderer() {
    }

    /**
     * 在游戏 HUD（In-Game GUI）渲染阶段绘制纸娃娃。
     *
     * @param guiGraphics 原生绘制上下文
     * @param tickDelta   帧插值时间
     */
    public static void renderHud(GuiGraphics guiGraphics, float tickDelta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        // 如果当前打开了其他界面，由 Screen 阶段接管渲染，避免重复绘制
        if (mc.screen != null) {
            return;
        }

        if (!ConfigManager.isPaperDollEnabled()) {
            return;
        }

        if (!shouldDisplay(mc.player)) {
            return;
        }

        renderPaperDoll(guiGraphics, mc.player, tickDelta);
    }

    /**
     * 在 Screen 界面（如游戏暂停菜单）渲染阶段绘制纸娃娃。
     *
     * @param guiGraphics 原生绘制上下文
     * @param screen      当前屏幕实例
     * @param tickDelta   帧插值时间
     */
    public static void renderInScreen(GuiGraphics guiGraphics, Screen screen, float tickDelta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        if (!ConfigManager.isPaperDollEnabled() || !ConfigManager.isPaperDollShowInScreens()) {
            return;
        }

        // 仅在暂停菜单等非全屏交互界面渲染，背包界面已有原生预览故排除
        if (screen instanceof InventoryScreen) {
            return;
        }

        if (screen instanceof PauseScreen || screen.getClass().getSimpleName().contains("Pause")
                || screen.getClass().getSimpleName().contains("GameMenu")) {
            renderPaperDoll(guiGraphics, mc.player, tickDelta);
        }
    }

    /**
     * 判断当前玩家状态是否满足纸娃娃显示条件。
     */
    private static boolean shouldDisplay(LocalPlayer player) {
        if (ConfigManager.getPaperDollDisplayMode() == PaperDollDisplayMode.ALWAYS) {
            return true;
        }

        long now = System.currentTimeMillis();
        boolean hasAction = player.isSprinting()
                || player.isCrouching()
                || player.isFallFlying()
                || player.isSwimming()
                || player.isVisuallySwimming()
                || player.isPassenger()
                || player.isUsingItem()
                || player.hurtTime > 0;

        if (hasAction) {
            lastActionTimeMs = now;
            return true;
        }

        return (now - lastActionTimeMs) < ACTION_HOLD_TIME_MS;
    }

    /**
     * 核心渲染管线：构建变换矩阵与渲染状态并分发绘制。
     */
    private static void renderPaperDoll(GuiGraphics guiGraphics, LocalPlayer player, float tickDelta) {
        String modelName = ModelSelectorConfig.getInstance().getPlayerModel(player.getName().getString());
        if (modelName == null || modelName.isEmpty() || UIConstants.DEFAULT_MODEL_NAME.equals(modelName)) {
            // 如果未配置 MMD 模型，调用原版实体预览回退绘制
            renderVanillaFallback(guiGraphics, player);
            return;
        }

        ModelRequestKey requestKey = ModelRequestKey.player(player, modelName);
        ManagedModel modelData = ClientRenderRuntime.get().modelRepository().acquire(requestKey);
        if (modelData == null || modelData.modelInstance() == null) {
            return;
        }

        int screenWidth = guiGraphics.guiWidth();
        int screenHeight = guiGraphics.guiHeight();
        float posX;
        float posY;
        float margin = 20.0f;
        int offsetX = ConfigManager.getPaperDollOffsetX();
        int offsetY = ConfigManager.getPaperDollOffsetY();

        switch (ConfigManager.getPaperDollPosition()) {
            case TOP_RIGHT -> {
                posX = screenWidth - margin - offsetX;
                posY = margin + 55.0f + offsetY;
            }
            case BOTTOM_LEFT -> {
                posX = margin + offsetX;
                posY = screenHeight - margin - 20.0f - offsetY;
            }
            case BOTTOM_RIGHT -> {
                posX = screenWidth - margin - offsetX;
                posY = screenHeight - margin - 20.0f - offsetY;
            }
            case TOP_LEFT -> {
                posX = margin + offsetX;
                posY = margin + 55.0f + offsetY;
            }
            default -> {
                posX = margin + offsetX;
                posY = margin + 55.0f + offsetY;
            }
        }

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(posX, posY, 50.0f);

        float scale = ConfigManager.getPaperDollScale();
        poseStack.scale(scale, scale, -scale);

        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        if (ConfigManager.getPaperDollRotationMode() == PaperDollRotationMode.FIXED) {
            // 经典微侧身 20 度
            rotation.mul(new Quaternionf().rotateY(-20.0f * ((float) Math.PI / 180F)));
            rotation.mul(new Quaternionf().rotateX(5.0f * ((float) Math.PI / 180F)));
        } else {
            // 跟随玩家身体与视角转动
            float bodyYaw = Mth.rotLerp(tickDelta, player.yBodyRotO, player.yBodyRot);
            float pitch = -player.getXRot();
            rotation.mul(new Quaternionf().rotateY(-bodyYaw * ((float) Math.PI / 180F)));
            rotation.mul(new Quaternionf().rotateX(pitch * ((float) Math.PI / 180F)));
        }
        poseStack.mulPose(rotation);

        PaperDollRenderScope.enter();
        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.setShader(GameRenderer::getRendertypeEntityTranslucentShader);

            int packedLight = 0xF000F0;
            float[] size = PlayerRenderHelper.getModelSize(modelData);

            AnimationStateManager.updateAnimationState(player, modelData);

            modelData.modelInstance().render(
                    player,
                    0.0f,
                    0.0f,
                    new Vector3f(0.0f),
                    tickDelta,
                    poseStack,
                    packedLight,
                    RenderScene.PAPERDOLL
            );

            float heldItemScale = ModelConfigManager.getConfig(modelName).heldItemScale;
            ItemRenderHelper.renderItems(
                    player,
                    modelData,
                    poseStack,
                    guiGraphics.bufferSource(),
                    packedLight,
                    heldItemScale,
                    tickDelta,
                    size[0]
            );

            guiGraphics.flush();
        } finally {
            PaperDollRenderScope.exit();
            RenderSystem.disableDepthTest();
            poseStack.popPose();
        }
    }

    /**
     * 当玩家未配置 MMD 模型时，回退到原版 3D 实体渲染。
     */
    private static void renderVanillaFallback(GuiGraphics guiGraphics, LocalPlayer player) {
        int screenWidth = guiGraphics.guiWidth();
        int screenHeight = guiGraphics.guiHeight();
        int posX;
        int posY;
        int margin = 20;
        int offsetX = ConfigManager.getPaperDollOffsetX();
        int offsetY = ConfigManager.getPaperDollOffsetY();

        switch (ConfigManager.getPaperDollPosition()) {
            case TOP_RIGHT -> {
                posX = screenWidth - margin - offsetX;
                posY = margin + 55 + offsetY;
            }
            case BOTTOM_LEFT -> {
                posX = margin + offsetX;
                posY = screenHeight - margin - 20 - offsetY;
            }
            case BOTTOM_RIGHT -> {
                posX = screenWidth - margin - offsetX;
                posY = screenHeight - margin - 20 - offsetY;
            }
            case TOP_LEFT -> {
                posX = margin + offsetX;
                posY = margin + 55 + offsetY;
            }
            default -> {
                posX = margin + offsetX;
                posY = margin + 55 + offsetY;
            }
        }

        Quaternionf bodyPose = new Quaternionf().rotateZ((float) Math.PI);
        if (ConfigManager.getPaperDollRotationMode() == PaperDollRotationMode.FIXED) {
            bodyPose.mul(new Quaternionf().rotateY(-20.0f * ((float) Math.PI / 180F)));
        } else {
            bodyPose.mul(new Quaternionf().rotateY(-player.yBodyRot * ((float) Math.PI / 180F)));
        }

        Quaternionf cameraOrientation = new Quaternionf().rotateX(-player.getXRot() * ((float) Math.PI / 180F));

        InventoryScreen.renderEntityInInventory(
                guiGraphics,
                posX,
                posY,
                (int) ConfigManager.getPaperDollScale(),
                bodyPose,
                cameraOrientation,
                player
        );
    }
}
