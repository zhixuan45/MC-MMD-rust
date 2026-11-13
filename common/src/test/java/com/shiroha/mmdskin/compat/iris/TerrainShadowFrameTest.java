package com.shiroha.mmdskin.compat.iris;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainShadowFrameTest {
    @Test
    void frameOwnsCaptureAndTransmissionValidityTogether() {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        frame.releaseAll();
        Object owner = new Object();

        frame.begin(owner, true);
        assertTrue(frame.requested);
        assertFalse(frame.captureValid());
        assertEquals("terrain capture not reached", frame.status);

        frame.texture = 7;
        frame.replayColorTargets = 2;
        frame.captured("ready");
        assertTrue(frame.captureValid());
        assertFalse(frame.transmissionValid());

        frame.transmissionDepth = 8;
        frame.transmissionColor = 9;
        frame.capturedTransmission("ready: test");
        assertTrue(frame.transmissionValid());
        assertEquals("ready: test", frame.replayStatus);

        frame.texture = frame.transmissionDepth = frame.transmissionColor = 0;
        frame.releaseAll();
        assertFalse(frame.requested);
        assertEquals(0, frame.replayColorTargets);
        assertEquals("not captured", frame.status);
    }
}
