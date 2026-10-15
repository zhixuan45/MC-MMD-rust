package com.shiroha.mmdskin.render.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslucentDiagnosticsAggregationTest {
    @Test
    void emitsPerCallStageAndCounterAggregatesAsJson() {
        TranslucentDiagnostics.Aggregate aggregate =
                new TranslucentDiagnostics.Aggregate(42L, "GPU_COMPUTE", "WORLD", "Test \"Model\"");
        long[] stages = new long[TranslucentDiagnostics.Stage.values().length];
        long[] counters = new long[TranslucentDiagnostics.Counter.values().length];
        stages[TranslucentDiagnostics.Stage.SORT.ordinal()] = 100;
        counters[TranslucentDiagnostics.Counter.RUNS.ordinal()] = 3;
        aggregate.addSample(1_000, stages, counters);
        stages[TranslucentDiagnostics.Stage.SORT.ordinal()] = 300;
        counters[TranslucentDiagnostics.Counter.RUNS.ordinal()] = 5;
        aggregate.addSample(2_000, stages, counters);

        String json = aggregate.toJson();
        assertTrue(json.contains("\"schemaVersion\":1"));
        assertTrue(json.contains("\"modelId\":42"));
        assertTrue(json.contains("\"modelName\":\"Test \\\"Model\\\"\""));
        assertTrue(json.contains("\"samples\":2"));
        assertTrue(json.contains("\"renderCallMedianNs\":1000"));
        assertTrue(json.contains("\"SORT\":{\"avgNs\":200,\"medianNs\":100,\"p95Ns\":300}"));
        assertTrue(json.contains("\"RUNS\":8"));
    }

    @Test
    void stageAverageCoversWholeIntervalWhileQuantilesUseRecentWindowIncludingZeros() {
        TranslucentDiagnostics.Aggregate aggregate =
                new TranslucentDiagnostics.Aggregate(7L, "CPU", "WORLD", "Model");
        long[] stages = new long[TranslucentDiagnostics.Stage.values().length];
        long[] counters = new long[TranslucentDiagnostics.Counter.values().length];
        stages[TranslucentDiagnostics.Stage.SORT.ordinal()] = 1_000;
        aggregate.addSample(10, stages, counters);
        stages[TranslucentDiagnostics.Stage.SORT.ordinal()] = 0;
        for (int i = 1; i < 122; i++) aggregate.addSample(10, stages, counters);

        String json = aggregate.toJson();
        assertTrue(json.contains("\"samples\":122"));
        assertTrue(json.contains("\"windowSamples\":120"));
        assertTrue(json.contains("\"SORT\":{\"avgNs\":8,\"medianNs\":0,\"p95Ns\":0}"));
        assertTrue(json.contains("\"CLASSIFY\":{\"avgNs\":0,\"medianNs\":0,\"p95Ns\":0}"));
    }

}
