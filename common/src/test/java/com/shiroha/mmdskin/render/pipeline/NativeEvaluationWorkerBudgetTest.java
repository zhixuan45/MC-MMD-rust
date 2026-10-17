package com.shiroha.mmdskin.render.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeEvaluationWorkerBudgetTest {
    @Test void respectsCpuAndEffectiveTaskLimits() {
        assertBudget(1, 8, 0);
        assertBudget(2, 8, 0);
        assertBudget(3, 8, 1);
        assertBudget(4, 8, 2);
        assertBudget(8, 8, 6);
        assertBudget(16, 16, 14);
        assertBudget(64, 64, 62);
        assertBudget(128, 128, 126);
    }

    @Test void zeroAndOneTaskNeverCreateBackgroundWorkers() {
        assertEquals(0, NativeEvaluationWorkerBudget.forTasks(16, 0).workers());
        assertEquals(0, NativeEvaluationWorkerBudget.forTasks(16, 1).workers());
        assertEquals(0, NativeEvaluationWorkerBudget.forTasks(16, -4).workers());
    }

    @Test void workerLimitLeavesTheCallerOneTask() {
        assertEquals(1, NativeEvaluationWorkerBudget.forTasks(16, 2).workers());
        assertEquals(3, NativeEvaluationWorkerBudget.forTasks(8, 4).workers());
        assertEquals(7, NativeEvaluationWorkerBudget.forTasks(16, 8).workers());
        assertEquals(14, NativeEvaluationWorkerBudget.forTasks(16, 64).workers());
    }

    private static void assertBudget(int processors, int tasks, int expectedWorkers) {
        NativeEvaluationWorkerBudget budget = NativeEvaluationWorkerBudget.forTasks(processors, tasks);
        assertEquals(Math.max(0, processors - 2), budget.backgroundBudget());
        assertEquals(expectedWorkers, budget.workers());
        assertEquals(Math.max(1, processors), budget.logicalProcessors());
    }
}
