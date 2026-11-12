package com.shiroha.mmdskin.compat.tacz;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaczFirstPersonFrameSnapshotTest {
    private static final float EPSILON = 0.00001f;

    @BeforeAll
    static void bootStrapMinecraftRegistries() {
        try {
            SharedConstants.tryDetectVersion();
            Class<?> loadingModListClass = Class.forName("net.neoforged.fml.loading.LoadingModList");
            for (var ctor : loadingModListClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                Object[] args = new Object[ctor.getParameterCount()];
                for (int i = 0; i < args.length; i++) {
                    if (ctor.getParameterTypes()[i].equals(java.util.List.class)) {
                        args[i] = java.util.Collections.emptyList();
                    }
                }
                var instance = ctor.newInstance(args);
                var field = loadingModListClass.getDeclaredField("INSTANCE");
                field.setAccessible(true);
                field.set(null, instance);
                break;
            }
        } catch (Throwable ignored) {
        }
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }
    }

    @AfterEach
    void cleanUp() {
        TaczFirstPersonFrameSnapshot.clearCurrentThreadFrame();
    }

    @Test
    void shouldCopyMatricesAndClearStateWhenFrameFinishes() {
        Object player = new Object();
        Object stack = new Object();
        Matrix4f pose = new Matrix4f().translate(1, 2, 3);
        Matrix3f normal = new Matrix3f();
        Matrix4f entryPose = new Matrix4f().translate(4, 5, 6);

        TaczFirstPersonFrameSnapshot.beginFrame(player, stack, entryPose, true);
        assertTrue(TaczFirstPersonFrameSnapshot.captureHand(player, TaczFirstPersonFrameSnapshot.Hand.RIGHT, pose, normal, true));
        pose.m30(99);
        entryPose.m30(99);

        var snapshot = TaczFirstPersonFrameSnapshot.finishFrame(true);
        assertTrue(snapshot.isPresent());
        assertEquals(4.0f, snapshot.get().entryPose().m30());
        assertEquals(1.0f, snapshot.get().capturedHand(TaczFirstPersonFrameSnapshot.Hand.RIGHT).orElseThrow().pose().m30());
        var matrices = snapshot.get().consume(player, stack, TaczFirstPersonFrameSnapshot.Hand.RIGHT);
        assertTrue(matrices.isPresent());
        assertEquals(1.0f, matrices.get().pose().m30());
        assertFalse(snapshot.get().consume(player, stack, TaczFirstPersonFrameSnapshot.Hand.RIGHT).isPresent());
        assertFalse(TaczFirstPersonFrameSnapshot.shouldSuppressOriginalArm(player, TaczFirstPersonFrameSnapshot.Hand.RIGHT, true));
        assertFalse(TaczFirstPersonFrameSnapshot.finishFrame(true).isPresent());
    }

    @Test
    void shouldRejectWrongPlayerNonFiniteMatrixAndOffRenderThread() {
        Object player = new Object();
        TaczFirstPersonFrameSnapshot.beginFrame(player, new Object(), new Matrix4f(), true);
        assertFalse(TaczFirstPersonFrameSnapshot.captureHand(new Object(), TaczFirstPersonFrameSnapshot.Hand.LEFT, new Matrix4f(), new Matrix3f(), true));
        assertFalse(TaczFirstPersonFrameSnapshot.captureHand(player, TaczFirstPersonFrameSnapshot.Hand.LEFT, new Matrix4f().m00(Float.NaN), new Matrix3f(), true));
        assertFalse(TaczFirstPersonFrameSnapshot.captureHand(player, TaczFirstPersonFrameSnapshot.Hand.LEFT, new Matrix4f(), new Matrix3f(), false));
        assertFalse(TaczFirstPersonFrameSnapshot.shouldSuppressOriginalArm(player, TaczFirstPersonFrameSnapshot.Hand.LEFT, true));
    }

    @Test
    void shouldRejectConsumptionForAnotherPlayerOrGun() {
        Object player = new Object();
        Object stack = new Object();
        TaczFirstPersonFrameSnapshot.beginFrame(player, stack, new Matrix4f(), true);
        assertTrue(TaczFirstPersonFrameSnapshot.captureHand(player, TaczFirstPersonFrameSnapshot.Hand.LEFT, new Matrix4f(), new Matrix3f(), true));

        var snapshot = TaczFirstPersonFrameSnapshot.finishFrame(true).orElseThrow();
        assertFalse(snapshot.consume(new Object(), stack, TaczFirstPersonFrameSnapshot.Hand.LEFT).isPresent());
        assertTrue(snapshot.consume(player, stack, TaczFirstPersonFrameSnapshot.Hand.LEFT).isPresent());
    }

    @Test
    void shouldDiscardAnUnfinishedPreviousFrameBeforeStartingAnother() {
        Object player = new Object();
        TaczFirstPersonFrameSnapshot.beginFrame(player, new Object(), new Matrix4f(), true);
        assertTrue(TaczFirstPersonFrameSnapshot.captureHand(player, TaczFirstPersonFrameSnapshot.Hand.LEFT, new Matrix4f(), new Matrix3f(), true));

        TaczFirstPersonFrameSnapshot.beginFrame(player, new Object(), new Matrix4f(), true);
        assertFalse(TaczFirstPersonFrameSnapshot.shouldSuppressOriginalArm(player, TaczFirstPersonFrameSnapshot.Hand.LEFT, true));
        assertTrue(TaczFirstPersonFrameSnapshot.finishFrame(true).isPresent());
    }

    @Test
    void shouldKeepStrictIdentityFallbackForNonMinecraftTestTokens() {
        Object token = new Object();
        assertTrue(TaczFirstPersonFrameSnapshot.matchesGunStack(token, token));
        assertFalse(TaczFirstPersonFrameSnapshot.matchesGunStack(token, new Object()));
    }

    @Test
    void shouldMatchSameItemWhenFiringChangesDynamicTag() {
        ItemStack beforeFire = new ItemStack(Items.CROSSBOW);
        ItemStack afterFire = beforeFire.copy();
        beforeFire.getOrCreateTag().putInt("Ammo", 30);
        afterFire.getOrCreateTag().putInt("Ammo", 29);

        assertTrue(TaczFirstPersonFrameSnapshot.matchesGunStack(beforeFire, afterFire));
        assertFalse(TaczFirstPersonFrameSnapshot.matchesGunStack(beforeFire, new ItemStack(Items.BOW)));
        assertFalse(TaczFirstPersonFrameSnapshot.matchesGunStack(beforeFire, ItemStack.EMPTY));
    }

    @Test
    void shouldConsumeCapturedHandWhenSameItemHasDifferentDynamicTag() {
        Object player = new Object();
        ItemStack capturedStack = new ItemStack(Items.CROSSBOW);
        ItemStack firingStack = capturedStack.copy();
        capturedStack.getOrCreateTag().putInt("Ammo", 30);
        firingStack.getOrCreateTag().putInt("Ammo", 29);

        TaczFirstPersonFrameSnapshot.beginFrame(player, capturedStack, new Matrix4f(), true);
        assertTrue(TaczFirstPersonFrameSnapshot.captureHand(player,
                TaczFirstPersonFrameSnapshot.Hand.RIGHT, new Matrix4f(), new Matrix3f(), true));

        var snapshot = TaczFirstPersonFrameSnapshot.finishFrame(true).orElseThrow();
        assertTrue(snapshot.consume(player, firingStack, TaczFirstPersonFrameSnapshot.Hand.RIGHT).isPresent());
    }

    @Test
    void shouldRebuildEntityRootRelativeToCurrentCamera() {
        Matrix3f cameraView = new Matrix3f().rotateY((float) Math.PI / 2);
        var root = TaczFirstPersonPostRenderer.createEntityRootPose(
                12.5, 70.0, -4.0,
                10.0, 68.25, -7.5,
                cameraView);

        assertEquals(3.5f, root.last().pose().m30(), EPSILON);
        assertEquals(1.75f, root.last().pose().m31(), EPSILON);
        assertEquals(-2.5f, root.last().pose().m32(), EPSILON);
        Vector3f rotatedNormal = root.last().normal().transform(new Vector3f(1.0f, 0.0f, 0.0f));
        assertTrue(rotatedNormal.equals(new Vector3f(0.0f, 0.0f, -1.0f), EPSILON));
    }
}
