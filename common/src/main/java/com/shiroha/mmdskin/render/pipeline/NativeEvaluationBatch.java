package com.shiroha.mmdskin.render.pipeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 所有准备和发布都在调用线程执行，JNI 工作必须在本批 join 后结束。 */
public final class NativeEvaluationBatch {
    public record BatchStats(int tasks, int cpuBudget, int internalBudget,
                             int requestedWorkers, int peakWorkers, int peakActive) {}

    public interface Work {
        long handle();
        void execute();
        void complete();
    }

    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            0, 1, 45L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
        Thread thread = new Thread(task, "MMD native evaluation");
        thread.setDaemon(true);
        return thread;
    });
    static { EXECUTOR.allowCoreThreadTimeOut(true); }

    private NativeEvaluationBatch() {}

    public static boolean enabled() {
        return !EXECUTOR.isShutdown()
                && !"false".equalsIgnoreCase(System.getProperty("mmdskin.reuseEvaluation"))
                && !"false".equalsIgnoreCase(System.getProperty("mmdskin.parallelEvaluation"));
    }

    public static BatchStats run(List<? extends Work> input) {
        return run(input, enabled() ? EXECUTOR : null, Runtime.getRuntime().availableProcessors());
    }

    static BatchStats run(List<? extends Work> input, ExecutorService executor) {
        return run(input, executor, Runtime.getRuntime().availableProcessors());
    }

    static synchronized BatchStats run(List<? extends Work> input, ExecutorService executor, int logicalProcessors) {
        List<Work> jobs = uniqueJobs(input);
        NativeEvaluationWorkerBudget budget = NativeEvaluationWorkerBudget.forTasks(logicalProcessors, jobs.size());
        if (jobs.isEmpty()) return new BatchStats(0, budget.backgroundBudget(), 0, 0, 0, 0);

        AtomicInteger next = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger activeWorkers = new AtomicInteger();
        AtomicInteger peakActive = new AtomicInteger();
        AtomicInteger peakWorkers = new AtomicInteger();
        Throwable[] failures = new Throwable[jobs.size()];
        List<CompletableFuture<Void>> workers = new ArrayList<>(budget.workers());

        if (executor instanceof ThreadPoolExecutor pool) {
            // 线程上限只在前一批 join 后调整，闲置线程可回收。
            int capacity = Math.max(1, budget.backgroundBudget());
            pool.setMaximumPoolSize(Math.max(pool.getMaximumPoolSize(), capacity));
            pool.setCorePoolSize(budget.backgroundBudget());
            pool.setMaximumPoolSize(capacity);
            if (pool.getKeepAliveTime(TimeUnit.NANOSECONDS) > 0) pool.allowCoreThreadTimeOut(true);
        }

        for (int i = 0; i < budget.workers(); i++) {
            try {
                int internalBudget = jobs.size() == 1
                        ? Math.max(1, budget.logicalProcessors() - 1) : 1;
                workers.add(CompletableFuture.runAsync(
                        () -> drain(jobs, failures, next, internalBudget, active, peakActive,
                                activeWorkers, peakWorkers, true), executor));
            } catch (RuntimeException rejected) {
                // 拒绝时调用线程继续领取同一队列，不丢任务。
                break;
            }
        }

        int internalBudget = jobs.size() == 1
                ? Math.max(1, budget.logicalProcessors() - 1) : 1;
        drain(jobs, failures, next, internalBudget, active, peakActive,
                activeWorkers, peakWorkers, false);
        for (CompletableFuture<Void> worker : workers) {
            try { worker.join(); }
            catch (CompletionException error) {
                // drain 会吸收逐实例异常；此处只防线程本身意外退出。
                if (failures.length > 0 && failures[0] == null) failures[0] = error.getCause();
            }
        }

        Throwable failure = null;
        for (int i = 0; i < jobs.size(); i++) {
            if (failures[i] != null) {
                if (failure == null) failure = failures[i];
                continue;
            }
            try { jobs.get(i).complete(); }
            catch (Throwable error) { if (failure == null) failure = error; }
        }
        if (failure != null) throw new CompletionException(failure);
        return new BatchStats(jobs.size(), budget.backgroundBudget(), internalBudget,
                workers.size(), peakWorkers.get(), peakActive.get());
    }

    private static List<Work> uniqueJobs(List<? extends Work> input) {
        HashSet<Long> handles = new HashSet<>();
        ArrayList<Work> jobs = new ArrayList<>();
        if (input == null || input.isEmpty()) return jobs;
        for (Work work : input) {
            if (work != null && handles.add(work.handle())) jobs.add(work);
        }
        return jobs;
    }

    private static void drain(List<Work> jobs, Throwable[] failures, AtomicInteger next, int internalBudget,
                              AtomicInteger active, AtomicInteger peakActive, AtomicInteger activeWorkers,
                              AtomicInteger peakWorkers, boolean background) {
        int index;
        while ((index = next.getAndIncrement()) < jobs.size()) {
            int nowActive = active.incrementAndGet();
            peakActive.accumulateAndGet(nowActive, Math::max);
            if (background) {
                int nowWorkers = activeWorkers.incrementAndGet();
                peakWorkers.accumulateAndGet(nowWorkers, Math::max);
            }
            try {
                NativeEvaluationScope.run(internalBudget, jobs.get(index)::execute);
            } catch (Throwable error) {
                failures[index] = error;
            } finally {
                if (background) activeWorkers.decrementAndGet();
                active.decrementAndGet();
            }
        }
    }

    public static void shutdown() {
        EXECUTOR.shutdown();
    }
}
