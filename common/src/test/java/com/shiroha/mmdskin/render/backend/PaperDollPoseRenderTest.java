package com.shiroha.mmdskin.render.backend;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.bridge.runtime.NativeRenderBackendPort;
import com.shiroha.mmdskin.render.scene.RenderScene;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class PaperDollPoseRenderTest {
    @Test void sharedDrawPreservesWorldPoseAndUsesOnlyTheProvidedDisplayTransform() {
        var model = new DrawOnlyModel();
        var stack = new PoseStack();
        stack.translate(20, 75, 50);
        stack.scale(30, 30, -30);
        Matrix4f display = new Matrix4f(stack.last().pose());
        model.renderPaperDollPose(null, stack, 0xF000F0);
        model.renderPaperDollPose(null, stack, 0xF000F0);
        assertEquals(2, model.draws);
        assertEquals(0, model.getNativeUpdateRevision());
        assertEquals(1, model.getGlobalAlpha());
        assertTrue(display.equals(stack.last().pose(), 0));
    }

    private static final class DrawOnlyModel extends BaseModelInstance {
        private int draws;

        DrawOnlyModel() {
            model = 1;
            nativeRenderBackendPort = (NativeRenderBackendPort) Proxy.newProxyInstance(
                    NativeRenderBackendPort.class.getClassLoader(),
                    new Class<?>[]{NativeRenderBackendPort.class}, (proxy, method, args) -> {
                        throw new AssertionError("Shared paperdoll must not synchronize or evaluate: " + method.getName());
                    });
        }

        @Override protected void doRenderModel(Entity entity, float yaw, float pitch, Vector3f translation,
                                                PoseStack stack, int light, RenderScene scene) {
            assertEquals(0, yaw);
            assertEquals(0, pitch);
            assertEquals(new Vector3f(), translation);
            assertSame(RenderScene.PAPERDOLL, scene);
            assertEquals(0xF000F0, light);
            draws++;
        }

        @Override protected void onUpdate(float delta) { fail("Paperdoll must not advance world physics"); }
        @Override public void dispose() { }
    }
}
