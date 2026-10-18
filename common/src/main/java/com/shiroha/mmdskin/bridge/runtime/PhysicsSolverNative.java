package com.shiroha.mmdskin.bridge.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 封装可选的原生 solver 迭代设置。 */
public final class PhysicsSolverNative {
    private static final Logger logger = LogManager.getLogger();
    private static final AtomicBoolean missingLogged = new AtomicBoolean();

    private PhysicsSolverNative() { }

    public static boolean apply(int iterations) {
        int clamped = Math.max(1, Math.min(80, iterations));
        try {
            return SetIterations(clamped);
        } catch (UnsatisfiedLinkError missing) {
            if (missingLogged.compareAndSet(false, true)) {
                logger.warn("当前原生库不支持 solver 迭代次数设置，继续使用原生默认值");
            }
            return false;
        }
    }

    public static native boolean SetIterations(int iterations);
}
