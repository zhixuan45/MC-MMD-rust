package com.shiroha.mmdskin.render.pipeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All preparation and completion run on the caller; workers execute JNI-only jobs.
 * Every submitted job is joined, including when another job fails. No work outlives a frame.
 */
public final class NativeEvaluationBatch {
    public interface Work {
        long handle();
        void execute();
        void complete();
    }
    private static final int WORKERS = Math.max(0, Math.min(3, Runtime.getRuntime().availableProcessors() - 2));
    private static final ExecutorService EXECUTOR = WORKERS == 0 ? null : Executors.newFixedThreadPool(WORKERS, task -> {
        Thread thread = new Thread(task, "MMD native evaluation");
        thread.setDaemon(true);
        return thread;
    });

    private NativeEvaluationBatch() {}
    public static boolean enabled() {
        return EXECUTOR != null && !EXECUTOR.isShutdown()
                && !"false".equalsIgnoreCase(System.getProperty("mmdskin.reuseEvaluation"))
                && !"false".equalsIgnoreCase(System.getProperty("mmdskin.parallelEvaluation"));
    }
    public static void run(List<? extends Work> input) {
        run(input, enabled() ? EXECUTOR : null);
    }

    static void run(List<? extends Work> input, ExecutorService executor) {
        // A duplicated handle must never be evaluated twice or race its own input packet.
        var handles = new HashSet<Long>();
        var jobs = new ArrayList<Work>();
        for (Work work : input) if (handles.add(work.handle())) jobs.add(work);
        var tasks = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < jobs.size(); i++) {
            Work work = jobs.get(i);
            if (executor != null && jobs.size() > 1 && i + 1 < jobs.size()) {
                try { tasks.add(CompletableFuture.runAsync(work::execute, executor)); }
                catch (RuntimeException rejected) { tasks.add(executeHere(work)); }
            } else tasks.add(executeHere(work));
        }
        Throwable failure = null;
        // Join everything before completing anything: GL/lifecycle work can resume only after this boundary.
        for (var task : tasks) {
            try { task.join(); }
            catch (CompletionException error) { if (failure == null) failure = error.getCause(); }
        }
        for (int i = 0; i < jobs.size(); i++) {
            if (!tasks.get(i).isCompletedExceptionally()) {
                try { jobs.get(i).complete(); }
                catch (Throwable error) { if (failure == null) failure = error; }
            }
        }
        if (failure != null) throw new CompletionException(failure);
    }
    private static CompletableFuture<Void> executeHere(Work work) {
        try { work.execute(); return CompletableFuture.completedFuture(null); }
        catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }
    public static void shutdown() { if (EXECUTOR != null) EXECUTOR.shutdown(); }
}
