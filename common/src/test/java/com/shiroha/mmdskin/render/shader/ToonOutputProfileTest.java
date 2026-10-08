package com.shiroha.mmdskin.render.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToonOutputProfileTest {
    private static final String ENCODER = "vec2 EncodeNormal(vec3 n) { float f = sqrt(n.z * 8.0 + 8.0); return n.xy / f + 0.5; }";

    @Test
    void shouldRejectCommentedColorContractAndUnmappedOutputs() {
        String source = "albedo = texture2D(texture, texCoord);\n"
                + "// albedo.rgb = pow(albedo.rgb, vec3(2.2));\n"
                + "/* DRAWBUFFERS:0 */\n gl_FragData[0] = albedo;";
        assertFalse(ToonOutputProfile.resolve("BSL_v8.4.02.2", "gbuffers_entities", source,
                new int[]{0}).isSupported());
        source = source.replace("// albedo.rgb", "albedo.rgb") + "\n gl_FragData[4] = albedo;";
        assertFalse(ToonOutputProfile.resolve("BSL_v8.4.02.2", "gbuffers_entities", source,
                new int[]{0}).isSupported());
    }

    @Test
    void shouldAcceptKnownComplementarySemanticReorder() {
        String source = "float smoothness = 0.0, metalData = 0.0, metalness = 0.0, f0 = 0.0, skymapMod = 0.0;\n"
                + "vec3 rawAlbedo = vec3(0.0); albedo.rgb = pow(albedo.rgb, vec3(2.2));\n" + ENCODER + "\n"
                + "/* DRAWBUFFERS:073 */\n"
                + "gl_FragData[0] = albedo;\n gl_FragData[1] = vec4(1.0);\n"
                + "gl_FragData[2] = vec4(smoothness, metalData, skymapMod, 1.0);\n";

        ToonOutputProfile profile = ToonOutputProfile.resolve("ComplementaryShaders_v4.7.1", "gbuffers_entities", source,
                new int[]{0, 7, 3});

        assertTrue(profile.isSupported(), profile.reason());
        assertTrue(profile.id().contains("entities:linear:073"));
        assertTrue(profile.fragmentDeclarations().contains("location = 2"));
        assertTrue(profile.fragmentWriter().contains("toonOutput1 = vec4(1.0)"));
        assertTrue(profile.fragmentWriter().contains("toonOutput2 = vec4(0.0, 0.0, 0.0, 1.0)"));
        assertTrue(profile.fragmentWriter().contains("pow(max(color"));
    }

    @Test
    void shouldMapColorWhenColortexZeroMovesToAnotherOutputLocation() {
        String source = "float smoothness = 0.0, metalData = 0.0, skymapMod = 0.0;\n"
                + "albedo.rgb = pow(albedo.rgb, vec3(2.2));\n/* DRAWBUFFERS:730 */\n"
                + "gl_FragData[0] = vec4(1.0); gl_FragData[1] = vec4(smoothness, metalData, skymapMod, 1.0);\n"
                + "gl_FragData[2] = albedo;";
        ToonOutputProfile profile = ToonOutputProfile.resolve("ComplementaryShaders_v4.7.1", "gbuffers_entities", source,
                new int[]{7, 3, 0});
        assertTrue(profile.isSupported(), profile.reason());
        assertTrue(profile.fragmentWriter().contains("toonOutput2 = vec4(pow(max(color"));
    }

    @Test
    void shouldEmitComplementaryRawAlbedoAndSpheremapNormalContract() {
        String source = "float smoothness = 0.0, metalData = 0.0, metalness = 0.0, f0 = 0.0, skymapMod = 0.0;\n"
                + "vec3 rawAlbedo = vec3(0.0); albedo.rgb = pow(albedo.rgb, vec3(2.2));\n"
                + "rawAlbedo = albedo.rgb * 0.999 + 0.001;\n" + ENCODER + "\n"
                + "/* DRAWBUFFERS:03761 */\n gl_FragData[0] = albedo;\n"
                + "gl_FragData[1] = vec4(smoothness, metalData, skymapMod, 1.0);\n"
                + "gl_FragData[2] = vec4(1.0);\n"
                + "gl_FragData[3] = vec4(EncodeNormal(newNormal), 0.0, 1.0);\n"
                + "gl_FragData[4] = vec4(rawAlbedo, 1.0);\n";

        ToonOutputProfile profile = ToonOutputProfile.resolve("ComplementaryShaders_v4.7.1", "gbuffers_entities", source,
                new int[]{0, 3, 7, 6, 1});

        assertTrue(profile.isSupported(), profile.reason());
        assertTrue(profile.fragmentWriter().contains("encodeComplementaryNormal(normal)"));
        assertTrue(profile.fragmentWriter().contains("if (f < 1e-6) return vec2(1.0, 0.5)"));
        assertTrue(profile.fragmentWriter().contains("pow(max(rawAlbedo, vec3(0.0)), vec3(2.2)) * 0.999 + 0.001"));
    }

    @Test
    void shouldPreserveBslLinearSqrtEncodingAndReflectionNeutralValues() {
        String source = "float smoothness = 0.0; float skyOcclusion = 0.0; vec3 fresnel3 = vec3(0.0);\n"
                + ENCODER + "\n albedo.rgb = pow(albedo.rgb, vec3(2.2));\n"
                + "albedo.rgb = sqrt(max(albedo.rgb, vec3(0.0)));\n"
                + "/* DRAWBUFFERS:0367 */\n gl_FragData[0] = albedo;\n"
                + "gl_FragData[1] = vec4(smoothness, skyOcclusion, 0.25, 1.0);\n"
                + "gl_FragData[2] = vec4(EncodeNormal(newNormal), float(gl_FragCoord.z < 1.0), 1.0);\n"
                + "gl_FragData[3] = vec4(fresnel3, 1.0);\n";

        ToonOutputProfile profile = ToonOutputProfile.resolve("BSL_v8.4.02.2", "gbuffers_entities", source,
                new int[]{0, 3, 6, 7});

        assertTrue(profile.isSupported(), profile.reason());
        assertTrue(profile.id().contains("sqrt-linear:0367"));
        assertTrue(profile.fragmentWriter().contains("sqrt(pow(max(color"));
        assertTrue(profile.fragmentWriter().contains("vec4(0.0, 0.0, 0.25, 1.0)"));
        assertTrue(profile.fragmentWriter().contains("float(gl_FragCoord.z < 1.0)"));
        assertTrue(profile.fragmentWriter().contains("toonOutput3 = vec4(0.0, 0.0, 0.0, 1.0)"));
    }

    @Test
    void shouldRejectUnknownOrIncompleteLayouts() {
        String unknown = "/* DRAWBUFFERS:09 */ gl_FragData[0] = albedo; gl_FragData[1] = mystery;";
        assertFalse(ToonOutputProfile.resolve("OtherPack", "gbuffers_entities", unknown,
                new int[]{0, 9}).isSupported());

        String source = "float smoothness = 0.0, metalData = 0.0, skymapMod = 0.0; " + ENCODER
                + " /* DRAWBUFFERS:037 */ gl_FragData[0] = albedo; "
                + "gl_FragData[1] = vec4(smoothness, metalData, skymapMod, 1.0); gl_FragData[2] = vec4(1.0);";
        assertFalse(ToonOutputProfile.resolve("ComplementaryShaders_v4.7.1", "gbuffers_entities", source,
                new int[]{3, 7}).isSupported());
        assertFalse(ToonOutputProfile.resolve("ComplementaryShaders_v4.7.1", "gbuffers_entities",
                source.replace("DRAWBUFFERS:037", "DRAWBUFFERS:033"), new int[]{0, 3, 3}).isSupported());
        assertFalse(ToonOutputProfile.resolve("ComplementaryShaders_v4.7.1", "gbuffers_entities", source,
                new int[]{0, 3, 9}).isSupported());
        assertFalse(ToonOutputProfile.resolve("ComplementaryShaders_v4.7.1", "gbuffers_entities",
                source.replace("pow(albedo.rgb, vec3(2.2))", "albedo.rgb"), new int[]{0, 3, 7}).isSupported());
        assertFalse(ToonOutputProfile.resolve("ComplementaryShaders_v4.7.1", "gbuffers_entities",
                source + " /* DRAWBUFFERS:03761 */", new int[]{0, 3, 7}).isSupported());
        String advanced = "float smoothness = 0.0, metalData = 0.0, skymapMod = 0.0; "
                + "vec3 rawAlbedo = vec3(0.0); albedo.rgb = pow(albedo.rgb, vec3(2.2)); "
                + "rawAlbedo = albedo.rgb * 0.999 + 0.001; " + ENCODER + " /* DRAWBUFFERS:03761 */ "
                + "gl_FragData[0] = albedo; gl_FragData[1] = vec4(smoothness, metalData, skymapMod, 1.0); "
                + "gl_FragData[2] = vec4(1.0); gl_FragData[3] = vec4(EncodeNormal(newNormal), 0.5, 1.0); "
                + "gl_FragData[4] = vec4(rawAlbedo, 1.0);";
        assertFalse(ToonOutputProfile.resolve("ComplementaryShaders_v4.7.1", "gbuffers_entities", advanced,
                new int[]{0, 3, 7, 6, 1}).isSupported());
    }

    @Test
    void shouldKeepVanillaWriterForFallback() {
        ToonOutputProfile profile = ToonOutputProfile.vanilla();
        assertTrue(profile.isSupported());
        assertTrue(profile.fragmentDeclarations().contains("location = 3"));
        assertTrue(profile.fragmentWriter().contains("normal * 0.5 + 0.5"));
    }
}
