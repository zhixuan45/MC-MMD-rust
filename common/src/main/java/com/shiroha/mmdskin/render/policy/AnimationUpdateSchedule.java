package com.shiroha.mmdskin.render.policy;

/** Stable phases spread distant-model work; distance hysteresis avoids LOD chatter. */
final class AnimationUpdateSchedule {
    private int band = -1;
    private long lastFrame = Long.MIN_VALUE;

    static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("mmdskin.stableScheduling"));
    }

    boolean due(long handle, long frame, double distanceSq, boolean local,
                double medium, double far, int mediumInterval, int farInterval) {
        return due(handle, frame, distanceSq, local, medium, far, mediumInterval, farInterval, enabled());
    }

    boolean due(long handle, long frame, double distanceSq, boolean local,
                double medium, double far, int mediumInterval, int farInterval, boolean stable) {
        int next = stable ? resolveBand(distanceSq, medium, far)
                : distanceSq <= medium * medium ? 0 : distanceSq <= far * far ? 1 : 2;
        boolean changed = next != band;
        band = next;
        int interval = local || band == 0 ? 1 : Math.max(1, band == 1 ? mediumInterval : farInterval);
        // Repeated passes in this frame still reach the input-aware evaluation cache.
        if (lastFrame == frame) return true;
        if (!stable) {
            if (lastFrame == Long.MIN_VALUE || frame - lastFrame >= interval) {
                lastFrame = frame;
                return true;
            }
            return false;
        }
        long hash = handle ^ (handle >>> 33);
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        if (lastFrame == Long.MIN_VALUE || changed || interval == 1
                || Math.floorMod(frame, interval) == Math.floorMod(hash, interval)) {
            lastFrame = frame;
            return true;
        }
        return false;
    }

    private int resolveBand(double distanceSq, double medium, double far) {
        // Enter the cheaper band past +10%, return to higher quality below -10%.
        double mediumBoundary = medium * (band >= 1 ? 0.9 : band == 0 ? 1.1 : 1.0);
        double farBoundary = far * (band == 2 ? 0.9 : band >= 0 ? 1.1 : 1.0);
        if (distanceSq <= mediumBoundary * mediumBoundary) return 0;
        return distanceSq <= farBoundary * farBoundary ? 1 : 2;
    }
}
