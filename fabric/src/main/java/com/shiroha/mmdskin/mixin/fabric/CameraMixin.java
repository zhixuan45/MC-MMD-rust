package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.config.RuntimeConfigPort;
import com.shiroha.mmdskin.config.RuntimeConfigPortHolder;
import com.shiroha.mmdskin.fabric.YsmCompat;
import com.shiroha.mmdskin.stage.client.camera.MMDCameraController;
import com.shiroha.mmdskin.stage.client.camera.StageCameraOrientation;
import com.shiroha.mmdskin.player.runtime.FirstPersonManager;
import com.shiroha.mmdskin.player.runtime.FrontViewCameraManager;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 相机 Mixin，用于接管舞台模式与第一人称 MMD 相机位置。 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow public abstract Quaternionf rotation();

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    public abstract Vec3 getPosition();

    // 在第三人称距离和墙体裁剪前校正起点，只改变前视镜头高度。
    @Inject(method = "setup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", ordinal = 0, shift = At.Shift.AFTER))
    private void mmdskin$alignFrontViewPivot(BlockGetter level, Entity entity, boolean detached,
                                            boolean mirrored, float partialTick, CallbackInfo ci) {
        if (!detached || !mirrored || MMDCameraController.getInstance().isActive()) return;
        if (entity instanceof LivingEntity living && YsmCompat.isYsmModelActive(living)
                && !YsmCompat.isDisableSelfModel()) return;
        Double height = FrontViewCameraManager.getPivotHeight(entity);
        if (height != null) {
            Vec3 pivot = getPosition();
            setPosition(pivot.x, height, pivot.z);
        }
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void onSetup(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {

        MMDCameraController controller = MMDCameraController.getInstance();
        if (controller.isActive()) {
            controller.checkEscapeKey();
            if (controller.isActive()) {
                controller.updateCamera();
                if (controller.isActive()) {
                    this.setPosition(controller.getCameraX(), controller.getCameraY(), controller.getCameraZ());
                    this.setRotation(controller.getCameraYaw(), controller.getCameraPitch());
                    StageCameraOrientation.applyRoll(rotation(), forwards, up, left, controller.getCameraRoll());
                }
            }
        } else {
            boolean vrEyeCameraActive = FirstPersonManager.isVrEyeCameraActive();
            boolean eyeCameraActive = FirstPersonManager.isEyeCameraActive();
            boolean eyeAnchorReady = vrEyeCameraActive || FirstPersonManager.isEyeBoneValid();
            if (eyeCameraActive && eyeAnchorReady && !detached) {

                if (entity instanceof LivingEntity living) {
                    boolean ysmActive = YsmCompat.isYsmModelActive(living);
                    boolean ysmDisableSelf = YsmCompat.isDisableSelfModel();
                    if (ysmActive && !ysmDisableSelf) {
                        return;
                    }
                }

                if (vrEyeCameraActive) {
                    Vec3 vrCameraPos = FirstPersonManager.getVrCameraPosition(entity, partialTick);
                    FirstPersonManager.setLastCameraPos(vrCameraPos);
                    this.setPosition(vrCameraPos.x, vrCameraPos.y, vrCameraPos.z);
                    return;
                }

                Vec3 boneEyePos = FirstPersonManager.getRotatedEyePosition(entity, partialTick);
                float originalYaw = entity.getViewYRot(partialTick);
                float originalPitch = entity.getViewXRot(partialTick);
                float lookPitchRad = originalPitch * ((float) Math.PI / 180F);
                float lookYawRad = originalYaw * ((float) Math.PI / 180F);
                float cosLookPitch = Mth.cos(lookPitchRad);
                float sinLookPitch = Mth.sin(lookPitchRad);
                float cosLookYaw = Mth.cos(lookYawRad);
                float sinLookYaw = Mth.sin(lookYawRad);
                RuntimeConfigPort runtimeConfig = RuntimeConfigPortHolder.get();

                double forwardOffset = runtimeConfig.getFirstPersonCameraForwardOffset();
                double verticalOffset = runtimeConfig.getFirstPersonCameraVerticalOffset();

                double targetX = boneEyePos.x + (double) (sinLookYaw * cosLookPitch * (float) (-forwardOffset));
                double targetY = boneEyePos.y + (double) (sinLookPitch * (float) (-forwardOffset)) + verticalOffset;
                double targetZ = boneEyePos.z + (double) (cosLookYaw * cosLookPitch * (float) forwardOffset);

                Vec3 finalPos = new Vec3(targetX, targetY, targetZ);
                FirstPersonManager.setLastCameraPos(finalPos);

                this.setPosition(finalPos.x, finalPos.y, finalPos.z);
                this.setRotation(originalYaw, originalPitch);
            }
        }
    }
}
