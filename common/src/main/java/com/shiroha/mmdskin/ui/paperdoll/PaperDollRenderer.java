package com.shiroha.mmdskin.ui.paperdoll;

import com.mojang.blaze3d.platform.Lighting;
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
import com.shiroha.mmdskin.render.backend.BaseModelInstance;
import com.shiroha.mmdskin.render.pipeline.RenderPerformanceProfiler;
import com.shiroha.mmdskin.ui.config.ModelSelectorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * 文件职责：管理并执行屏幕角落纸娃娃（Paperdoll）的 3D 模型渲染 (Minecraft 1.21.1 适配)。
 */
public final class PaperDollRenderer {

    /** 动作触发后的延迟隐藏缓冲时间（毫秒），避免动作停顿瞬间频繁闪烁 */
    private static final long ACTION_HOLD_TIME_MS = 1200L;
    private static long lastActionTimeMs = 0L;
    private static final PaperDollFrameCache FRAME_CACHE = new PaperDollFrameCache();

    private record CacheKey(Object player, Object model, Object screen, int guiWidth, int guiHeight,
                            double guiScale, Object position, int offsetX, int offsetY, float scale,
                            Object rotation, boolean toon, boolean outline, float heldItemScale) { }

    public static void releaseCache() { FRAME_CACHE.close(); }

    /** Platform tick also handles leaving a world, where the HUD callback may stop entirely. */
    public static void tickCache() {
        if (Minecraft.getInstance().player == null || !ConfigManager.isPaperDollEnabled()
                || ConfigManager.getPaperDollRefreshMode().framesPerSecond == 0) releaseCache();
    }

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
            FRAME_CACHE.invalidate();
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
            FRAME_CACHE.invalidate();
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
        String modelName = ModelSelectorConfig.getInstance().getPlayerModelByUuidOrName(
                player.getUUID(), player.getName().getString());
        if (modelName == null || modelName.isEmpty() || UIConstants.DEFAULT_MODEL_NAME.equals(modelName)) {
            FRAME_CACHE.invalidate();
            // 如果未配置 MMD 模型，调用原版实体预览回退绘制
            renderVanillaFallback(guiGraphics, player);
            return;
        }

        var repository = ClientRenderRuntime.get().modelRepository();
        ManagedModel source = repository.peek(ModelRequestKey.player(player, modelName));
        BaseModelInstance sharedPose = source != null && source.modelInstance() instanceof BaseModelInstance instance
                && instance.canReuseForPaperDoll(player) ? instance : null;
        ManagedModel modelData = sharedPose != null ? source
                : repository.acquire(ModelRequestKey.paperDoll(player, modelName));
        if (modelData == null || modelData.modelInstance() == null) {
            FRAME_CACHE.invalidate();
            // 模型未加载完成前回退到原版绘制
            renderVanillaFallback(guiGraphics, player);
            return;
        }

