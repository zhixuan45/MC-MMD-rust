package com.shiroha.mmdskin.bridge.runtime;

import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.render.pipeline.NativeEvaluationScope;
import java.nio.ByteBuffer;
import org.apache.logging.log4j.LogManager;

/** 可选 ABI 的拒绝只能发生在推进模型之前。 */
final class NativeParallelEvaluation {
    static final long BUDGET_V1 = 1, PROFILE_V1 = 2, DATA_PARALLEL = 4, PHYSICS_MT = 8;
    interface Calls {
        long capabilities();
        boolean update(long handle, float delta, boolean cpu, int budget, boolean physics, ByteBuffer profile);
    }

    private final Calls calls;
    private volatile long capabilities = -1;

    NativeParallelEvaluation(Calls calls) { this.calls = calls; }

    static NativeParallelEvaluation create() {
        return new NativeParallelEvaluation(new Calls() {
            public long capabilities() { return NativeFunc.GetInst().GetEvaluationCapabilities(); }
            public boolean update(long handle, float delta, boolean cpu, int budget, boolean physics, ByteBuffer profile) {
                return NativeFunc.GetInst().UpdateModelParallel(handle, delta, cpu, budget, physics, profile);
            }
        });
    }

    private long capabilities() {
        if (capabilities >= 0) return capabilities;
        synchronized (this) {
            if (capabilities >= 0) return capabilities;
            try {
                capabilities = calls.capabilities();
                LogManager.getLogger().info("[MMD并行] 原生能力={}，可用CPU线程={}，内部预算={}",
                        capabilities, Runtime.getRuntime().availableProcessors(), NativeEvaluationScope.threadBudget());
            } catch (UnsatisfiedLinkError missing) {
                capabilities = 0;
                LogManager.getLogger().info("当前原生库没有按次并行接口，使用兼容求值路径");
            }
            return capabilities;
        }
    }

    boolean update(long handle, float delta, boolean cpu, ByteBuffer profile) {
        long flags = capabilities();
        if ((flags & BUDGET_V1) == 0) return false;
        if (profile != null && ((flags & PROFILE_V1) == 0 || !profile.isDirect()
                || profile.isReadOnly() || profile.capacity() < NativeEvaluationScope.PROFILE_BYTES)) return false;
        // 旧 DLL 可以只写 64 字节，清头防止把上次详细统计当作本次结果。
        if (profile != null) profile.putLong(64, 0);
        try {
            return calls.update(handle, delta, cpu, NativeEvaluationScope.threadBudget(),
                    NativeEvaluationScope.physicsParallel(), profile);
        } catch (UnsatisfiedLinkError missing) {
            capabilities = 0;
            LogManager.getLogger().info("原生并行入口缺失，使用兼容求值路径");
            return false;
        }
    }
}
