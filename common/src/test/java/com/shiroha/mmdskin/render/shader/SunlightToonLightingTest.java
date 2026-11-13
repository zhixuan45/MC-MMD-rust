package com.shiroha.mmdskin.render.shader;

import org.junit.jupiter.api.Test;
import com.shiroha.mmdskin.util.AssetsUtil;

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
    private static final String MODERN_TRANSMISSION = """
            uniform sampler2DShadow shadowtex0;
            uniform sampler2DShadow shadowtex1;
            uniform sampler2D shadowcolor0;
            vec2 texCoord;
            vec3 ViewToPlayer(vec3 p) { return p; }
            vec3 SampleShadow(vec3 shadowPos, float colorMult, float colorPow) {
                float full = shadow2D(shadowtex0, vec3(shadowPos.st, shadowPos.z)).x;
                vec3 transmission = vec3(0.0);
                if (full < 1.0) {
                    float opaque = shadow2D(shadowtex1, vec3(shadowPos.st, shadowPos.z)).x;
                    if (opaque * 0.9999 > full) {
                        transmission = texture2D(shadowcolor0, shadowPos.st).rgb * opaque;
                        transmission *= colorMult;
                        transmission = pow(transmission, vec3(colorPow));
                    }
                }
                return transmission * (1.0 - full) + full;
            }
            vec3 GetShadow(vec3 shadowPos, float lightmapY, vec3 offset, int samples,
                    bool leaves, vec3 playerPos) {
                float colorMult = 2.5 + 5.5 * pow(lightmapY, 1.5) + 2.0 * lightmapY * lightmapY;
                float colorPow = mix(2.0, 0.5, pow(lightmapY, 4.0));
                return SampleShadow(shadowPos, colorMult, colorPow);
            }
            void DoLighting() {
                float NdotLM = clamp(dot(normal, lightVec), 0.0, 1.0);
                float shadowTime = 1.0;
                float subsurface = 0.0;
                vec3 shadowPos = GetShadowPos(playerPosM);
                if (NdotLM > 0.0001) {
                    shadowMult *= GetShadow(shadowPos, lightmap.y, vec3(0.0), 1, false, playerPos);
                }
                shadowMult *= max(NdotLM * shadowTime, subsurface);
            }
            void main() {}
            """;

    @Test
    void bslSamplesOcclusionBeforeFillingTangentFacesWithoutChangingDiffuseInput() {
        String lighting = BslToonLighting.create(BSL, VERTEX, false);
        assertTrue(lighting.contains("toonPrepareSunlight(sunVec, toonLight, upVec)"));
        assertTrue(lighting.contains("toonSunlightStrength > 0.0 || NoL > 0.0"));
        assertTrue(lighting.contains("GetShadow(worldPos, toonShadowSampleNormal(normal), toonShadowSampleNdotL(NoL),"));
        assertTrue(lighting.contains("ToonTerrainShadowEnabled || toonSunlightStrength"));
        assertTrue(lighting.contains("vec3 world = ToWorld(p)"));
        assertFalse(lighting.contains("toonModelShadow"));
        assertTrue(lighting.contains("NoL = clamp(NoL * 1.01 - 0.01, 0.0, 1.0)"));
        assertTrue(lighting.contains("shadow * toonSunDiffuseRamp(NoL, shadow)"));
        assertTrue(lighting.contains("lmCoord, 1.0, ndl,"));
        assertTrue(lighting.contains("vec3(1.0 / 2.2)), toonDiffuseRamp(ndl))"));
        assertFalse(lighting.contains("toonSunlitRamp("));
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
    void bslBodyAndHandKeepSurfaceBandsWhenDirectLightCanBeFilled() {
        for (boolean hand : new boolean[]{false, true}) {
            String lighting = BslToonLighting.create(BSL, VERTEX, hand);
            assertTrue(lighting.contains("shadow * toonSunDiffuseRamp(NoL, shadow)"));
            String surface = lighting.substring(lighting.lastIndexOf("return shadeToonSurface("));
            assertTrue(surface.contains("toonDiffuseRamp(ndl)"));
            assertFalse(surface.contains("toonSun"), "Filled light energy must not select surface bands");
            assertTrue(lighting.contains(hand ? "p, world, vec3(0.0)," : "p, world, n,"));
        }
    }

    @Test
    void diagnosticCodeIsDisabledByDefaultAndLeavesOutputAndDepthPassesInPlace() {
        String body = AssetsUtil.getAssetsAsString("shader/toon_main_body.frag.glsl").replace("\r\n", "\n");
        String source = ToonShaderBase.outputSource(body, ToonOutputProfile.vanilla());
        assertFalse(source.contains("#define TOON_LIGHTING_DEBUG"));
        assertTrue(source.contains("#ifdef TOON_LIGHTING_DEBUG"));
        assertTrue(source.contains("toonOutput0 = vec4(gl_FragCoord.z);\n            return;"));
        assertTrue(source.indexOf("toonLightingDebugColor(finalColor, normal)") > source.indexOf("mmdFxGlass(finalColor"));
        assertTrue(source.contains("writeToonOutputs(finalColor, albedo, normal, finalAlpha)"));
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
        assertTrue(lighting.contains("float NdotLm = (ToonTerrainShadowEnabled ? 1.0 : (clamp(dot(normal, lightVec), 0.0, 1.0)))"));
        assertTrue(lighting.contains("shadow * max(toonSunDiffuseRamp(NdotL, shadow),"));
        assertTrue(lighting.contains("illumination, band)"));
        assertFalse(lighting.contains("toonSunlitRamp("));
        assertTrue(lighting.contains("vec3 world = (gbufferModelViewInverse * vec4(p, 1.0)).xyz"));
        assertFalse(lighting.contains("normal = lightVec"));
        assertFalse(lighting.contains("toonModelShadow"));
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
        assertTrue(lighting.contains("if (ToonTerrainShadowEnabled || toonSunlightStrength > 0.0 || NdotLM > 0.0001)"));
        assertTrue(lighting.contains("vec3 player = ViewToPlayer(p)"));
        assertTrue(lighting.contains("vec3 sampleNormal = n"));
        assertFalse(lighting.contains("toonModelShadow"));
        assertTrue(lighting.contains("toonSunDiffuseRamp(NdotLM, shadowMult) * shadowTime"));
        assertTrue(lighting.contains("max(lit.rgb, vec3(0.0)), band)"));
        assertFalse(lighting.contains("toonSunlitRamp("));
        assertFalse(ComplementaryToonLighting.createModern(
                fragment.replace("shadowMult *= GetShadow(", "shadowMult *= UnknownShadow("), VERTEX)
                .contains("toonPrepareSunlight("));
    }

    @Test
    void modernLegacyUsesFifteenArgumentsAndOldShadowDecode() {
        String legacy = """
                uniform sampler2DShadow shadowtex0;
                uniform sampler2DShadow shadowtex1;
                uniform sampler2D shadowcolor0;
                vec2 texCoord;
                vec3 ViewToPlayer(vec3 p) { return p; }
                vec3 SampleShadow(vec3 p, float colorMult, float colorPow) {
                    float full = shadow2D(shadowtex0, p).x;
                    float opaque = shadow2D(shadowtex1, p).x;
                    return texture2D(shadowcolor0, p.st).rgb * opaque * colorMult;
                }
                vec3 GetShadow(vec3 shadowPos, float lViewPos, float lightmapY, float offset, bool leaves) {
                    float colorMult = 1.2 + 3.8 * lightmapY;
                    float colorPow = 1.1 - 0.6 * pow2(pow2(pow2(lightmapY)));
                    return SampleShadow(shadowPos, colorMult, colorPow);
                }
                void DoLighting(inout vec4 color, inout vec3 shadowMult, vec3 playerPos, vec3 viewPos,
                        float lViewPos, vec3 normalM, vec2 lightmap, bool noSmoothLighting,
                        bool noDirectionalShading, bool noVanillaAO, bool centerShadowBias,
                        int subsurfaceMode, float smoothnessG, float highlightMult, float emission) {
                    float NdotLM = clamp(dot(normalM, lightVec), 0.0, 1.0);
                    float shadowTime = 1.0;
                    vec3 shadowPos = GetShadowPos(playerPosM);
                    if (NdotLM > 0.0001) {
                        shadowMult *= GetShadow(shadowPos, lViewPos, lightmap.y, 0.0, false);
                    }
                    shadowMult *= max(NdotLM * shadowTime, 0.0);
                    color.rgb *= shadowMult;
                }
                void main() {}
                """;
        String lighting = ComplementaryToonLighting.createModernLegacy(legacy, VERTEX);
        assertTrue(lighting.contains("shadowMult *= max(toonSunDiffuseRamp(NdotLM, shadowMult) * shadowTime,"));
        assertTrue(lighting.contains(
                "DoLighting(lit, shadow, player, p, length(p), sampleNormal, lmCoord,\n"
                        + "           false, true, true, true, 0, 0.0, 0.0, 0.0);"));
        assertFalse(lighting.contains(
                "DoLighting(lit, shadow, player, p, length(p), sampleNormal, sampleNormal, 0.5, worldNormal, lmCoord,"));
        assertTrue(lighting.contains("return toonComplementaryFilteredTransmission(shadowPos, colorMult, colorPow);"));
        assertTrue(lighting.contains("if (full < 1.0 && opaque > 0.9999)"));
        assertFalse(lighting.contains("if (opaque * 0.9999 > full)"));
        assertTrue(lighting.contains("colorMult = 1.2 + 3.8 * lightmapY;"));
    }

    @Test
    void modernLegacyCarriesOnlyVerifiedSunVectorHelper() {
        String vertex = """
                void main() {
                    sunVec = GetSunVector();
                }
                vec3 GetSunVector() {
                    const vec2 sunRotationData = vec2(cos(sunPathRotation * 0.01745329251994),
                            -sin(sunPathRotation * 0.01745329251994));
                    float ang = fract(timeAngle - 0.25);
                    ang = (ang + (cos(ang * 3.14159265358979) * -0.5 + 0.5 - ang) / 3.0)
                            * 6.28318530717959;
                    return normalize((gbufferModelView
                            * vec4(vec3(-sin(ang), cos(ang) * sunRotationData) * 2000.0, 1.0)).xyz);
                }
                """;
        String lighting = ComplementaryToonLighting.createModernLegacy(MODERN_TRANSMISSION, vertex);
        assertTrue(lighting.contains("vec3 GetSunVector()"));
        assertTrue(lighting.contains("sunVec = GetSunVector();"));

        String unsafeVertex = vertex.replace(
                "return normalize((gbufferModelView", "return texture2D(noiseTexture, vec2(0.0, gbufferModelView");
        assertTrue(ComplementaryToonLighting.createModernLegacy(MODERN_TRANSMISSION, unsafeVertex).isEmpty());
    }

    @Test
    void modernComplementarySeparatesGlassDepthsAndPreservesNativeDecode() {
        String lighting = ComplementaryToonLighting.createModern(MODERN_TRANSMISSION, VERTEX);
        assertTrue(lighting.contains(
                "ToonTerrainShadowTransmissionEnabled ? texture(ToonTerrainShadowTranslucentDepth, p)"));
        assertTrue(lighting.contains("toonOpaqueShadow2D(shadowtex1,"));
        assertTrue(lighting.contains(
                "if (ToonTerrainShadowEnabled && !ToonTerrainShadowTransmissionEnabled && ToonWorldShadowSoftness > 0.0)"));
        assertTrue(lighting.contains(
                "return toonComplementaryFilteredTransmission(shadowPos, colorMult, colorPow);"));
        assertTrue(lighting.contains(
                "float full = texture(ToonTerrainShadowTranslucentDepth, vec3(uv, p.z));"));
        assertTrue(lighting.contains(
                "float opaque = texture(ToonTerrainShadowDepth, vec3(uv, p.z));"));
        assertTrue(lighting.contains(
                "texture(ToonTerrainShadowColor, uv).rgb * opaque * colorMult"));
        assertTrue(lighting.contains("vec3(colorPow)"));
        assertTrue(lighting.contains("toonRecordOpaqueSunVisibility(opaque, weight);"));
        assertTrue(lighting.contains("shadow += (transmission * (1.0 - full) + full) * weight;"));
        assertTrue(lighting.contains("if (opaque * 0.9999 > full)"));
        assertFalse(lighting.contains("if (full < 1.0 && opaque > 0.9999)"));
        assertTrue(lighting.contains("return shadow * 0.0625;"));

        String withoutSnapshotContract = ComplementaryToonLighting.createModern(
                MODERN_TRANSMISSION.replace(
                        "transmission = texture2D(shadowcolor0, shadowPos.st).rgb * opaque;",
                        "transmission = vec3(0.0);"), VERTEX);
        assertFalse(withoutSnapshotContract.contains("toonComplementaryFilteredTransmission"));
        assertTrue(withoutSnapshotContract.contains(
                "texture(ToonTerrainShadowTranslucentDepth, p) : texture(ToonTerrainShadowDepth, p)"));
    }

    @Test
    void lowQualityModernShadowsDoNotActivateUnusedHostColorSamplers() {
        String lowQuality = MODERN_TRANSMISSION.replace(
                "return SampleShadow(shadowPos, colorMult, colorPow);",
                "return vec3(shadow2D(shadowtex0, shadowPos).r);");
        String lighting = ComplementaryToonLighting.createModern(lowQuality, VERTEX);
        assertFalse(lighting.contains("toonComplementaryFilteredTransmission"));
        assertTrue(lighting.contains(
                "if (ToonTerrainShadowEnabled && ToonWorldShadowSoftness > 0.0) return vec3(toonFilteredTerrainShadow(shadowPos));"));
    }

    @Test
    void legacyAndCoreComparisonCallsAreRoutedWithoutChangingCoordinatesOrColorInputs() {
        String adapted = ComplementaryToonLighting.adaptShadowSampler("""
                uniform sampler2DShadow shadowtex0;
                uniform sampler2D shadowcolor0;
                uniform sampler2D albedo;
                float a = shadow2D(shadowtex0, shadowPos).x;
                float b = texture(shadowtex0, shadowPos);
                vec3 c = texture(shadowcolor0, shadowPos.xy + offset).rgb;
                vec3 d = texture(albedo, uv).rgb;
                """);
        assertTrue(adapted.contains("toonShadow2D(shadowtex0, shadowPos).x"));
        assertTrue(adapted.contains("toonTerrainShadow(shadowtex0, shadowPos)"));
        assertTrue(adapted.contains("toonShadowColor(shadowcolor0, shadowPos.xy + offset).rgb"));
        assertTrue(adapted.contains("texture(albedo, uv).rgb"));
    }

    @Test
    void rawDepthHelpersAreNotMistakenForComparisonSamplersWithSimilarNames() {
        String source = """
                uniform sampler2DShadow shadowtex0;
                uniform sampler2D shadowtex1;
                float raw(sampler2D shadowtex, vec2 uv) { return texture(shadowtex, uv).r; }
                float other = texture(shadowtex1, coord).r;
                """;
        assertEquals(source, ComplementaryToonLighting.adaptShadowSampler(source));
    }

    @Test
    void modernDoLightingSupportsTwentyTwoArgumentEuphoriaSignature() {
        String modern22 = """
                vec2 texCoord;
                vec3 ViewToPlayer(vec3 p) { return p; }
                void DoLighting(inout vec4 color, inout vec3 shadowMult, vec3 playerPos, vec3 viewPos,
                        float lViewPos, vec3 geoNormal, vec3 normalM, float dither, vec3 worldGeoNormal,
                        vec2 lightmap, bool noSmoothLighting, bool noDirectionalShading, bool noVanillaAO,
                        bool centerShadowBias, int subsurfaceMode, float smoothnessG, float highlightMult,
                        float emission, inout float purkinjeOverwrite, bool isLightSource,
                        inout float enderDragonDead, vec3 metalHighlightTint) {}
                void main() {}
                """;
        String lighting = ComplementaryToonLighting.createModern(modern22, VERTEX);
        assertTrue(lighting.contains("float purkinjeOverwrite = 0.0;"));
        assertTrue(lighting.contains("float enderDragonDead = 1.0;"));
        assertTrue(lighting.contains("DoLighting(lit, shadow, player, p, length(p), sampleNormal, sampleNormal,"
                + " 0.5, worldNormal, lmCoord, false, true, true, true, 0, 0.0, 0.0, 0.0,"
                + " purkinjeOverwrite, false, enderDragonDead, vec3(1.0));"));
    }
}
