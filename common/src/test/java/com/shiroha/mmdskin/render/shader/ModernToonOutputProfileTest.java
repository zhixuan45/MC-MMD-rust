package com.shiroha.mmdskin.render.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 输出契约取自 Modrinth 的 Complementary r5.9.3 与 BSL 10.1.8，完整包由可选探针读取。 */
class ModernToonOutputProfileTest {
    private static final String MODERN_CONTRACT = """
            const float OSIEBCA = 1.0 / 255.0;
            void DoLighting(inout vec4 color, inout vec3 shadowMult, vec3 playerPos) {
                vec3 finalDiffuse = vec3(1.0);
                finalDiffuse = sqrt(max(finalDiffuse, vec3(0.0)));
                color.rgb *= finalDiffuse;
            }
            float smoothnessD = 0.0, materialMask = OSIEBCA * 254.0;
            float skyLightFactor = GetSkyLightFactor(lmCoordM, shadowMult);
            vec3 translucentMult = vec3(1.0);
            /* DRAWBUFFERS:0364 */
            gl_FragData[0] = color;
            gl_FragData[1] = vec4(1.0 - translucentMult, 1.0);
            gl_FragData[2] = vec4(smoothnessD, materialMask, skyLightFactor, 1.0);
            gl_FragData[3] = vec4(mat3(gbufferModelViewInverse) * normalM, 1.0);
            """;
    private static final String BSL_COLOR = """
            albedo.rgb = pow(albedo.rgb, vec3(2.2));
            albedo.rgb = sqrt(max(albedo.rgb, vec3(0.0)));
            gl_FragData[0] = albedo;
            """;

    @Test
    void acceptsBothComplementaryEditionsWithoutApplyingOldLinearEncoding() {
        for (String pack : new String[]{"ComplementaryUnbound_r5.9.3.zip", "ComplementaryReimagined_r5.9.3.zip"}) {
            var profile = ToonOutputProfile.resolve(pack, "gbuffers_entities", MODERN_CONTRACT, new int[]{0, 3, 6, 4});
            assertTrue(profile.isSupported(), profile.reason());
            assertTrue(profile.fragmentWriter().contains("toonOutput0 = vec4(color, alpha)"));
            assertTrue(profile.fragmentWriter().contains("254.0 / 255.0"));
            assertTrue(profile.fragmentWriter().contains("mat3(ToonViewToWorld) * normal"));
            assertFalse(profile.fragmentWriter().contains("toonOutput0 = vec4(pow("));
            assertTrue(profile.outlineWriter().contains("vec3 encoded = color;"));
            assertTrue(profile.outlineWriter().contains("Premultiplied ? encoded * alpha : encoded"));
        }
    }

    @Test
    void adaptsModernShadowSamplerForCoreGlsl() {
        String fragment = "uniform sampler2DShadow shadowtex0;\nvec2 texCoord;\n"
                + "vec3 ViewToPlayer(vec3 p) { return p; }\n"
                + MODERN_CONTRACT.replace("vec3 finalDiffuse = vec3(1.0);",
                        "vec3 finalDiffuse = vec3(1.0); float shadow = shadow2D(shadowtex0, vec3(0.5)).r;")
                + "\nvoid main() {}\n";
        String vertex = "void main() { sunVec = normalize(vec3(1.0)); }";

        var profile = ToonOutputProfile.resolve("ComplementaryUnbound_r5.9.3", "gbuffers_entities",
                fragment, vertex, new int[]{0, 3, 6, 4});

        assertTrue(profile.isSupported(), profile.reason());
        assertTrue(profile.lightingSource().contains("vec4 toonShadow2D(sampler2DShadow s, vec3 p)"));
        assertTrue(profile.lightingSource().contains("toonShadow2D(shadowtex0, vec3(0.5))"));
        assertFalse(profile.lightingSource().contains("shadow2D("));
    }

    @Test
    void routesOutlineToColorSlotAfterMultiDigitTargets() {
        String source = MODERN_CONTRACT.replace("DRAWBUFFERS:0364", "RENDERTARGETS:13,3,6,4,0")
                .replace("gl_FragData[0] = color;", "gl_FragData[4] = color;") + """
                float lViewPos = length(viewPos);
                float entityLinearDepth = sqrt(lViewPos / far);
                gl_FragData[0] = vec4(entityLinearDepth, 1.0, 1.0, 1.0);
                """;
        var profile = ToonOutputProfile.resolve("ComplementaryUnbound_r5.9.3", "gbuffers_entities_translucent",
                source, new int[]{13, 3, 6, 4, 0});
        assertTrue(profile.isSupported(), profile.reason());
        assertEquals(4, profile.colorOutputSlot());
        assertEquals(-1, ToonOutputProfile.unsupported("unknown").colorOutputSlot());
    }

