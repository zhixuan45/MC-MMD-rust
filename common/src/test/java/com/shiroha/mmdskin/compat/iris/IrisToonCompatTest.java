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
}
