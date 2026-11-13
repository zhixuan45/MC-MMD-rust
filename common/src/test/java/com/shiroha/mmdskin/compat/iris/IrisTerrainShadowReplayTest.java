package com.shiroha.mmdskin.compat.iris;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisTerrainShadowReplayTest {
    private static final String BSL_SHADOW = """
            void main() {
                albedo.rgb = mix(vec3(1.0), albedo.rgb, 1.0 - pow(1.0 - albedo.a, 1.5));
                albedo.rgb *= 1.0 - pow(albedo.a, 96.0);
                gl_FragData[0] = albedo;
            }
            """;

    private static final String MODERN_FORMULA = """
            void DoNaturalShadowCalculation(inout vec4 color1, inout vec4 color2) {
                color1.rgb *= glColor.rgb;
                color1.rgb = mix(vec3(1.0), color1.rgb, pow(color1.a, (1.0 - color1.a) * 0.5) * 1.05);
                color1.rgb *= 1.0 - pow(color1.a, 64.0);
                color1.rgb *= 0.1;

                color2.rgb = normalize(color1.rgb) * 0.5;
            }
            """;

    @Test
    void acceptsVerifiedOneAndTwoTargetTransmissionContracts() {
        assertContract(BSL_SHADOW, 1);
        assertContract(modern(1), 1);
        assertContract(modern(2), 2);
        assertContract(legacy(1), 1);
        assertContract(legacy(2), 2);
        assertFalse(IrisTerrainShadowReplay.supports(
                BSL_SHADOW.replace("96.0", "unknownExponent"), ""));
        assertFalse(IrisTerrainShadowReplay.supports(
                "void main() { gl_FragData[0] = albedo; }", ""));
    }

    @Test
    void rejectsUnknownRewritesThirdTargetsAndUnrestorableSideEffects() {
        assertContract(BSL_SHADOW + "gl_FragData[1] = albedo;", 0);
        assertContract(modern(2) + "gl_FragData[2] = color2;", 0);
        assertContract(modern(2) + "gl_FragData[1] = unknown;", 0);
        assertContract(modern(2).replace("*= 0.1", "*= 0.11"), 0);
        assertContract(naturalShadow("0.21", 1), 0);
        assertContract(modern(1) + "color1.rgb *= 0.2;", 0);
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

    @Test
    void acceptsUnusedSharedModernSamplersButRejectsFeedbackInEitherStage() {
        String declarations = """
                uniform sampler2D shadowcolor0;
                uniform sampler2D shadowcolor1;
                uniform sampler2DShadow shadowtex0;
                uniform sampler2DShadow shadowtex1;
                """;
        assertContract(declarations + modern(2), declarations + "void main() {}", 2);
        assertContract(declarations + legacy(2), declarations + "void main() {}", 2);
        assertContract(declarations + modern(2) + "float f = shadow2D(shadowtex0, p).r;", 0);
        assertContract(declarations + modern(2),
                declarations + "void main() { texture(shadowcolor1, uv); }", 0);
    }

    @Test
    void commentsCannotProveFormulaOrAddAnotherColorTarget() {
        assertContract(modern(1) + "/* gl_FragData[1] = color2; */", 1);
        assertContract("/*" + modern(2) + "*/", 0);
        assertContract("void main() { /* " + MODERN_FORMULA + " */ gl_FragData[0] = color1; }", 0);
    }

    @Test
    void usesActualUnknownDrawBufferHostRuleAndWrittenTargetCount() throws Exception {
        assertArrayEquals(new int[]{0, 1},
                IrisTerrainShadowReplay.effectiveShadowDrawBuffers(
                        new ProgramDirectives(true, new int[]{0})));
        assertArrayEquals(new int[]{0},
                IrisTerrainShadowReplay.effectiveShadowDrawBuffers(
                        new ProgramDirectives(false, new int[]{0})));
        assertTrue(IrisTerrainShadowReplay.shadowDrawBuffersCover(new int[]{0}, 1));
        assertTrue(IrisTerrainShadowReplay.shadowDrawBuffersCover(new int[]{0, 1}, 1));
        assertTrue(IrisTerrainShadowReplay.shadowDrawBuffersCover(new int[]{0, 1}, 2));
        assertFalse(IrisTerrainShadowReplay.shadowDrawBuffersCover(new int[]{0}, 2));
        assertFalse(IrisTerrainShadowReplay.shadowDrawBuffersCover(new int[]{1}, 1));
        assertEquals(2, IrisTerrainShadowReplay.verifiedReplayColorTargets(new int[]{0, 1}, 1));
        assertEquals(1, IrisTerrainShadowReplay.verifiedReplayColorTargets(new int[]{0}, 1));
        assertEquals(2, IrisTerrainShadowReplay.verifiedReplayColorTargets(new int[]{0, 1}, 2));
        assertEquals(0, IrisTerrainShadowReplay.verifiedReplayColorTargets(new int[]{0}, 2));
    }

    @Test
    void selectsBaseShadowThroughProgramSetGet() throws Exception {
        Object program = new Object();
        Object solid = new Object();
        Object water = new Object();
        ProgramSet set = new ProgramSet(program, solid, null);
        assertSame(program, IrisTerrainShadowReplay.shadowProgram(set, ProgramId.class));
        assertSame(solid, IrisTerrainShadowReplay.program(set, ProgramId.class, "ShadowSolid"));
        assertTrue(IrisTerrainShadowReplay.hasDedicatedShadowOverride(set, ProgramId.class));
        assertTrue(IrisTerrainShadowReplay.hasDedicatedShadowOverride(
                new ProgramSet(program, null, water), ProgramId.class));
        assertFalse(IrisTerrainShadowReplay.hasDedicatedShadowOverride(
                new ProgramSet(program, null, null), ProgramId.class));
        assertNull(IrisTerrainShadowReplay.shadowProgram(new ProgramSet(null, null, null), ProgramId.class));
    }

    private static String modern(int targets) {
        return naturalShadow("0.1", targets);
    }

    private static String legacy(int targets) {
        return naturalShadow("0.2", targets);
    }

    private static String naturalShadow(String scale, int targets) {
        String output = "void main() { vec4 color1=vec4(1.0); vec4 color2=vec4(0.5);"
                + "gl_FragData[0] = color1;";
        if (targets == 2) output += "gl_FragData[1] = color2;";
        return MODERN_FORMULA.replace("*= 0.1", "*= " + scale) + output + "}";
    }

    private static void assertContract(String fragment, int targets) {
        assertContract(fragment, "void main() {}", targets);
    }

    private static void assertContract(String fragment, String vertex, int targets) {
        assertEquals(targets, IrisTerrainShadowReplay.colorTargetCount(fragment, vertex), fragment);
        assertEquals(targets > 0, IrisTerrainShadowReplay.supports(fragment, vertex), fragment);
    }

    enum ProgramId {
        Shadow,
        ShadowSolid,
        ShadowWater
    }

    static final class ProgramSet {
        private final Object shadow;
        private final Object shadowSolid;
        private final Object shadowWater;

        ProgramSet(Object shadow, Object shadowSolid, Object shadowWater) {
            this.shadow = shadow;
            this.shadowSolid = shadowSolid;
            this.shadowWater = shadowWater;
        }

        public Optional<Object> get(ProgramId id) {
            return switch (id) {
                case Shadow -> Optional.ofNullable(shadow);
                case ShadowSolid -> Optional.ofNullable(shadowSolid);
                case ShadowWater -> Optional.ofNullable(shadowWater);
            };
        }
    }

    static final class ProgramDirectives {
        private final boolean unknown;
        private final int[] drawBuffers;

        ProgramDirectives(boolean unknown, int[] drawBuffers) {
            this.unknown = unknown;
            this.drawBuffers = drawBuffers;
        }

        public boolean hasUnknownDrawBuffers() {
            return unknown;
        }

        public int[] getDrawBuffers() {
            return drawBuffers;
        }
    }

    @Test void acceptsEuphoria1105SaturatedShadowOutputs() {
        String helper = """
                vec3 saturateColors(vec3 col, float saturationMult) {
                    if (saturationMult == 1.0) return col;
                    float brightness = maxOf(col);
                    return max0((col - brightness) * saturationMult + brightness);
                }
                """;
        String oneTarget = helper + MODERN_FORMULA + """
                /* DRAWBUFFERS:0 */
                gl_FragData[0] = vec4(saturateColors(color1.rgb, 1.0), color1.a);
                """;
        String twoTarget = helper + MODERN_FORMULA + """
                /* DRAWBUFFERS:01 */
                gl_FragData[0] = vec4(saturateColors(color1.rgb, 1.25), color1.a);
                gl_FragData[1] = vec4(saturateColors(color2.rgb, pow(1.25, 0.8)), color2.a);
                """;
        assertContract(oneTarget, 1);
        assertContract(twoTarget, 2);
        assertContract(oneTarget.replace("vec3 saturateColors(vec3 col, float saturationMult)", "vec3 other(vec3 col, float s)"), 0);
    }
}
