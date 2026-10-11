package com.shiroha.mmdskin.texture.runtime;

import com.shiroha.mmdskin.bridge.runtime.NativeTexturePort;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextureGpuLoaderTest {
    @Test
    void releasesNativeHandleWhenMetadataReadFailsBeforeGpuSetup() {
        AtomicInteger deletes = new AtomicInteger();
        NativeTexturePort port = new NativeTexturePort() {
            @Override public long loadTexture(String filename) { return 1L; }
            @Override public int textureWidth(long handle) { throw new IllegalStateException("metadata failed"); }
            @Override public int textureHeight(long handle) { return 1; }
            @Override public long textureData(long handle) { return 0L; }
            @Override public boolean textureHasAlpha(long handle) { return true; }
            @Override public void copyTextureData(ByteBuffer targetBuffer, long sourceAddress, int size) { }
            @Override public void deleteTexture(long handle) { deletes.incrementAndGet(); }
        };

        assertThrows(IllegalStateException.class,
                () -> TextureGpuLoader.loadToGpu("metadata-error.png", port));
        assertEquals(1, deletes.get());
    }

    @Test
    void freesDecodeBufferAndNativeHandleWhenPixelCopyFails() {
        AtomicInteger allocations = new AtomicInteger();
        AtomicInteger frees = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        NativeTexturePort port = new NativeTexturePort() {
            @Override public long loadTexture(String filename) { return 2L; }
            @Override public int textureWidth(long handle) { return 1; }
            @Override public int textureHeight(long handle) { return 1; }
            @Override public long textureData(long handle) { return 0L; }
            @Override public boolean textureHasAlpha(long handle) { return true; }
            @Override public void copyTextureData(ByteBuffer targetBuffer, long sourceAddress, int size) {
                throw new IllegalStateException("copy failed");
            }
            @Override public void deleteTexture(long handle) { deletes.incrementAndGet(); }
        };
        TextureGpuLoader.BufferAllocator allocator = new TextureGpuLoader.BufferAllocator() {
            @Override public ByteBuffer allocate(int size) {
                allocations.incrementAndGet();
                return ByteBuffer.allocate(size);
            }
            @Override public void free(ByteBuffer buffer) { frees.incrementAndGet(); }
        };

        assertThrows(IllegalStateException.class,
                () -> TextureGpuLoader.decode("copy-error.png", port, "diffuse", "test", allocator));
        assertEquals(1, allocations.get());
        assertEquals(1, frees.get());
        assertEquals(1, deletes.get());
    }

    @Test
    void freesTransferredBufferWhenNativeHandleCleanupFailsBeforeReturningPredecodedTexture() {
        AtomicInteger frees = new AtomicInteger();
        NativeTexturePort port = new NativeTexturePort() {
            @Override public long loadTexture(String filename) { return 3L; }
            @Override public int textureWidth(long handle) { return 1; }
            @Override public int textureHeight(long handle) { return 1; }
            @Override public long textureData(long handle) { return 0L; }
            @Override public boolean textureHasAlpha(long handle) { return true; }
            @Override public void copyTextureData(ByteBuffer targetBuffer, long sourceAddress, int size) {
                targetBuffer.putInt(0xFFFFFFFF);
            }
            @Override public void deleteTexture(long handle) {
                throw new IllegalStateException("native cleanup failed");
            }
        };
        TextureGpuLoader.BufferAllocator allocator = new TextureGpuLoader.BufferAllocator() {
            @Override public ByteBuffer allocate(int size) { return ByteBuffer.allocate(size); }
            @Override public void free(ByteBuffer buffer) { frees.incrementAndGet(); }
        };

        assertThrows(IllegalStateException.class,
                () -> TextureGpuLoader.decode("delete-error.png", port, "diffuse", "test", allocator));
        assertEquals(1, frees.get());
    }
}
