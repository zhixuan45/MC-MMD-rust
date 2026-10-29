package com.shiroha.mmdskin.stage.client.camera;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StageAudioSyncTest {
    @Test void normalDriftDoesNotSeekThroughTheWaveform() {
        StageAudioSync sync = new StageAudioSync();
        for (int i=0;i<300;i++) {
            var result=sync.update(i/60f+.2f,i/60f,i*16_666_667L);
            assertTrue(Float.isNaN(result.seekSeconds()));
            assertEquals(1,result.gain());
            assertTrue(result.pitch()>1 && result.pitch()<=1.05f);
        }
        assertEquals(1,sync.update(6,6,6_000_000_000L).pitch());
    }
    @Test void largeDiscontinuitySeeksOnlyAfterFadingOutThenFadesIn() {
        StageAudioSync sync = new StageAudioSync();
        assertEquals(1,sync.update(10,1,0).gain());
        var middle=sync.update(10.015f,1.015f,15_000_000);
        assertEquals(.5f,middle.gain());
        assertTrue(Float.isNaN(middle.seekSeconds()));
        var jump=sync.update(10.03f,1.03f,30_000_000);
        assertEquals(0,jump.gain());
        assertEquals(10.03f,jump.seekSeconds());
        assertEquals(.5f,sync.update(10.045f,10.045f,45_000_000).gain());
        assertEquals(1,sync.update(10.06f,10.06f,60_000_000).gain());
    }
    @Test void resetRemovesPendingSeek() {
        StageAudioSync sync=new StageAudioSync();
        sync.update(10,0,0); sync.reset();
        var result=sync.update(0,0,40_000_000);
        assertEquals(1,result.gain());
        assertTrue(Float.isNaN(result.seekSeconds()));
    }
}
