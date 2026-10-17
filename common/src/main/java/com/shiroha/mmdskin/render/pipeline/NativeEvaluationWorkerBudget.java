package com.shiroha.mmdskin.render.pipeline;

/** 根据有效任务数限制本批后台求值线程。 */
public record NativeEvaluationWorkerBudget(int logicalProcessors, int backgroundBudget, int workers) {
    public static NativeEvaluationWorkerBudget forTasks(int logicalProcessors, int taskCount) {
        int processors = Math.max(1, logicalProcessors);
        int tasks = Math.max(0, taskCount);
        int background = Math.max(0, processors - 2);
        int workers = Math.min(background, Math.max(0, tasks - 1));
        return new NativeEvaluationWorkerBudget(processors, background, workers);
    }

    public static NativeEvaluationWorkerBudget current(int taskCount) {
        return forTasks(Runtime.getRuntime().availableProcessors(), taskCount);
    }
}
