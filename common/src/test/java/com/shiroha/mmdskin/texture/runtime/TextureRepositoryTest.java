package com.shiroha.mmdskin.texture.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.shiroha.mmdskin.bridge.runtime.NativeTexturePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文件职责：验证纹理仓库的待回收票据状态机与预解码释放一致性。 */
class TextureRepositoryTest {

    @AfterEach
    void tearDown() {
        TextureRepository.Init();
    }

    @Test
    void shouldIgnoreStalePendingTicketAfterTextureIsReactivated() throws Exception {
        TextureRepository.Init();
        VramBudgetManager bm = budgetManager();

        TextureRepository.TextureSlot slot = new TextureRepository.TextureSlot();
        TextureRepository.Texture texture = new TextureRepository.Texture();
        texture.vramSize = 64L;
        slot.texture = texture;

        activeTextureCount().set(1);
        moveToPending().invoke(bm, "first", slot, texture, 10L, activeTextureCount());
        long firstTicketId = slot.pendingTicketId;

        activatePendingTexture().invoke(bm, slot, texture, activeTextureCount());
        moveToPending().invoke(bm, "second", slot, texture, 20L, activeTextureCount());
        long secondTicketId = slot.pendingTicketId;

        boolean staleEvicted = (boolean) evictIfCurrent().invoke(bm, null,
                new VramBudgetManager.PendingTicket("first", slot, firstTicketId, 10L));
        assertFalse(staleEvicted);
        assertTrue(slot.pending);
        assertEquals(texture, slot.texture);

        boolean currentEvicted = (boolean) evictIfCurrent().invoke(bm, null,
                new VramBudgetManager.PendingTicket("second", slot, secondTicketId, 20L));
        assertTrue(currentEvicted);
        assertFalse(slot.pending);
        assertNull(slot.texture);
        assertEquals(0, pendingTextureCount(bm).get());
        assertEquals(0L, pendingReleaseVram(bm).get());
    }

    @Test
    void shouldReleasePredecodedBufferOnlyOnce() {
        ByteBuffer buffer = MemoryUtil.memAlloc(4);
        TextureRepository.PredecodedTexture predecoded = new TextureRepository.PredecodedTexture(buffer, 1, 1, true);

        predecoded.release();
        predecoded.release();

        assertNull(predecoded.pixelData());
    }

    @Test
    void shouldSkipPredecodeForMissingOptionalTextureAndTryOnceAtRuntime() {
        TextureRepository.Init();
        AtomicInteger loadCalls = new AtomicInteger();
        TextureRepository.configureRuntimeCollaborators(new NativeTexturePort() {
            @Override public long loadTexture(String filename) {
                loadCalls.incrementAndGet();
                return 0L;
            }
            @Override public int textureWidth(long handle) { return 0; }
            @Override public int textureHeight(long handle) { return 0; }
            @Override public long textureData(long handle) { return 0L; }
            @Override public boolean textureHasAlpha(long handle) { return false; }
            @Override public void copyTextureData(ByteBuffer buffer, long source, int size) {}
            @Override public void deleteTexture(long handle) {}
        });

        String missingPath = Path.of(System.getProperty("java.io.tmpdir"),
                "mmd-missing-lightmap-" + System.nanoTime() + ".png").toString();
        TextureRepository.preloadTexture(missingPath, "light-map", "preload modelHandle=7");
        assertEquals(0, loadCalls.get());

        assertNull(TextureRepository.GetTexture(missingPath, "light-map", "render modelHandle=7"));
        assertEquals(1, loadCalls.get());
    }

    @Test
    void shouldClearPendingTextureAndPredecodedBufferTogether() throws Exception {
        TextureRepository.Init();
        VramBudgetManager bm = budgetManager();

        TextureRepository.TextureSlot slot = new TextureRepository.TextureSlot();
        TextureRepository.Texture texture = new TextureRepository.Texture();
        texture.vramSize = 32L;
        slot.texture = texture;
        slot.predecoded = new TextureRepository.PredecodedTexture(MemoryUtil.memAlloc(4), 1, 1, true);

        activeTextureCount().set(1);
        moveToPending().invoke(bm, "clear", slot, texture, 30L, activeTextureCount());
        clearSlot().invoke(null, slot);

        assertFalse(slot.pending);
        assertNull(slot.texture);
        assertNull(slot.predecoded);
        assertEquals(0, activeTextureCount().get());
        assertEquals(0, pendingTextureCount(bm).get());
        assertEquals(0L, pendingReleaseVram(bm).get());
    }

    private static VramBudgetManager budgetManager() throws Exception {
        Field field = TextureRepository.class.getDeclaredField("budgetManager");
        field.setAccessible(true);
        return (VramBudgetManager) field.get(null);
    }

    private static Method moveToPending() throws Exception {
        Method method = VramBudgetManager.class.getDeclaredMethod("moveToPending",
                String.class, TextureRepository.TextureSlot.class, TextureRepository.Texture.class,
                long.class, AtomicInteger.class);
        method.setAccessible(true);
        return method;
    }

    private static Method activatePendingTexture() throws Exception {
        Method method = VramBudgetManager.class.getDeclaredMethod("activatePendingTexture",
                TextureRepository.TextureSlot.class, TextureRepository.Texture.class, AtomicInteger.class);
        method.setAccessible(true);
        return method;
    }

    private static Method evictIfCurrent() throws Exception {
        Method method = VramBudgetManager.class.getDeclaredMethod("evictIfCurrent",
                Map.class, VramBudgetManager.PendingTicket.class);
        method.setAccessible(true);
        return method;
    }

    private static Method clearSlot() throws Exception {
        Method method = TextureRepository.class.getDeclaredMethod("clearSlot", TextureRepository.TextureSlot.class);
        method.setAccessible(true);
        return method;
    }

    private static AtomicInteger activeTextureCount() throws Exception {
        Field field = TextureRepository.class.getDeclaredField("activeTextureCount");
        field.setAccessible(true);
        return (AtomicInteger) field.get(null);
    }

    private static AtomicInteger pendingTextureCount(VramBudgetManager bm) throws Exception {
        Field field = VramBudgetManager.class.getDeclaredField("pendingTextureCount");
        field.setAccessible(true);
        return (AtomicInteger) field.get(bm);
    }

    private static AtomicLong pendingReleaseVram(VramBudgetManager bm) throws Exception {
        Field field = VramBudgetManager.class.getDeclaredField("pendingReleaseVram");
        field.setAccessible(true);
        return (AtomicLong) field.get(bm);
    }
}
