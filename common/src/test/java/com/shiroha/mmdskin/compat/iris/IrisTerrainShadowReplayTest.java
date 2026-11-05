package com.shiroha.mmdskin.compat.iris;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IrisTerrainShadowReplayTest {
    private static final String SHADOW = """
            void main() {
                albedo.rgb = mix(vec3(1.0), albedo.rgb, 1.0 - pow(1.0 - albedo.a, 1.5));
                albedo.rgb *= 1.0 - pow(albedo.a, 96.0);
                gl_FragData[0] = albedo;
            }
            """;

    @Test void acceptsVerifiedSingleTargetTransmissionAndRejectsOpaqueOnlyProtocol() {
        assertTrue(IrisTerrainShadowReplay.supports(SHADOW, "void main() {}"));
        assertFalse(IrisTerrainShadowReplay.supports(SHADOW.replace("96.0", "unknownExponent"), ""));
        assertFalse(IrisTerrainShadowReplay.supports("void main() { gl_FragData[0] = albedo; }", ""));
    }

    @Test void rejectsUnrestorableSideEffectsAndReadsFromHostShadowHistory() {
        for (String extra : new String[]{
                "gl_FragData[1] = albedo;", "gl_FragData[10] = albedo;",
                "gl_FragDepth = 0.0;", "imageStore(data, pixel, albedo);", "atomicAdd(counter, 1);",
                "layout(std430) buffer State { uint count; };",
                "layout(location=1) out vec4 Other;",
                "uniform sampler2D shadowtex0;", "uniform sampler2D shadowcolor0;"}) {
            assertFalse(IrisTerrainShadowReplay.supports(SHADOW + extra, ""), extra);
            assertFalse(IrisTerrainShadowReplay.supports(SHADOW, extra), extra);
        }
    }
}
