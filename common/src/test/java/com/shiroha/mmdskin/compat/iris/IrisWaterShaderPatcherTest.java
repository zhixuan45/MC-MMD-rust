package com.shiroha.mmdskin.compat.iris;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IrisWaterShaderPatcherTest {
    private static final String CONTRACT = """
            void main() {
                vlAlbedo = mix(vec3(1.0), vlAlbedo, sqrt(albedo.a)) * (1.0 - pow(albedo.a, 64.0));
                GetWaterFog(viewPos); albedo.a *= cloudBlendOpacity;
                gl_FragData[0] = albedo; gl_FragData[1] = vec4(vlAlbedo, 1.0);
            }
            """;
    private static final String MODERN_CONTRACT = """
            void main() {
                vec4 color = texture2D(tex, texCoord) * vec4(glColor.rgb, 1.0);
                vec3 normalM = VdotN > 0.0 ? -normal : normal;
                float fresnelM = pow3(fresnel);
                vec4 translucentMult = vec4(1.0);
                float materialMask = 0.0;
                translucentMult = vec4(mix(vec3(0.666), color.rgb * (1.0 - pow2(pow2(color.a))), color.a), 1.0);
                translucentMult.rgb = mix(translucentMult.rgb, vec3(1.0), min1(pow2(pow2(lViewPos / far))));
                DoLighting(color, shadowMult, playerPos, viewPos, lViewPos, geoNormal, normalM, dither,
                           worldGeoNormal, lmCoordM, noSmoothLighting, noDirectionalShading, false,
                           false, subsurfaceMode, smoothnessG, highlightMult, emission);
                float skyLightFactor = GetSkyLightFactor(lmCoordM, shadowMult);
                vec4 reflection = GetReflection(normalM, viewPos.xyz, nViewPos, playerPos, lViewPos, -1.0,
                                                depthtex1, dither, skyLightFactor, fresnel, smoothnessG,
                                                geoNormal, color.rgb, shadowMult, highlightMult);
                float prevAlpha = color.a;
                color.a = 1.0;
                DoFog(color, skyFade, lViewPos, playerPos, VdotU, VdotS, dither, false, 0.0);
                float fogAlpha = color.a;
                color.a = prevAlpha * (1.0 - skyFade);
                if (color.a < 0.001) discard;
            """;
    private static final String FRAGMENT = """
            #version 330 core
            #extension GL_ARB_shader_texture_lod : enable
            layout(location = 1) out vec4 iris_FragData1;
            layout(location = 0) out vec4 iris_FragData0;
            void main() {
                iris_FragData0 = vec4(.2); iris_FragData1 = vec4(.75);
                if (iris_FragData0.a < .001) discard;
            }
            """;

    @Test void copiesCachedSourcesAndKeepsNativeDiscardAndAuxiliaryOutputs() {
        var original = Map.of("VERTEX", "vertex", "FRAGMENT", FRAGMENT);
        var result = IrisWaterShaderPatcher.patch("gbuffers_water", CONTRACT, original);
        assertNotSame(original, result);
        assertEquals(FRAGMENT, original.get("FRAGMENT"));
        String source = result.get("FRAGMENT");
        assertTrue(source.startsWith("#version 430 core"));
        assertTrue(source.contains("#extension GL_ARB_shader_texture_lod : enable"));
        assertTrue(source.contains("if (iris_FragData0.a < .001) discard;"));
        assertTrue(source.contains("iris_FragData1 = vec4(.75);"));
        assertTrue(source.contains("if (MmdWaterCapture) { mmdStore(iris_FragData0)"));
        assertFalse(source.contains("atomicAdd(mmdReserved0"));
        assertFalse(source.contains("atomicOr(mmdReserved0"));
        assertFalse(source.contains("early_fragment_tests"));
        assertFalse(source.contains("gl_FragDepth"));
    }

    @Test void acceptsEveryVerifiedModernLayoutAndPreservesAllMrtOutputs() {
        for (String layout : new String[]{"03", "036", "0348", "03648"}) {
            String contract = modernContract(layout);
            int outputs = layout.length();
            var original = transformed(outputs);
            var result = IrisWaterShaderPatcher.patch("gbuffers_water", contract, original);

            assertNotSame(original, result, layout);
            assertTrue(IrisWaterShaderPatcher.supports("gbuffers_water", contract), layout);
            String source = result.get("FRAGMENT");
            assertTrue(source.startsWith("#version 430 core"), layout);
            assertTrue(source.contains("if (MmdWaterCapture) { mmdStore(iris_FragData0)"), layout);
            assertTrue(source.contains("if (!(iris_FragData0.a > iris_currentAlphaTest)) discard;"), layout);
            for (int slot = 0; slot < outputs; slot++) {
                assertTrue(source.contains("iris_FragData" + slot + " = vec4(float(" + slot + "));"), layout);
            }
            assertFalse(source.contains("gl_FragDepth"), layout);
            assertFalse(source.contains("imageStore("), layout);
        }
    }

    @Test void usesLastDirectiveFromRealPreprocessedWaterBranch() {
        String contract = MODERN_CONTRACT + """
                /* DRAWBUFFERS:03 */
                gl_FragData[0] = color;
                gl_FragData[1] = vec4(1.0 - translucentMult.rgb, translucentMult.a);
                /* DRAWBUFFERS:036 */
                gl_FragData[2] = vec4(1.0, materialMask, skyLightFactor, 1.0);
                /* DRAWBUFFERS:03648 */
                gl_FragData[3] = vec4(mat3(gbufferModelViewInverse) * normalM, sqrt(fresnelM * color.a * fogAlpha));
                gl_FragData[4] = vec4(reflection.rgb * fresnelM * color.a * fogAlpha, reflection.a);
                }
                """;

        assertTrue(IrisWaterShaderPatcher.supports("gbuffers_water", contract));
        var original = transformed(5);
        assertNotSame(original, IrisWaterShaderPatcher.patch("gbuffers_water", contract, original));
    }

    @Test void unrelatedProgramsAndUnknownContractsRemainUntouched() {
        var original = Map.of("FRAGMENT", FRAGMENT);
        assertSame(original, IrisWaterShaderPatcher.patch("gbuffers_entities", CONTRACT, original));
        assertSame(original, IrisWaterShaderPatcher.patch("gbuffers_water", "unknown water", original));
    }

    @Test void rejectsUnknownConflictingAndExtraLayoutsOrOutputs() {
        Map<String, String> original = transformed(2);
        String[] rejected = {
                modernContract("034"),
                modernContract("03") + "gl_FragData[1] = vec4(0.0);\n",
                modernContract("036") + "gl_FragData[3] = vec4(0.0);\n",
                modernContract("0348").replace(
                        "gl_FragData[2] = vec4(mat3(gbufferModelViewInverse) * normalM, sqrt(fresnelM * color.a * fogAlpha));",
                        "gl_FragData[2] = vec4(0.0);"),
                modernContract("03648").replace("pow2(pow2(color.a))", "color.a"),
                modernContract("03") + "/* DRAWBUFFERS:038 */\n"
        };
        for (String source : rejected) {
            assertFalse(IrisWaterShaderPatcher.supports("gbuffers_water", source));
            assertSame(original, IrisWaterShaderPatcher.patch("gbuffers_water", source, original));
        }

        String accepted = modernContract("03648");
        Map<String, String> mismatched = transformed(4);
        assertSame(mismatched, IrisWaterShaderPatcher.patch("gbuffers_water", accepted, mismatched));
    }

    @Test void rejectsDepthImageAndStorageSideEffects() {
        Map<String, String> original = transformed(2);
        String[] rejected = {
                modernContract("03").replace("discard;", "discard; gl_FragDepth = gl_FragCoord.z;"),
                modernContract("03").replace("void main()",
                        "layout(rgba16f) uniform image2D unsafeImage;\nvoid main()"),
                modernContract("03").replace("void main()",
                        "layout(std430) buffer UnsafeBuffer { vec4 values[]; };\nvoid main()")
        };
        for (String source : rejected) {
            assertFalse(IrisWaterShaderPatcher.supports("gbuffers_water", source));
            assertSame(original, IrisWaterShaderPatcher.patch("gbuffers_water", source, original));
        }
    }

    @Test void rejectsUnverifiedStagesDepthWritesAndMissingPrimaryOutput() {
        var geometry = Map.of("FRAGMENT", FRAGMENT, "GEOMETRY", "geometry");
        assertSame(geometry, IrisWaterShaderPatcher.patch("gbuffers_water", CONTRACT, geometry));
        assertNull(IrisWaterShaderPatcher.patchFragment(FRAGMENT.replace("discard;", "gl_FragDepth=.2;")));
        assertNull(IrisWaterShaderPatcher.patchFragment("layout(early_fragment_tests) in;\n" + FRAGMENT));
        assertNull(IrisWaterShaderPatcher.patchFragment(FRAGMENT.replace("location = 0", "location = 2")));
    }

    @Test void directInstrumentationRetainsVerifiedFixturesWithArbitraryOutputNames() {
        String fragment = FRAGMENT.replace("iris_FragData0", "primaryColor")
                .replace("iris_FragData1", "auxiliaryColor");
        String patched = IrisWaterShaderPatcher.patchFragment(fragment);
        assertNotNull(patched);
        assertTrue(patched.contains("mmdStore(primaryColor)"));
        assertTrue(patched.contains("auxiliaryColor = vec4(.75);"));
        var transformed = Map.of("FRAGMENT", fragment);
        assertSame(transformed, IrisWaterShaderPatcher.patch("gbuffers_water", modernContract("03"), transformed));
    }

    @Test void refusesDoubleInstrumentationAndHandlesVoidMain() {
        String patched = IrisWaterShaderPatcher.patchFragment(FRAGMENT.replace("main()", "main(void)"));
        assertNotNull(patched);
        assertNull(IrisWaterShaderPatcher.patchFragment(patched));
    }

    @Test void refusesDoubleInstrumentationForModernLayout() {
        var result = IrisWaterShaderPatcher.patch("gbuffers_water", modernContract("0348"), transformed(4));
        String patched = result.get("FRAGMENT");
        assertNotNull(patched);
        assertNull(IrisWaterShaderPatcher.patchFragment(patched));
    }

    private static String modernContract(String layout) {
        String tails = switch (layout) {
            case "03" -> """
                    /* DRAWBUFFERS:03 */
                    gl_FragData[0] = color;
                    gl_FragData[1] = vec4(1.0 - translucentMult.rgb, translucentMult.a);
                    """;
            case "036" -> """
                    /* DRAWBUFFERS:036 */
                    gl_FragData[0] = color;
                    gl_FragData[1] = vec4(1.0 - translucentMult.rgb, translucentMult.a);
                    gl_FragData[2] = vec4(1.0, materialMask, skyLightFactor, 1.0);
                    """;
            case "0348" -> """
                    /* DRAWBUFFERS:0348 */
                    gl_FragData[0] = color;
                    gl_FragData[1] = vec4(1.0 - translucentMult.rgb, translucentMult.a);
                    gl_FragData[2] = vec4(mat3(gbufferModelViewInverse) * normalM, sqrt(fresnelM * color.a * fogAlpha));
                    gl_FragData[3] = vec4(reflection.rgb * fresnelM * color.a * fogAlpha, reflection.a);
                    """;
            case "03648" -> """
                    /* DRAWBUFFERS:03648 */
                    gl_FragData[0] = color;
                    gl_FragData[1] = vec4(1.0 - translucentMult.rgb, translucentMult.a);
                    gl_FragData[2] = vec4(1.0, materialMask, skyLightFactor, 1.0);
                    gl_FragData[3] = vec4(mat3(gbufferModelViewInverse) * normalM, sqrt(fresnelM * color.a * fogAlpha));
                    gl_FragData[4] = vec4(reflection.rgb * fresnelM * color.a * fogAlpha, reflection.a);
                    """;
            default -> "/* DRAWBUFFERS:" + layout + " */\n"
                    + "gl_FragData[0] = color;\n";
        };
        return MODERN_CONTRACT + tails + "}\n";
    }

    private static Map<String, String> transformed(int outputs) {
        Map<String, String> result = new HashMap<>();
        result.put("VERTEX", "vertex");
        StringBuilder source = new StringBuilder("#version 330 core\n");
        for (int slot = outputs - 1; slot >= 0; slot--) {
            source.append("layout(location = ").append(slot).append(") out vec4 iris_FragData")
                    .append(slot).append(";\n");
        }
        source.append("uniform float iris_currentAlphaTest;\nvoid main() {\n");
        for (int slot = 0; slot < outputs; slot++) {
            source.append("iris_FragData").append(slot).append(" = vec4(float(")
                    .append(slot).append("));\n");
        }
        source.append("if (!(iris_FragData0.a > iris_currentAlphaTest)) discard;\n}\n");
        result.put("FRAGMENT", source.toString());
        return result;
    }
}
