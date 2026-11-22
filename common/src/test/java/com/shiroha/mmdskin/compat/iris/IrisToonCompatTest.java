package com.shiroha.mmdskin.compat.iris;

import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisToonCompatTest {
    @Test
    void unsupportedProfileSkipsToonForCurrentPass() {
        AtomicInteger selections = new AtomicInteger();

        boolean selected = IrisToonCompat.selectCurrentPassProfile(
                ToonOutputProfile.unsupported("unknown MRT"), profile -> {
                    selections.incrementAndGet();
                    return true;
                });

        assertFalse(selected);
        assertEquals(0, selections.get());
    }

    @Test
    void supportedProfileUsesSelectorResultWithoutLegacyFallback() {
        ToonOutputProfile profile = ToonOutputProfile.vanilla();
        AtomicInteger selections = new AtomicInteger();

        boolean selected = IrisToonCompat.selectCurrentPassProfile(profile, candidate -> {
            selections.incrementAndGet();
            assertEquals(profile, candidate);
            return false;
        });

        assertFalse(selected);
        assertEquals(1, selections.get());
        assertTrue(profile.isSupported());
    }

    @Test
    void notificationKeyIncludesPackProgramAndReason() {
        assertEquals(
                IrisToonCompat.notificationKey("pack", "entities", "bad layout"),
                IrisToonCompat.notificationKey("pack", "entities", "bad layout"));
        assertFalse(IrisToonCompat.notificationKey("pack", "entities", "bad layout")
                .equals(IrisToonCompat.notificationKey("pack", "translucent", "bad layout")));
        assertFalse(IrisToonCompat.notificationKey("pack", "entities", "bad layout")
                .equals(IrisToonCompat.notificationKey("pack", "entities", "compile failed")));
    }

    @Test
    void shadowPassMetadataReadDoesNotEvictMainPassCache() throws Exception {
        var cacheField = IrisToonMetadata.class.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (java.util.Map<net.minecraft.client.renderer.ShaderInstance, IrisToonMetadata.Result>) cacheField.get(null);
        var cachedPackField = IrisToonMetadata.class.getDeclaredField("cachedPack");
        cachedPackField.setAccessible(true);
        Object sentinelPack = new Object();
        IrisToonMetadata.reset();
        try {
            cachedPackField.set(null, sentinelPack);
            cache.put(null, new IrisToonMetadata.Result("pack", "entities", ToonOutputProfile.vanilla()));

            // 阴影通道返回 vanilla，但必须保留主通道已解析的光影缓存，避免每帧重解析导致掉帧。
            assertEquals("vanilla", IrisToonMetadata.read(null, true, true).profile().id());
            assertEquals(1, cache.size());
            assertEquals(sentinelPack, cachedPackField.get(null));

            // 关闭光影时才真正清空缓存。
            assertEquals("vanilla", IrisToonMetadata.read(null, false, false).profile().id());
            assertEquals(0, cache.size());
        } finally {
            IrisToonMetadata.reset();
        }
    }
}