        int fps = ConfigManager.getPaperDollRefreshMode().framesPerSecond;
        if (fps > 0 && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()
                && !com.shiroha.mmdskin.compat.iris.IrisCompat.isIrisShaderActive()) {
            // Finish previously queued HUD work before changing the framebuffer.
            guiGraphics.flush();
            Minecraft mc = Minecraft.getInstance();
            var key = new CacheKey(player, modelData.modelInstance(), mc.screen,
                    guiGraphics.guiWidth(), guiGraphics.guiHeight(), mc.getWindow().getGuiScale(),
                    ConfigManager.getPaperDollPosition(), ConfigManager.getPaperDollOffsetX(),
                    ConfigManager.getPaperDollOffsetY(), ConfigManager.getPaperDollScale(),
                    ConfigManager.getPaperDollRotationMode(), ConfigManager.isToonRenderingEnabled(),
                    ConfigManager.isToonOutlineEnabled(), ModelConfigManager.getConfig(modelName).heldItemScale);
            if (FRAME_CACHE.render(key, fps, System.nanoTime(),
                    () -> renderPrepared(guiGraphics, player, tickDelta, modelName, modelData, sharedPose))) return;
        } else {
            FRAME_CACHE.close();
        }
        renderPrepared(guiGraphics, player, tickDelta, modelName, modelData, sharedPose);
    }

    private static void renderPrepared(GuiGraphics guiGraphics, LocalPlayer player, float tickDelta,
                                       String modelName, ManagedModel modelData, BaseModelInstance sharedPose) {
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

        // 1.21.1 独立维护 PoseStack，由 composeModelViewMatrix 组合进入 Shader
        PoseStack modelViewStack = new PoseStack();
        modelViewStack.pushPose();
        modelViewStack.translate(posX, posY, 50.0f);

        float scale = ConfigManager.getPaperDollScale();
        modelViewStack.scale(scale, scale, -scale);

        // 仅设置整体显示朝向；头眼、动作和物理沿用世界姿态。
        modelViewStack.mulPose(PaperDollDisplayTransform.rotation(ConfigManager.getPaperDollRotationMode(),
                Mth.rotLerp(tickDelta, player.yBodyRotO, player.yBodyRot), player.getXRot(), new Quaternionf()));

        PaperDollRenderScope.enter();
        Minecraft mc = Minecraft.getInstance();
        boolean lightLayerTurnedOn = false;
        try {
            Lighting.setupForEntityInInventory();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.setShader(GameRenderer::getRendertypeEntityTranslucentShader);

            if (mc.gameRenderer != null && mc.gameRenderer.lightTexture() != null) {
                mc.gameRenderer.lightTexture().turnOnLightLayer();
                lightLayerTurnedOn = true;
            }

            int packedLight = 0xF000F0;
            float[] size = PlayerRenderHelper.getModelSize(modelData);

            if (sharedPose != null) {
                sharedPose.renderPaperDollPose(player, modelViewStack, packedLight);
            } else {
                AnimationStateManager.updateAnimationState(player, modelData);
                modelData.modelInstance().render(
                    player,
                    0.0f,
                    0.0f,
                    new Vector3f(0.0f),
                    tickDelta,
                    modelViewStack,
                    packedLight,
                    RenderScene.PAPERDOLL
                );
            }

            float heldItemScale = ModelConfigManager.getConfig(modelName).heldItemScale;
            long itemTimer = RenderPerformanceProfiler.get().startTimer();
            ItemRenderHelper.renderItems(
                    player,
                    modelData,
                    modelViewStack,
                    guiGraphics.bufferSource(),
                    packedLight,
                    heldItemScale,
                    tickDelta,
                    size[0]
            );
            RenderPerformanceProfiler.get().endTimer("paperDoll.items", itemTimer);
            long flushTimer = RenderPerformanceProfiler.get().startTimer();
            guiGraphics.flush();
            RenderPerformanceProfiler.get().endTimer("paperDoll.flush", flushTimer);
        } finally {
            if (lightLayerTurnedOn && mc.gameRenderer != null && mc.gameRenderer.lightTexture() != null) {
                mc.gameRenderer.lightTexture().turnOffLightLayer();
            }
            Lighting.setupFor3DItems();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            PaperDollRenderScope.exit();
            RenderSystem.disableDepthTest();
            modelViewStack.popPose();
        }
    }

    /**
     * 当玩家未配置 MMD 模型或模型加载中时，回退到原版 3D 实体渲染 (1.21.1 签名适配)。
     */
    private static void renderVanillaFallback(GuiGraphics guiGraphics, LocalPlayer player) {
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

        Quaternionf bodyPose = new Quaternionf().rotateZ((float) Math.PI);
        if (ConfigManager.getPaperDollRotationMode() == PaperDollRotationMode.FIXED) {
            // 面对镜头，经典微侧身 20 度
            bodyPose.mul(new Quaternionf().rotateY((180.0f + 20.0f) * ((float) Math.PI / 180F)));
            bodyPose.mul(new Quaternionf().rotateX(5.0f * ((float) Math.PI / 180F)));
        } else {
            // 跟随玩家身体与视角转动（面对镜头为基准）
            float bodyYaw = Mth.rotLerp(1.0f, player.yBodyRotO, player.yBodyRot);
            bodyPose.mul(new Quaternionf().rotateY((180.0f - bodyYaw) * ((float) Math.PI / 180F)));
        }

        Quaternionf cameraOrientation = new Quaternionf().rotateX(-player.getXRot() * ((float) Math.PI / 180F));

        PaperDollRenderScope.enter();
        try {
            InventoryScreen.renderEntityInInventory(
                    guiGraphics,
                    posX,
                    posY,
                    ConfigManager.getPaperDollScale(),
                    new Vector3f(),
                    bodyPose,
                    cameraOrientation,
                    player
            );
        } finally {
            PaperDollRenderScope.exit();
        }
    }
}
