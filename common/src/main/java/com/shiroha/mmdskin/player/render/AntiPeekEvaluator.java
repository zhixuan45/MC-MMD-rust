package com.shiroha.mmdskin.player.render;

import com.shiroha.mmdskin.config.RuntimeConfigPort;
import com.shiroha.mmdskin.config.RuntimeConfigPortHolder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 直播防走光与特殊状态（如游泳）角色隐藏/虚化评估器。
 */
public final class AntiPeekEvaluator {

    private AntiPeekEvaluator() {}

    /**
     * 评估结果数据结构。
     *
     * @param fullyHidden 是否完全隐藏（不进行任何渲染）
     * @param alpha       当前透明度调制因子（0.0 ~ 1.0）
     */
    public record Result(boolean fullyHidden, float alpha) {
        public static final Result NORMAL = new Result(false, 1.0f);
        public static final Result HIDDEN = new Result(true, 0.0f);
    }

    /**
     * 评估本地玩家当前帧的可见性与透明度。
     *
     * @param player          当前渲染的客户端玩家
     * @param isLocalPlayer   是否为本地玩家自身
     * @param firstPersonView 是否处于第一人称模式
     * @param inventoryRender 是否为背包/物品栏渲染
     * @param shadowPass      是否为光影阴影渲染通道
     * @return 评估结果
     */
    public static Result evaluate(AbstractClientPlayer player,
                                 boolean isLocalPlayer,
                                 boolean firstPersonView,
                                 boolean inventoryRender,
                                 boolean shadowPass) {
        if (!isLocalPlayer || player == null || inventoryRender || shadowPass) {
            return Result.NORMAL;
        }

        boolean swimming = player.isSwimming() || player.isVisuallySwimming();
        boolean passenger = player.isPassenger();

        // 规则 1：只要处于第一人称且在游泳或乘坐载具状态，必定隐藏第一人称角色模型
        if (firstPersonView && (swimming || passenger)) {
            return Result.HIDDEN;
        }

        RuntimeConfigPort config = RuntimeConfigPortHolder.get();

        // 规则 2：开启防走光模式时，若处于游泳状态，直接隐藏以防止水下异常穿模与走光
        if (config.isAntiPeekModeEnabled() && swimming) {
            return Result.HIDDEN;
        }

        // 规则 3：防走光模式下的第三人称低机位仰角判定
        if (!config.isAntiPeekModeEnabled() || firstPersonView) {
            return Result.NORMAL;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameRenderer == null) {
            return Result.NORMAL;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera == null) {
            return Result.NORMAL;
        }

        float thresholdAngle = config.getAntiPeekThresholdAngle();
        float hideAngle = config.getAntiPeekHideAngle();

        // 确保阈值逻辑正确（开始虚化角度必须大于等于完全隐藏角度）
        if (thresholdAngle < hideAngle) {
            float temp = thresholdAngle;
            thresholdAngle = hideAngle;
            hideAngle = temp;
        }

        // Minecraft 中相机 Pitch：向上看为负数（-90度表示垂直看天），水平为0度，向下看为正数
        float cameraPitch = camera.getXRot();

        // 仅当视角为向上仰视（Pitch < 0）时可能产生从下往上看裙底的走光风险
        if (cameraPitch >= 0.0f) {
            return Result.NORMAL;
        }

        // 相机视线与垂直向上向量 (0, 1, 0) 的夹角（度）
        float angleToUp = 90.0f + cameraPitch;

        // 校验相机垂直高度：确保相机位于角色下半身高度（或者接近角色底部）
        Vec3 cameraPos = camera.getPosition();
        double playerBaseY = player.getY();
        double playerWaistY = playerBaseY + player.getEyeHeight() * 0.75;
        if (cameraPos.y > playerWaistY + 0.3) {
            return Result.NORMAL;
        }

        // 夹角小于完全隐藏阈值：彻底隐藏
        if (angleToUp <= hideAngle) {
            return Result.HIDDEN;
        }

        // 夹角大于虚化起始阈值：正常显示
        if (angleToUp >= thresholdAngle) {
            return Result.NORMAL;
        }

        // 处于过渡区间：使用 smoothstep 平滑插值计算透明度
        float progress = (angleToUp - hideAngle) / Math.max(0.001f, thresholdAngle - hideAngle);
        progress = Math.max(0.0f, Math.min(1.0f, progress));
        float smoothAlpha = progress * progress * (3.0f - 2.0f * progress);

        if (smoothAlpha <= 0.001f) {
            return Result.HIDDEN;
        }

        return new Result(false, smoothAlpha);
    }
}
