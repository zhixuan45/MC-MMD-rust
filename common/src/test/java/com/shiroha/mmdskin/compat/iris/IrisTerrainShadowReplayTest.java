package com.shiroha.mmdskin.compat.iris;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IrisTerrainShadowReplayTest {
    private static final String BSL_SHADOW = """
            void main() {
                albedo.rgb = mix(vec3(1.0), albedo.rgb, 1.0 - pow(1.0 - albedo.a, 1.5));
                albedo.rgb *= 1.0 - pow(albedo.a, 96.0);
                gl_FragData[0] = albedo;
            }
            """;

    // ComplementaryReimagined shaders/program/shadow.glsl:35-41.
    private static final String MODERN_FORMULA = """
            void DoNaturalShadowCalculation(inout vec4 color1, inout vec4 color2) {
                color1.rgb *= glColor.rgb;
                color1.rgb = mix(vec3(1.0), color1.rgb, pow(color1.a, (1.0 - color1.a) * 0.5) * 1.05);
                color1.rgb *= 1.0 - pow(color1.a, 64.0);
                color1.rgb *= 0.1;

                color2.rgb = normalize(color1.rgb) * 0.5;
            }
            """;

    @Test void acceptsVerifiedOneAndTwoTargetTransmissionContracts() {
        assertContract(BSL_SHADOW, 1);
        assertContract(modern(1), 1);
        assertContract(modern(2), 2);
        assertFalse(IrisTerrainShadowReplay.supports(BSL_SHADOW.replace("96.0", "unknownExponent"), ""));
        assertFalse(IrisTerrainShadowReplay.supports("void main() { gl_FragData[0] = albedo; }", ""));
    }

    @Test void rejectsUnknownRewritesThirdTargetsAndUnrestorableSideEffects() {
        assertContract(BSL_SHADOW + "gl_FragData[1] = albedo;", 0);
        assertContract(modern(2) + "gl_FragData[2] = color2;", 0);
        assertContract(modern(2) + "gl_FragData[1] = unknown;", 0);
        assertContract(modern(2).replace("*= 0.1", "*= 0.11"), 0);
        for (String extra : new String[]{
                "gl_FragDepth = 0.0;", "imageStore(data, pixel, albedo);", "atomicAdd(counter, 1);",
                "layout(std430) buffer State { uint count; };",
                "layout(location=1) out vec4 Other;",
                "uniform sampler2D shadowtex0;",
                "vec3 feedback = texture(shadowcolor0, uv).rgb;"}) {
            assertContract(BSL_SHADOW + extra, 0);
            assertContract(modern(2) + extra, 0);
            assertContract(BSL_SHADOW, extra, 0);
            assertContract(modern(2), extra, 0);
        }
        assertContract(BSL_SHADOW + "uniform sampler2D shadowcolor0;", 0);
    }

    @Test void acceptsUnusedSharedModernSamplersButRejectsFeedbackInEitherStage() {
        String declarations = """
                uniform sampler2D shadowcolor0;
                uniform sampler2D shadowcolor1;
                uniform sampler2DShadow shadowtex0;
                uniform sampler2DShadow shadowtex1;
                """;
        assertContract(declarations + modern(2), declarations + "void main() {}", 2);
        assertContract(declarations + modern(2) + "float f = shadow2D(shadowtex0, p).r;", 0);
        assertContract(declarations + modern(2), declarations + "void main() { texture(shadowcolor1, uv); }", 0);
    }

    @Test void commentsCannotProveFormulaOrAddAnotherColorTarget() {
        assertContract(modern(1) + "/* gl_FragData[1] = color2; */", 1);
        assertContract("/*" + modern(2) + "*/", 0);
        assertContract("void main() { /* " + MODERN_FORMULA + " */ gl_FragData[0] = color1; }", 0);
    }

    @Test void selectsTheBaseShadowProgramActuallyUsedByShadowRenderer() throws Exception {
        Object program = new Object();
        assertSame(program, IrisTerrainShadowReplay.shadowProgram(new ProgramSet(program)));
        assertNull(IrisTerrainShadowReplay.shadowProgram(new ProgramSet(null)));
    }

    private static String modern(int targets) {
        String output = "void main() { vec4 color1=vec4(1.0); vec4 color2=vec4(0.5);"
                + "gl_FragData[0] = color1;";
        if (targets == 2) output += "gl_FragData[1] = color2;";
        return MODERN_FORMULA + output + "}";
    }

    private static void assertContract(String fragment, int targets) {
        assertContract(fragment, "void main() {}", targets);
    }

    private static void assertContract(String fragment, String vertex, int targets) {
        assertEquals(targets, IrisTerrainShadowReplay.colorTargetCount(fragment, vertex), fragment);
        assertEquals(targets > 0, IrisTerrainShadowReplay.supports(fragment, vertex), fragment);
    }

    public static final class ProgramSet {
        private final Object shadow;

        ProgramSet(Object shadow) {
            this.shadow = shadow;
        }

        public Optional<Object> getShadow() {
            return Optional.ofNullable(shadow);
        }
    }
}
