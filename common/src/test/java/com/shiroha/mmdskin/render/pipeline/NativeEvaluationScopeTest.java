package com.shiroha.mmdskin.render.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeEvaluationScopeTest {
    @Test void scopeRestoresAfterFailureAndDoesNotLeakToOtherThreads() throws InterruptedException {
        int original = NativeEvaluationScope.threadBudget();
        NativeEvaluationScope.run(1, () -> {
            assertEquals(1, NativeEvaluationScope.threadBudget());
            assertFalse(NativeEvaluationScope.physicsParallel());
            var result = new java.util.concurrent.FutureTask<>(() -> {
                assertEquals(original, NativeEvaluationScope.threadBudget());
                return null;
            });
            Thread other = new Thread(result);
            other.start();
            try { result.get(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception e) { throw new AssertionError(e); }
            assertThrows(IllegalStateException.class, () -> NativeEvaluationScope.run(0, () -> {
                throw new IllegalStateException("scope");
            }));
            assertEquals(1, NativeEvaluationScope.threadBudget());
        });
        assertEquals(original, NativeEvaluationScope.threadBudget());
    }
}
