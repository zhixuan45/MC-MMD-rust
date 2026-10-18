package com.shiroha.mmdskin.bridge.runtime;

import com.shiroha.mmdskin.render.pipeline.NativeEvaluationScope;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeParallelEvaluationTest {
    @Test void olderLibraryIsProbedOnceAndNeverStartsAnEvaluation() {
        AtomicInteger probes = new AtomicInteger();
        var adapter = new NativeParallelEvaluation(new NativeParallelEvaluation.Calls() {
            public long capabilities() { probes.incrementAndGet(); throw new UnsatisfiedLinkError("old dll"); }
            public boolean update(long h, float d, boolean c, int b, boolean p, ByteBuffer out) {
                fail("旧库不执行新接口"); return false;
            }
        });
        assertFalse(adapter.update(1, .02f, false, null));
        assertFalse(adapter.update(1, .02f, false, null));
        assertEquals(1, probes.get());
    }

    @Test void validEvaluationIsCalledOnceWithScopedBudgetAndClearedDetailHeader() {
        AtomicInteger executions = new AtomicInteger();
        ByteBuffer profile = ByteBuffer.allocateDirect(512).order(ByteOrder.nativeOrder());
        profile.putLong(64, 1);
        var adapter = new NativeParallelEvaluation(new NativeParallelEvaluation.Calls() {
            public long capabilities() { return 15; }
            public boolean update(long h, float d, boolean c, int b, boolean p, ByteBuffer out) {
                executions.incrementAndGet();
                assertEquals(1, b);
                assertFalse(p);
                assertSame(profile, out);
                assertEquals(0, out.getLong(64));
                return true;
            }
        });
        NativeEvaluationScope.run(1, () -> assertTrue(adapter.update(1, .02f, false, profile)));
        assertEquals(1, executions.get());
        assertFalse(adapter.update(1, .02f, false, ByteBuffer.allocate(512)));
        assertFalse(adapter.update(1, .02f, false, ByteBuffer.allocateDirect(64)));
        assertEquals(1, executions.get());
    }

    @Test void executionFailurePropagatesAndCannotBeConvertedToRetry() {
        var failure = new IllegalStateException("started");
        var adapter = new NativeParallelEvaluation(new NativeParallelEvaluation.Calls() {
            public long capabilities() { return 1; }
            public boolean update(long h, float d, boolean c, int b, boolean p, ByteBuffer out) { throw failure; }
        });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> adapter.update(1, .02f, true, null)));
    }
}
