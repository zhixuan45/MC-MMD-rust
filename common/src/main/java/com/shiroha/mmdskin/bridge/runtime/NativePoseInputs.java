package com.shiroha.mmdskin.bridge.runtime;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Input epochs, shared by the runtime and animation bridges. Never owns native handles. */
final class NativePoseInputs {
    private static final Map<Long, State> STATES = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong CONFIGURATION = new java.util.concurrent.atomic.AtomicLong();

    static void configurationChanged() { CONFIGURATION.incrementAndGet(); }
    static void changed(long handle) { state(handle).change(); }
    static void animationChanged(long handle, long layer) { state(handle).animationChanged(layer); }
    static void externalPose(long handle) { state(handle).externalPose = true; changed(handle); }
    static void forget(long handle) { STATES.remove(handle); }
    static long revision(long handle) { return state(handle).revision(); }
    static boolean hasExternalPose(long handle) { return state(handle).externalPose; }
    static void assign(long handle, String field, int... values) { state(handle).assign(field, values); }
    static int bits(float value) { return Float.floatToRawIntBits(value); }
    private static State state(long handle) { return STATES.computeIfAbsent(handle, ignored -> new State()); }

    private static final class State {
        private final Map<String, int[]> assignments = new HashMap<>();
        private long epoch, lastConfiguration = CONFIGURATION.get();
        private volatile boolean externalPose;
        synchronized void change() { epoch++; }
        synchronized void animationChanged(long layer) {
            assignments.remove("weight." + layer);
            assignments.remove("loop." + layer);
            epoch++;
        }
        synchronized long revision() {
            long configuration = CONFIGURATION.get();
            if (lastConfiguration != configuration) { lastConfiguration = configuration; epoch++; }
            return epoch;
        }
        synchronized void assign(String field, int[] values) {
            if (!Arrays.equals(assignments.get(field), values)) { assignments.put(field, values); epoch++; }
        }
    }
}
