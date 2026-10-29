package com.shiroha.mmdskin.stage.client.camera;

import com.shiroha.mmdskin.config.StageEndPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StageEndFrameResolverTest {
    @Test void selectionUsesTheRequestedTrackInsteadOfAlwaysTakingTheShortest() {
        assertEquals(900,resolve(StageEndPolicy.LEGACY,900,900,600,300).frame());
        assertEquals(900,resolve(StageEndPolicy.CAMERA,900,900,600,300).frame());
        assertEquals(600,resolve(StageEndPolicy.MOTION,900,900,600,300).frame());
        assertEquals(300,resolve(StageEndPolicy.AUDIO,900,900,600,300).frame());
        assertEquals(300,resolve(StageEndPolicy.SHORTEST,900,900,600,300).frame());
    }
    @Test void missingSourcesDoNotCountAsZeroAndSelectedMissingSourceFallsBack() {
        assertEquals(600,resolve(StageEndPolicy.SHORTEST,900,-1,600,Float.NaN).frame());
        var result=resolve(StageEndPolicy.AUDIO,900,900,600,-1);
        assertEquals(900,result.frame());
        assertTrue(result.fallback());
    }
    @Test void actualFrameZeroIsDifferentFromAMissingTrack() {
        assertEquals(0,resolve(StageEndPolicy.CAMERA,900,0,600,-1).frame());
        assertEquals(0,resolve(StageEndPolicy.SHORTEST,900,0,600,-1).frame());
    }
    private static StageEndFrameResolver.Result resolve(StageEndPolicy policy,float legacy,float camera,float motion,float audio) {
        return StageEndFrameResolver.resolve(policy,legacy,camera,motion,audio);
    }
}