    @Test
    void supportsMultiDigitRenderTargetsAndValidatesTheirOrder() {
        String source = MODERN_CONTRACT.replace("DRAWBUFFERS:0364", "RENDERTARGETS: 0,3,6,4,13") + """
                float lViewPos = length(viewPos);
                float entityLinearDepth = sqrt(lViewPos / far);
                gl_FragData[4] = vec4(entityLinearDepth, 1.0, 1.0, 1.0);
                """;
        var profile = ToonOutputProfile.resolve("ComplementaryUnbound_r5.9.3", "gbuffers_entities_translucent",
                source, new int[]{0, 3, 6, 4, 13});
        assertTrue(profile.isSupported(), profile.reason());
        assertTrue(profile.fragmentWriter().contains("sqrt(length(viewPos) / max(ToonFar, 1.0))"));
        assertFalse(ToonOutputProfile.resolve("ComplementaryUnbound_r5.9.3", "gbuffers_entities_translucent",
                source, new int[]{0, 3, 6, 13, 4}).isSupported());
        assertFalse(ToonOutputProfile.resolve("ComplementaryUnbound_r5.9.3", "gbuffers_entities_translucent",
                source.replace("sqrt(lViewPos / far)", "lViewPos / far"), new int[]{0, 3, 6, 4, 13}).isSupported());
    }

    @Test
    void changesCacheVariantWhenTranslucencyChangesWithTheSameLayout() {
        String translucent = MODERN_CONTRACT.replace("vec3 translucentMult = vec3(1.0)",
                "vec3 translucentMult = mix(vec3(0.666), color.rgb * (1.0 - pow2(pow2(color.a))), color.a)");
        var opaque = ToonOutputProfile.resolve("ComplementaryUnbound_r5.9.3", "gbuffers_entities", MODERN_CONTRACT, new int[]{0, 3, 6, 4});
        var alpha = ToonOutputProfile.resolve("ComplementaryUnbound_r5.9.3", "gbuffers_entities", translucent, new int[]{0, 3, 6, 4});
        assertTrue(alpha.isSupported(), alpha.reason());
        assertNotEquals(opaque.id(), alpha.id());
        assertTrue(alpha.fragmentWriter().contains("pow(alpha, 4.0)"));
    }

    @Test
    void matchesSourceContractsRatherThanFilenameVersionNumbers() {
        var profile = ToonOutputProfile.resolve("BSL_renamed.zip", "gbuffers_hand", BSL_COLOR + "/* DRAWBUFFERS:0 */", new int[]{0});
        assertTrue(profile.isSupported(), profile.reason());
        assertFalse(ToonOutputProfile.resolve("BSL_v10.1.8", "gbuffers_hand",
                BSL_COLOR.replace("pow(albedo.rgb, vec3(2.2))", "albedo.rgb"), new int[]{0}).isSupported());
        assertFalse(ToonOutputProfile.resolve("ComplementaryUnbound_r5.9.3", "gbuffers_entities",
                MODERN_CONTRACT.replace("materialMask = OSIEBCA * 254.0", "materialMask = 1.0"), new int[]{0, 3, 6, 4}).isSupported());
        assertFalse(ToonOutputProfile.resolve("ComplementaryUnbound_r5.9.3", "gbuffers_entities",
                MODERN_CONTRACT.replace("vec4(mat3(gbufferModelViewInverse) * normalM, 1.0)", "mystery"), new int[]{0, 3, 6, 4}).isSupported());
    }

