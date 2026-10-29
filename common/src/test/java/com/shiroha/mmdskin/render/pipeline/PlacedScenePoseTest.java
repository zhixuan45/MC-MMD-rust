package com.shiroha.mmdskin.render.pipeline;

import com.shiroha.mmdskin.bridge.runtime.NativeScenePort;
import com.shiroha.mmdskin.render.scene.RenderScene;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class PlacedScenePoseTest {
    @Test
    void playerStateCannotOverwritePlacedScenePhysicsRootOrPose() {
        NativeScenePort port = (NativeScenePort) Proxy.newProxyInstance(NativeScenePort.class.getClassLoader(),
                new Class<?>[]{NativeScenePort.class}, (proxy, method, args) -> {
                    throw new AssertionError("Scene placement must be preserved: " + method.getName());
                });
        for (boolean stagePlaying : new boolean[]{false, true}) {
            for (boolean vrActive : new boolean[]{false, true}) {
                LivingEntityModelStateHelper.syncModelState(port, 42L, null, 120.0f, 0.5f,
                        RenderScene.PLACED_SCENE, "stage", stagePlaying, vrActive);
            }
        }
        assertTrue(RenderScene.PLACED_SCENE.isWorldScene());
        assertTrue(RenderScene.PLACED_SCENE.allowsPhysics());
        assertFalse(RenderScene.WORLD.isPlacedScene());
        assertFalse(RenderScene.FIRST_PERSON.isPlacedScene());
    }
}
