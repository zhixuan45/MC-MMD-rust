package com.shiroha.mmdskin.render.outline;

import com.shiroha.mmdskin.render.effectpack.FxEffect;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutlineFxAlphaTest {
    @Test void constantAlphaKeepsMaterialFactorAndRemovesTextureHoles() {
        var channel = new FxEffect.Channel(0, "", new float[]{.5f}, 0, new float[]{1, 1}, 0, 3, 0);
        var effect = new FxEffect("constant", "constant", Path.of("."), FxEffect.Kind.RAY_MATERIAL,
                Map.of("ALPHA", channel), .5f, FxEffect.IDENTITY, List.of());
        var result = OutlineRenderPass.coverage(.4f, effect);
        assertEquals(.2f, result.alpha());
        assertFalse(result.textureAlpha());
    }

    @Test void noFxRetainsTheOriginalMaskAndAlpha() {
        var result = OutlineRenderPass.coverage(.4f, null);
        assertEquals(.4f, result.alpha());
        assertTrue(result.textureAlpha());
    }
}