    @Test
    void ignoresMissingMisleadingAndUnrelatedPackNames() {
        String bsl = """
                void GetLighting(inout vec3 a, out vec3 s, vec3 p, vec3 w, vec3 n, vec2 lm,
                                 float sl, float nl, float vd, float ps, float e, float ss, float bs) {}
                """ + BSL_COLOR + "/* DRAWBUFFERS:0 */";
        for (String name : new String[]{null, "", "renamed.zip", "Complementary_v4.7.1.zip", "BSL_v10.1.8.zip"}) {
            var modern = ToonOutputProfile.resolve(name, "gbuffers_entities", MODERN_CONTRACT, new int[]{0, 3, 6, 4});
            assertTrue(modern.isSupported(), modern.reason());
            assertTrue(modern.id().startsWith("complementary_modern:"));
            var other = ToonOutputProfile.resolve(name, "gbuffers_entities", bsl, "void main() {}", new int[]{0});
            assertTrue(other.isSupported(), other.reason());
            assertTrue(other.id().startsWith("bsl:"));
        }
        String unverified = BSL_COLOR + "/* DRAWBUFFERS:0 */";
        var generic = ToonOutputProfile.resolve("other-pack.zip", "gbuffers_entities", unverified,
                "void main() {}", new int[]{0});
        assertTrue(generic.isSupported());
        assertTrue(generic.lightingSource().isEmpty());
    }

    @Test
    void handlesBslEntityMaskAndRejectsChangedMaskSemantics() {
        String source = BSL_COLOR + """
                float entityMask = (color.a < 0.999 || entityId == 10104) ? 0.0 : 0.25;
                /* DRAWBUFFERS:03 */
                gl_FragData[1] = vec4(0.0, 0.0, entityMask, 1.0);
                """;
        var profile = ToonOutputProfile.resolve("BSL_v10.1.8", "gbuffers_entities", source, new int[]{0, 3});
        assertTrue(profile.isSupported(), profile.reason());
        assertTrue(profile.fragmentWriter().contains("alpha < 0.999 ? 0.0 : 0.25"));
        assertFalse(ToonOutputProfile.resolve("BSL_v10.1.8", "gbuffers_entities",
                source.replace("? 0.0 : 0.25", "? 0.5 : 1.0"), new int[]{0, 3}).isSupported());
    }

    @Test
    void retainsBslVolumetricAlbedoAndGlowingMaskForBothColorEncodings() {
        String source = BSL_COLOR + """
                vlAlbedo = mix(vec3(1.0), albedo.rgb, sqrt(albedo.a)) * (1.0 - pow(albedo.a, 64.0));
                /* DRAWBUFFERS:013 */
                gl_FragData[1] = vec4(vlAlbedo, 1.0);
                gl_FragData[2] = vec4(0.0, 0.0, 1.0, 1.0);
                """;
        var sqrt = ToonOutputProfile.resolve("BSL_v10.1.8", "gbuffers_entities_glowing", source, new int[]{0, 1, 3});
        var linear = ToonOutputProfile.resolve("BSL_v10.1.8", "gbuffers_entities_glowing",
                source.replace("albedo.rgb = sqrt(max(albedo.rgb, vec3(0.0)));", ""), new int[]{0, 1, 3});
        assertTrue(sqrt.isSupported(), sqrt.reason());
        assertTrue(linear.isSupported(), linear.reason());
        assertNotEquals(sqrt.id(), linear.id());
        assertTrue(sqrt.fragmentWriter().contains("pow(max(rawAlbedo"));
        assertTrue(sqrt.fragmentWriter().contains("toonOutput2 = vec4(0.0, 0.0, 1.0, 1.0)"));
    }

    @Test
    void acceptsKnownPbrOverwriteButRejectsUnknownOverwrite() {
        String source = BSL_COLOR + """
                float smoothness = 0.0; float skyOcclusion = 0.0; vec3 fresnel3 = vec3(0.0);
                /* DRAWBUFFERS:03 */
                gl_FragData[1] = vec4(0.0, 0.0, 1.0, 1.0);
                gl_FragData[1] = vec4(smoothness, skyOcclusion, 1.0, 1.0);
                """;
        assertTrue(ToonOutputProfile.resolve("BSL_v10.1.8", "gbuffers_entities_glowing", source, new int[]{0, 3}).isSupported());
        assertFalse(ToonOutputProfile.resolve("BSL_v10.1.8", "gbuffers_entities_glowing",
                source + "gl_FragData[1] = unknown;", new int[]{0, 3}).isSupported());
    }

    @Test
    void preservesVerifiedOutputWhenNativeLightingCannotBeExtracted() {
        var profile = ToonOutputProfile.resolve("ComplementaryUnbound_r5.9.3", "gbuffers_entities",
                MODERN_CONTRACT, "void main() {}", new int[]{0, 3, 6, 4});
        assertTrue(profile.isSupported(), profile.reason());
        assertTrue(profile.lightingSource().isEmpty());
        assertTrue(profile.fragmentWriter().contains("254.0 / 255.0"));
    }
}
