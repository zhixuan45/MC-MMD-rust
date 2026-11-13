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
    private static final String MODERN_LEGACY_CONTRACT = """
            const float OSIEBCA = 1.0 / 255.0;
            void DoLighting(inout vec4 color, inout vec3 shadowMult, vec3 playerPos, vec3 viewPos,
                    float lViewPos, vec3 normalM, vec2 lightmap, bool noSmoothLighting,
                    bool noDirectionalShading, bool noVanillaAO, bool centerShadowBias,
                    int subsurfaceMode, float smoothnessG, float highlightMult, float emission) {
                vec3 finalDiffuse = vec3(1.0);
                finalDiffuse = sqrt(max(finalDiffuse, vec3(0.0)));
                color.rgb *= finalDiffuse;
            }
            float smoothnessD = 0.0, skyLightFactor = 0.0, materialMask = OSIEBCA * 254.0;
            vec3 normalM = normal;
            vec3 lightAlbedo = vec3(0.0);
            /* DRAWBUFFERS:06 */
            gl_FragData[0] = color;
            gl_FragData[1] = vec4(smoothnessD, materialMask, skyLightFactor, 1.0);
            skyLightFactor = pow2(max(lmCoord.y - 0.7, 0.0) * 3.33333);
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
    void acceptsVerifiedR511Colortex6ProtocolForEveryLegacyLayout() {
        for (String program : new String[]{
                "gbuffers_entities", "gbuffers_entities_translucent",
                "gbuffers_entities_glowing", "gbuffers_hand"}) {
            var base = ToonOutputProfile.resolve("renamed.zip", program, MODERN_LEGACY_CONTRACT, new int[]{0, 6});
            assertTrue(base.isSupported(), base.reason());
            assertTrue(base.id().startsWith("complementary_modern:"));
            assertTrue(base.id().contains(":protocol-r511"));
            assertTrue(base.fragmentWriter().contains("254.0 / 255.0"));

            String withNormal = MODERN_LEGACY_CONTRACT + """
                    /* DRAWBUFFERS:065 */
                    gl_FragData[2] = vec4(mat3(gbufferModelViewInverse) * normalM, 1.0);
                    """;
            var normal = ToonOutputProfile.resolve("renamed.zip", program, withNormal, new int[]{0, 6, 5});
            assertTrue(normal.isSupported(), normal.reason());
            assertTrue(normal.fragmentWriter().contains("mat3(ToonViewToWorld) * normal"));

            String withColoredLight = MODERN_LEGACY_CONTRACT + """
                    /* DRAWBUFFERS:068 */
                    gl_FragData[2] = vec4(lightAlbedo, 1.0);
                    lightAlbedo = normalize(color.rgb) * min1(emission);
                    """;
            var colored = ToonOutputProfile.resolve("renamed.zip", "gbuffers_hand",
                    withColoredLight, new int[]{0, 6, 8});
            assertTrue(colored.isSupported(), colored.reason());
            assertTrue(colored.fragmentWriter().contains("toonOutput2 = vec4(0.0, 0.0, 0.0, 1.0)"));

            String complete = MODERN_LEGACY_CONTRACT + """
                    /* DRAWBUFFERS:0658 */
                    gl_FragData[2] = vec4(mat3(gbufferModelViewInverse) * normalM, 1.0);
                    gl_FragData[3] = vec4(lightAlbedo, 1.0);
                    lightAlbedo = normalize(color.rgb) * min1(emission);
                    """;
            var profile = ToonOutputProfile.resolve("renamed.zip", "gbuffers_hand", complete, new int[]{0, 6, 5, 8});
            assertTrue(profile.isSupported(), profile.reason());
        }
    }

    @Test
    void rejectsUnverifiedR511Colortex6Variants() {
        for (String changed : new String[]{
                MODERN_LEGACY_CONTRACT.replace("skyLightFactor = 0.0", "skyLightFactor = 1.0"),
                MODERN_LEGACY_CONTRACT.replace(
                        "vec4(smoothnessD, materialMask, skyLightFactor, 1.0)",
                        "vec4(smoothnessD, unknownMask, skyLightFactor, 1.0)"),
                MODERN_LEGACY_CONTRACT.replace(
                        "int subsurfaceMode, float smoothnessG",
                        "int subsurfaceMode, float extra, float smoothnessG")}) {
            assertFalse(ToonOutputProfile.resolve("renamed.zip", "gbuffers_entities",
                    changed, new int[]{0, 6}).isSupported());
        }
        String changedNormal = MODERN_LEGACY_CONTRACT + """
                /* DRAWBUFFERS:065 */
                gl_FragData[2] = vec4(mysteryNormal, 1.0);
                """;
        assertFalse(ToonOutputProfile.resolve("renamed.zip", "gbuffers_entities",
                changedNormal, new int[]{0, 6, 5}).isSupported());
        String unverifiedColoredLight = MODERN_LEGACY_CONTRACT + """
                /* DRAWBUFFERS:068 */
                gl_FragData[2] = vec4(lightAlbedo, 1.0);
                """;
        assertFalse(ToonOutputProfile.resolve("renamed.zip", "gbuffers_hand",
                unverifiedColoredLight, new int[]{0, 6, 8}).isSupported());
        assertFalse(ToonOutputProfile.resolve("renamed.zip", "gbuffers_entities",
                MODERN_LEGACY_CONTRACT + "skyLightFactor = unknownSkyFactor;", new int[]{0, 6}).isSupported());
    }

    @Test
    void acceptsR511ZeroOnlySkyLightPathButRejectsUnknownAssignments() {
        String zeroOnly = MODERN_LEGACY_CONTRACT.replace(
                "skyLightFactor = pow2(max(lmCoord.y - 0.7, 0.0) * 3.33333);", "");
        var profile = ToonOutputProfile.resolve("renamed.zip", "gbuffers_entities",
                zeroOnly, new int[]{0, 6});
        assertTrue(profile.isSupported(), profile.reason());
        assertTrue(profile.id().contains(":protocol-r511"));
        assertFalse(ToonOutputProfile.resolve("renamed.zip", "gbuffers_entities",
                zeroOnly + "skyLightFactor = unknownSkyFactor;", new int[]{0, 6}).isSupported());
        assertFalse(ToonOutputProfile.resolve("renamed.zip", "gbuffers_entities",
                MODERN_LEGACY_CONTRACT
                        + "skyLightFactor = dot(shadowMult, shadowMult) / 3.0;\n"
                        + "skyLightFactor = unknownSkyFactor;", new int[]{0, 6}).isSupported());
        assertFalse(ToonOutputProfile.resolve("renamed.zip", "gbuffers_entities",
                zeroOnly + "skyLightFactor += unknownSkyFactor;", new int[]{0, 6}).isSupported());
        assertFalse(ToonOutputProfile.resolve("renamed.zip", "gbuffers_entities",
                zeroOnly + "skyLightFactor++;", new int[]{0, 6}).isSupported());
        assertFalse(ToonOutputProfile.resolve("renamed.zip", "gbuffers_entities",
                zeroOnly + "++skyLightFactor;", new int[]{0, 6}).isSupported());
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

    @Test
    void acceptsEuphoriaR593Patch1105EntitiesHandReflectionDepthAndSsblLayouts() {
        String vertex = """
                void main() {
                    sunVec = normalize(vec3(0.0, 1.0, 0.0));
                }
                """;
        String base = """
                const float OSIEBCA = 1.0 / 255.0;
                vec2 texCoord;
                vec3 ViewToPlayer(vec3 p) { return p; }
                void DoLighting(inout vec4 color, inout vec3 shadowMult, vec3 playerPos, vec3 viewPos,
                        float lViewPos, vec3 geoNormal, vec3 normalM, float dither, vec3 worldGeoNormal,
                        vec2 lightmap, bool noSmoothLighting, bool noDirectionalShading, bool noVanillaAO,
                        bool centerShadowBias, int subsurfaceMode, float smoothnessG, float highlightMult,
                        float emission, inout float purkinjeOverwrite, bool isLightSource,
                        inout float enderDragonDead, vec3 metalHighlightTint) {
                    vec3 finalDiffuse = vec3(1.0);
                    finalDiffuse = sqrt(max(finalDiffuse, vec3(0.0)));
                    color.rgb *= finalDiffuse;
                }
                float purkinjeOverwrite = 0.0, emissionOld = 0.0;
                float smoothnessD = 0.0, enderDragonDead = 1.0, materialMask = OSIEBCA * 254.0, materialAO = 1.0;
                float skyLightFactor = GetSkyLightFactor(lmCoordM, shadowMult);
                vec3 translucentMult = vec3(1.0);
                vec3 lightAlbedo = vec3(0.0);
                float entitySSBLMask = 1.0;
                float reflectNormalAlpha = 1.0;
                reflectNormalAlpha = clamp(maxOf(rawAlbedoM), 0.02, 0.99) * 2.0 - 1.0;
                /* DRAWBUFFERS:03649 */
                gl_FragData[0] = color;
                gl_FragData[1] = vec4(1.0 - translucentMult, 1.0);
                gl_FragData[2] = vec4(smoothnessD, materialMask, skyLightFactor, lmCoord.x + clamp01(purkinjeOverwrite) + clamp01(emissionOld) + maxOf(entityColor));
                gl_FragData[3] = vec4(mat3(gbufferModelViewInverse) * normalM, reflectNormalAlpha);
                gl_FragData[4] = vec4(lightAlbedo, entitySSBLMask);
                void main() {}
                """;
        var entities = ToonOutputProfile.resolve("ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5",
                "gbuffers_entities", base, vertex, new int[]{0, 3, 6, 4, 9});
        assertTrue(entities.isSupported(), entities.reason());
        assertTrue(entities.fragmentWriter().contains("clamp(ToonLightmap.x, 0.0, 0.9)"));
        assertTrue(entities.fragmentWriter().contains("clamp(max(rawAlbedo.r, max(rawAlbedo.g, rawAlbedo.b)), 0.02, 0.99) * 2.0 - 1.0"));
        assertTrue(entities.fragmentWriter().contains("toonOutput4 = vec4(0.0, 0.0, 0.0, 1.0);"));
        assertFalse(entities.lightingSource().isEmpty());
        assertTrue(entities.lightingSource().contains("purkinjeOverwrite, false, enderDragonDead, vec3(1.0)"));

        String translucent = base
                .replace("reflectNormalAlpha = clamp(maxOf(rawAlbedoM), 0.02, 0.99) * 2.0 - 1.0;", "")
                .replace("DRAWBUFFERS:03649", "RENDERTARGETS: 0,3,6,4,13,9")
                .replace("gl_FragData[3] = vec4(mat3(gbufferModelViewInverse) * normalM, reflectNormalAlpha);",
                        "gl_FragData[3] = vec4(mat3(gbufferModelViewInverse) * normalM, 1.0);")
                .replace("gl_FragData[4] = vec4(lightAlbedo, entitySSBLMask);",
                        "float lViewPos = length(viewPos);\nfloat entityLinearDepth = sqrt(lViewPos / far);\ngl_FragData[4] = vec4(0.0, 1.0, 1.0, entityLinearDepth);\ngl_FragData[5] = vec4(lightAlbedo, entitySSBLMask);");
        var transProfile = ToonOutputProfile.resolve("ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5",
                "gbuffers_entities_translucent", translucent, vertex, new int[]{0, 3, 6, 4, 13, 9});
        assertTrue(transProfile.isSupported(), transProfile.reason());
        assertTrue(transProfile.fragmentWriter().contains("vec4(0.0, 1.0, 1.0, sqrt(length(viewPos) / max(ToonFar, 1.0)))"));

        String hand = base
                .replace("float entitySSBLMask = 1.0;", "float handSSBLMask = 0.0;\nfloat purkinjeData = 1.0;\nfloat albedoData = 1.0;")
                .replace("reflectNormalAlpha = clamp(maxOf(rawAlbedoM), 0.02, 0.99) * 2.0 - 1.0;",
                        "albedoData = clamp(maxOf(rawAlbedoM), 0.02, 0.99) * 2.0 - 1.0;")
                .replace("lmCoord.x + clamp01(purkinjeOverwrite) + clamp01(emissionOld) + maxOf(entityColor)",
                        "purkinjeData")
                .replace("reflectNormalAlpha", "albedoData")
                .replace("entitySSBLMask", "handSSBLMask");
        var handProfile = ToonOutputProfile.resolve("ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5",
                "gbuffers_hand", hand, vertex, new int[]{0, 3, 6, 4, 9});
        assertTrue(handProfile.isSupported(), handProfile.reason());
        assertTrue(handProfile.fragmentWriter().contains("toonOutput4 = vec4(0.0, 0.0, 0.0, 0.0);"));
    }
}
