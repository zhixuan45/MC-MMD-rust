package com.shiroha.mmdskin.stage.client.camera;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.shiroha.mmdskin.bridge.runtime.NativeAnimationPort;
import com.shiroha.mmdskin.bridge.runtime.NativeModelPort;
import com.shiroha.mmdskin.bridge.runtime.NativeRuntimePort;
import com.shiroha.mmdskin.bridge.runtime.NativeScenePort;
import com.shiroha.mmdskin.stage.client.StageClientRuntime;
import net.minecraft.client.Camera;
import net.minecraftforge.client.event.ViewportEvent;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Executes the production Forge event callback with the real 1.20.1 camera and view operations. */
public final class StageCameraEventProbe {
    public static void main(String[] args) throws Exception {
        Object ports = Proxy.newProxyInstance(StageCameraEventProbe.class.getClassLoader(),
                new Class<?>[]{NativeRuntimePort.class, NativeModelPort.class,
                        NativeScenePort.class, NativeAnimationPort.class},
                (proxy, method, values) -> {
                    throw new AssertionError("Camera angle dispatch must not call native: " + method);
                });
        StageClientRuntime.initialize((NativeRuntimePort) ports, (NativeAnimationPort) ports);
        MMDCameraController controller = StageClientRuntime.get().cameraController();
        Method callback = Class.forName("com.shiroha.mmdskin.forge.MmdSkinForgeClient")
                .getDeclaredMethod("onComputeCameraAngles", ViewportEvent.ComputeCameraAngles.class);
        callback.setAccessible(true);
        Field stateField = MMDCameraController.class.getDeclaredField("stateMachine");
        stateField.setAccessible(true);
        StageCameraSessionStateMachine states = (StageCameraSessionStateMachine) stateField.get(controller);

        var inactive = new ViewportEvent.ComputeCameraAngles(null, new Camera(), 0.5, 12, 23, 34);
        callback.invoke(null, inactive);
        check(inactive.getYaw() == 12 && inactive.getPitch() == 23 && inactive.getRoll() == 34,
                "Inactive stage changed another camera");

        int checked = 0;
        for (var state : StageCameraSessionStateMachine.StageState.values()) {
            if (state == StageCameraSessionStateMachine.StageState.INACTIVE) continue;
            states.set(state);
            for (float yaw : new float[]{-170, 0, 37, 180, 370}) {
                for (float pitch : new float[]{-90, -22, 0, 89.999f, 90}) {
                    for (float roll : new float[]{-180, -90, -1, 0, 1, 90, 179.9f}) {
                        set(controller, "cameraYaw", yaw);
                        set(controller, "cameraPitch", pitch);
                        set(controller, "cameraRoll", roll);
                        TestCamera camera = new TestCamera();
                        camera.setStageRotation(yaw, pitch, roll);
                        var event = new ViewportEvent.ComputeCameraAngles(
                                null, camera, 0.5, 10, 20, 30);
                        callback.invoke(null, event);
                        check(event.getYaw() == yaw && event.getPitch() == pitch && event.getRoll() == roll,
                                "Stage angles lost during Forge event");

                        // These are the operations in Forge's patched GameRenderer.renderLevel.
                        camera.setAnglesInternal(event.getYaw(), event.getPitch());
                        PoseStack view = new PoseStack();
                        view.mulPose(Axis.ZP.rotationDegrees(event.getRoll()));
                        view.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
                        view.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0f));
                        var world = view.last().pose().invert();
                        checkVector(world.transformDirection(new Vector3f(0, 0, -1)), camera.getLookVector());
                        checkVector(world.transformDirection(new Vector3f(0, 1, 0)), camera.getUpVector());
                        checkVector(world.transformDirection(new Vector3f(-1, 0, 0)), camera.getLeftVector());
                        checked++;
                    }
                }
            }
        }
        System.out.println("PASS Forge camera event: inactive passthrough + " + checked
                + " active state/three-axis poses; no native calls");
    }

    private static void set(Object target, String name, float value) throws Exception {
        Field field = MMDCameraController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setFloat(target, value);
    }

    private static void checkVector(Vector3f expected, Vector3f actual) {
        check(expected.equals(actual, 1.0E-4f), "Camera/view mismatch: " + expected + " vs " + actual);
    }

    private static void check(boolean success, String reason) {
        if (!success) throw new AssertionError(reason);
    }

    private static final class TestCamera extends Camera {
        void setStageRotation(float yaw, float pitch, float roll) {
            setRotation(yaw, pitch);
            StageCameraOrientation.applyRoll(rotation(), getLookVector(), getUpVector(), getLeftVector(), roll);
        }
    }
}
