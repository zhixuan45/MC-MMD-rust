package com.shiroha.mmdskin.render.pipeline;

/** 每次求值的内部预算；不同 Java worker 互不修改策略。 */
public final class NativeEvaluationScope {
    public static final int PROFILE_BYTES = 512;
    private static final ThreadLocal<Integer> BUDGET = new ThreadLocal<>();

    private NativeEvaluationScope() {}

    public static int threadBudget() {
        if ("false".equalsIgnoreCase(System.getProperty("mmdskin.parallelInternal"))) return 1;
        Integer scoped = BUDGET.get();
        int available = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        return scoped == null ? available : Math.max(1, Math.min(scoped, available));
    }

    public static boolean physicsParallel() {
        return threadBudget() > 1
                && !"false".equalsIgnoreCase(System.getProperty("mmdskin.parallelPhysics"));
    }

    public static void run(int threadBudget, Runnable action) {
        Integer previous = BUDGET.get();
        BUDGET.set(Math.max(1, threadBudget));
        try {
            action.run();
        } finally {
            if (previous == null) BUDGET.remove(); else BUDGET.set(previous);
        }
    }
}
