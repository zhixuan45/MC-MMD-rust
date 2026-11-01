package com.shiroha.mmdskin.render.effectpack;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FxConstantExpressionTest {
    private float[] evaluate(String expression, int width) throws IOException {
        return new FxConstantExpression(Map.of(), Map.of(), Map.of()).evaluate(expression, width);
    }

    @Test void evaluatesRayColorsAndNestedArguments() throws Exception {
        float[] purple = evaluate("pow(float3(155, 64, 227) / 255, 2.2)", 3);
        assertArrayEquals(new float[]{(float) Math.pow(155.0 / 255, 2.2),
                (float) Math.pow(64.0 / 255, 2.2), (float) Math.pow(227.0 / 255, 2.2)}, purple, 1e-7f);
        assertArrayEquals(new float[]{0.4f, 0.8f, 1}, evaluate("float3(102,204,255)/255", 3), 1e-7f);
        assertArrayEquals(new float[]{1, 2, 3, 4}, evaluate("float4(float2(1, 2), 3, 4)", 4));
    }

    @Test void obeysPrecedenceUnaryOperatorsBroadcastAndScientificLiterals() throws Exception {
        assertArrayEquals(new float[]{-1, 0, 1}, evaluate("-(float3(1, 2, 3) - 2) + 2 * float3(-1, 0, 1)", 3));
        assertArrayEquals(new float[]{0.25f, 0.25f}, evaluate("half2(2.5e-1f)", 2));
        assertArrayEquals(new float[]{9}, evaluate("(1 + 2) * 3", 1));
        assertArrayEquals(new float[]{2, 2, 2}, evaluate("2", 3));
    }

    @Test void evaluatesPureMacrosWithConstantsAndLexicalArguments() throws Exception {
        var function = new FxConstantExpression.Function(List.of("x"),
                "exp((1 - saturate(x)) * float3(-8, -40, -64))");
        var evaluator = new FxConstantExpression(Map.of("strength", new float[]{0.8f}),
                Map.of("DENOMINATOR", "(255.0f)", "LOOP", "LOOP"),
                Map.of("SSS_SKIN_TRANSMITTANCE", function));
        float[] sss = evaluator.evaluate("SSS_SKIN_TRANSMITTANCE(strength)", 3);
        assertArrayEquals(new float[]{(float) Math.exp(-1.6), (float) Math.exp(-8), (float) Math.exp(-12.8)}, sss, 1e-7f);
        assertArrayEquals(new float[]{1}, evaluator.evaluate("255 / DENOMINATOR", 1));
        assertThrows(IOException.class, () -> evaluator.evaluate("LOOP", 1));
        assertThrows(IOException.class, () -> evaluator.evaluate("SSS_SKIN_TRANSMITTANCE()", 3));
    }

    @Test void rejectsCyclesAndResourceExhaustion() {
        var recursive = new FxConstantExpression(Map.of(), Map.of("a", "b", "b", "a"),
                Map.of("f", new FxConstantExpression.Function(List.of("x"), "f(x)")));
        assertThrows(IOException.class, () -> recursive.evaluate("a", 1));
        assertThrows(IOException.class, () -> recursive.evaluate("f(1)", 1));
        assertThrows(IOException.class, () -> recursive.evaluate("(".repeat(100) + "1" + ")".repeat(100), 1));
        assertThrows(IOException.class, () -> recursive.evaluate("-".repeat(100) + "1", 1));
        assertThrows(IOException.class, () -> recursive.evaluate("1+".repeat(9000) + "1", 1));
    }

    @Test void rejectsRuntimeInputsNonFiniteResultsAndMalformedExpressions() {
        for (String expression : List.of("time", "mAlbedoColor", "unknown(1)", "1/0", "0/0",
                "pow(-1, 0.5)", "exp(10000)", "1e39", "1.2.3", "1e+", "1; return 2",
                "float3(1,2)", "float3(1,2,3)+float2(1,2)", "float3(1,2,3).r", "saturate()")) {
            assertThrows(IOException.class, () -> evaluate(expression, 1), expression);
        }
    }

    @Test void rejectsVectorTruncationAndPreservesEvaluatorAfterFailure() throws Exception {
        var evaluator = new FxConstantExpression(Map.of(), Map.of(), Map.of());
        assertThrows(IOException.class, () -> evaluator.evaluate("float3(1,2,3)", 2));
        assertArrayEquals(new float[]{0.5f}, evaluator.evaluate("lerp(0, 1, .5)", 1));
        assertArrayEquals(new float[]{0, 1, 1}, evaluator.evaluate("clamp(float3(-1,2,3), 0, 1)", 3));
    }
}
