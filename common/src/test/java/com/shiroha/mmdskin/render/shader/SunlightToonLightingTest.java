package com.shiroha.mmdskin.render.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SunlightToonLightingTest {
    private static final String VERTEX = """
            void main() {
                float timeAngleM = timeAngle;
                const vec2 sunRotationData = vec2(1.0, 0.0);
                sunVec = normalize(vec3(0.0, 1.0, 0.0));
                upVec = normalize(vec3(0.0, 1.0, 0.0));
                eastVec = normalize(vec3(1.0, 0.0, 0.0));
            }
            """;
    private static final String BSL = """
            varying vec2 texCoord, lmCoord;
            varying vec3 normal, sunVec, upVec, eastVec;
            varying vec4 color;
            vec3 lightVec = sunVec;
            vec3 ToWorld(vec3 p) { return p; }
            vec3 GetShadow(vec3 p, vec3 n, float nl, float s, float sky) { return vec3(0.0); }
            void GetLighting(inout vec3 albedo, out vec3 shadow, vec3 viewPos, vec3 worldPos, vec3 normal,
                    vec2 lightmap, float smoothLighting, float NoL, float vanillaDiffuse,
                    float parallaxShadow, float emission, float subsurface, float basicSubsurface) {
                if (NoL > 0.0 || basicSubsurface > 0.0) {
                    shadow = GetShadow(worldPos, normal, NoL, basicSubsurface, lightmap.y);
                }
                NoL = clamp(NoL * 1.01 - 0.01, 0.0, 1.0);
                vec3 fullShadow = max(shadow * NoL, vec3(0.0));
                albedo *= fullShadow;
            }
            void main() {}
            """;

    @Test
    void bslSamplesOcclusionBeforeFillingTangentFacesWithoutChangingDiffuseInput() {
        String lighting = BslToonLighting.create(BSL, VERTEX, false);
        assertTrue(lighting.contains("toonPrepareSunlight(sunVec, toonLight, upVec)"));
        assertTrue(lighting.contains("toonSunlightStrength > 0.0 || NoL > 0.0"));
        assertTrue(lighting.contains("GetShadow(worldPos, normal, toonShadowSampleNdotL(NoL),"));
        assertTrue(lighting.contains("NoL = clamp(NoL * 1.01 - 0.01, 0.0, 1.0)"));
        assertTrue(lighting.contains("shadow * toonSunDiffuseRamp(NoL, shadow)"));
        assertTrue(lighting.contains("lmCoord, 1.0, ndl,"));
        assertTrue(lighting.contains("toonSunlitRamp(toonDiffuseRamp(ndl))"));
    }

    @Test
    void missingOrChangedShadowContractNeverEnablesSunlightFill() {
        for (String fragment : new String[]{
                BSL.replace("shadow = GetShadow(", "shadow = OtherShadow("),
                BSL.replace("NoL > 0.0", "UnverifiedCondition"),
                BSL.replace("shadow * NoL", "shadow * UnknownDiffuse")}) {
            String lighting = BslToonLighting.create(fragment, VERTEX, false);
            assertFalse(lighting.contains("toonPrepareSunlight("));
        }
    }

    @Test
    void netherAmbientLightingNeverEnablesSunlightFill() {
        String lighting = BslToonLighting.create(
                BSL.replace("vec3 fullShadow =", "vec3 sceneLighting = netherColSqrt; vec3 fullShadow ="),
                VERTEX, false);
        assertFalse(lighting.contains("toonPrepareSunlight("));
    }

    @Test
    void complementaryKeepsNormalBiasAndCapturedShadowSeparateFromDiffuseFill() {
        String fragment = """
                varying vec2 texCoord, lmCoord;
                varying vec3 normal, sunVec, upVec;
                varying vec4 color;
                vec3 ScreenToView(vec3 p) { return p; }
                void GetLighting() {
                    if ((NdotL > 0.0 || subsurface > 0.001)) {
                        float NdotLm = clamp(dot(normal, lightVec), 0.0, 1.0);
                        shadow = GetShadow(shadowPos, offset, water, doSubsurface);
                    }
                    fullShadow = shadow * max(NdotL, subsurface);
                }
                void main() {}
                """;
        String lighting = ComplementaryToonLighting.create(fragment, VERTEX);
        assertTrue(lighting.contains("toonPrepareSunlight(sunVec, lightVec, upVec)"));
        assertTrue(lighting.contains("toonSunlightStrength > 0.0 || NdotL > 0.0"));
        assertTrue(lighting.contains("float NdotLm = clamp(dot(normal, lightVec), 0.0, 1.0)"));
        assertTrue(lighting.contains("shadow * max(toonSunDiffuseRamp(NdotL, shadow),"));
        assertTrue(lighting.contains("illumination, toonSunlitRamp(band)"));
    }

    @Test
    void modernComplementarySamplesTangentFacesAndPreservesShadowTimeMultiplier() {
        String fragment = """
                vec2 texCoord;
                vec3 ViewToPlayer(vec3 p) { return p; }
                void DoLighting() {
                    if (NdotLM > 0.0001) {
                        shadowMult *= GetShadow(shadowPos, lightmap.y, offset, samples, false, playerPos);
                    }
                    shadowMult *= max(NdotLM * shadowTime, subsurface);
                }
                void main() {}
                """;
        String lighting = ComplementaryToonLighting.createModern(fragment, VERTEX);
        assertTrue(lighting.contains("if (toonSunlightStrength > 0.0 || NdotLM > 0.0001)"));
        assertTrue(lighting.contains("toonSunDiffuseRamp(NdotLM, shadowMult) * shadowTime"));
        assertTrue(lighting.contains("max(lit.rgb, vec3(0.0)), toonSunlitRamp(band)"));
        assertFalse(ComplementaryToonLighting.createModern(
                fragment.replace("shadowMult *= GetShadow(", "shadowMult *= UnknownShadow("), VERTEX)
                .contains("toonPrepareSunlight("));
    }
}
