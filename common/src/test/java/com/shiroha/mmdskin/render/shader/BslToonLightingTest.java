package com.shiroha.mmdskin.render.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BslToonLightingTest {
    private static final String FRAGMENT = """
            varying vec2 texCoord, lmCoord;
            varying vec3 normal, sunVec, upVec, eastVec;
            varying vec4 color;
            uniform float timeAngle;
            vec3 lightVec = sunVec * ((timeAngle < 0.5325 || timeAngle > 0.9675) ? 1.0 : -1.0);
            vec3 ToWorld(vec3 pos) { return pos; }
            void GetLighting(inout vec3 albedo, out vec3 shadow, vec3 viewPos, vec3 worldPos, vec3 normal,
                vec2 lightmap, float smoothLighting, float NoL, float vanillaDiffuse,
                float parallaxShadow, float emission, float subsurface, float basicSubsurface) {
                vec3 fullShadow = max(shadow * NoL, vec3(0.0));
                albedo *= fullShadow;
            }
            void main() {
                vec4 albedo = vec4(1.0);
                albedo.rgb = pow(albedo.rgb, vec3(2.2));
                albedo.rgb = sqrt(max(albedo.rgb, vec3(0.0)));
                /* DRAWBUFFERS:0 */
                gl_FragData[0] = albedo;
            }
            """;
    private static final String VERTEX = """
            void main() {
                const vec2 sunRotationData = vec2(1.0, 0.0);
                sunVec = normalize(vec3(1.0, 1.0, 0.0));
                upVec = normalize(vec3(0.0, 1.0, 0.0));
                eastVec = normalize(vec3(1.0, 0.0, 0.0));
            }
            """;

    @Test
    void retainsNativeCoordinatesAndQuantizesDiffuseAfterShadowSampling() {
        var profile = ToonOutputProfile.resolve("renamed.zip", "gbuffers_entities", FRAGMENT, VERTEX, new int[]{0});
        assertTrue(profile.isSupported(), profile.reason());
        String lighting = profile.lightingSource();
        assertTrue(lighting.contains("shadow = vec3(0.0)"));
        assertTrue(lighting.contains("max(shadow * toonDiffuseRamp(NoL),"));
        assertTrue(lighting.contains("vec3 world = ToWorld(p)"));
        assertTrue(lighting.contains("GetLighting(lit, shadow, p, world, n,"));
        assertTrue(lighting.contains("vec3(1.0 / 2.2)"));
        assertFalse(lighting.contains("varying "));
    }

    @Test
    void genericVariantRetainsEveryOutputAndDoesNotReuseNativeVariantKey() {
        var nativeProfile = ToonOutputProfile.resolve("", "gbuffers_entities", FRAGMENT, VERTEX, new int[]{0});
        var generic = nativeProfile.withoutPackLighting();
        assertTrue(generic.isSupported());
        assertFalse(nativeProfile.lightingSource().isEmpty());
        assertTrue(generic.lightingSource().isEmpty());
        assertEquals(nativeProfile.fragmentWriter(), generic.fragmentWriter());
        assertEquals(nativeProfile.fragmentDeclarations(), generic.fragmentDeclarations());
        assertEquals(nativeProfile.colorOutputSlot(), generic.colorOutputSlot());
        assertEquals(nativeProfile.outlineWriter(), generic.outlineWriter());
        assertNotEquals(nativeProfile.id(), generic.id());
        assertSame(generic, generic.withoutPackLighting());
    }

    @Test
    void missingSolarContractFallsBackToGenericLightingWithVerifiedBslEncoding() {
        var profile = ToonOutputProfile.resolve("", "gbuffers_entities", FRAGMENT, "void main() {}", new int[]{0});
        assertTrue(profile.isSupported());
        assertTrue(profile.lightingSource().isEmpty());
        assertTrue(profile.fragmentWriter().contains("sqrt(pow(max(color"));
    }

    @Test
    void handUsesNativeHandShadowConvention() {
        assertTrue(BslToonLighting.create(FRAGMENT, VERTEX, true)
                .contains("GetLighting(lit, shadow, p, world, vec3(0.0),"));
    }
}
