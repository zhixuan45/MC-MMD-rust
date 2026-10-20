package com.shiroha.mmdskin.render.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class NativeEvaluationBatchTest {
    private static NativeEvaluationBatch.Work job(long handle, Runnable run, Runnable complete) {
        return new NativeEvaluationBatch.Work() {
            public long handle() { return handle; }
            public void execute() { run.run(); }
            public void complete() { complete.run(); }
        };
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS), "Independent jobs must overlap"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    @Test void overlapsJobsAndCompletesOnlyAfterAllJoinOnCaller() {
        try (var executor = Executors.newFixedThreadPool(2)) {
            Thread caller = Thread.currentThread();
            var entered = new CountDownLatch(3);
            var finished = new AtomicInteger();
            var completed = new AtomicInteger();
            Runnable run = () -> { entered.countDown(); await(entered); finished.incrementAndGet(); };
            Runnable complete = () -> {
                assertSame(caller, Thread.currentThread());
                assertEquals(3, finished.get());
                completed.incrementAndGet();
            };
            var stats = NativeEvaluationBatch.run(List.of(job(1, run, complete), job(2, run, complete), job(3, run, complete)), executor, 8);
            assertEquals(3, completed.get());
            assertEquals(2, stats.requestedWorkers());
            assertEquals(2, stats.peakWorkers());
            assertEquals(3, stats.peakActive());
        }
    }

    @Test void failureOnCallerStillJoinsWorkerAndCompletesSuccessfulJobs() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var callerFailed = new CountDownLatch(1);
            var finished = new AtomicInteger();
            var completed = new AtomicInteger();
            var failure = new IllegalStateException("native failure");
            var error = assertThrows(CompletionException.class, () -> NativeEvaluationBatch.run(List.of(
                    job(1, () -> { await(callerFailed); finished.incrementAndGet(); }, () -> {
                        assertEquals(1, finished.get()); completed.incrementAndGet();
                    }),
                    job(2, () -> { callerFailed.countDown(); throw failure; }, () -> fail("Failed job cannot publish"))
            ), executor, 4)); // 此用例验证并行异常收尾，不依赖 CI 主机的逻辑 CPU 数。
            assertSame(failure, error.getCause());
            assertEquals(1, completed.get());
        }
    }

    @Test void workerFailureAndCompletionFailureDoNotSkipRemainingCompletion() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var completed = new AtomicInteger();
            var failure = new IllegalArgumentException("worker failure");
            var error = assertThrows(CompletionException.class, () -> NativeEvaluationBatch.run(List.of(
                    job(1, () -> { throw failure; }, () -> fail("Failed job cannot publish")),
                    job(2, () -> {}, () -> { throw new IllegalStateException("completion failure"); }),
                    job(3, () -> {}, completed::incrementAndGet)
            ), executor));
            assertSame(failure, error.getCause());
            assertEquals(1, completed.get());
        }
    }

    @Test void duplicateHandleExecutesAndPublishesOnce() {
        var executed = new AtomicInteger();
        var completed = new AtomicInteger();
        var first = job(7, executed::incrementAndGet, completed::incrementAndGet);
        NativeEvaluationBatch.run(List.of(first, job(7, () -> fail("Duplicate execute"), () -> fail("Duplicate publish"))), null);
        assertEquals(1, executed.get());
        assertEquals(1, completed.get());
    }

    @Test void rejectedExecutorFallsBackWithoutDuplicatingWork() {
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var executed = new AtomicInteger();
        var completed = new AtomicInteger();
        NativeEvaluationBatch.run(List.of(job(1, executed::incrementAndGet, completed::incrementAndGet),
                job(2, executed::incrementAndGet, completed::incrementAndGet)), executor);
        assertEquals(2, executed.get());
        assertEquals(2, completed.get());
    }

    @Test void synchronousFallbackWaitsForEveryExecuteBeforeCompletion() {
        var executed = new AtomicInteger();
        Runnable complete = () -> assertEquals(2, executed.get());
        NativeEvaluationBatch.run(List.of(job(1, executed::incrementAndGet, complete),
                job(2, executed::incrementAndGet, complete)), null);
        NativeEvaluationBatch.run(List.of(), null);
    }
}
