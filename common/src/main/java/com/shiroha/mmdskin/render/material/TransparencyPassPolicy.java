package com.shiroha.mmdskin.render.material;

/** Pure planning rules for ordinary material programs versus the MMD Toon shader. */
final class TransparencyPassPolicy {
    private TransparencyPassPolicy() {}

    /** Never run an expensive full-depth prepass/seal when the Toon shader is disabled. */
    static int passCount(boolean shadow, boolean opaque, boolean sharedWorld, boolean depthPrepass,
                         boolean worldFallback, boolean toon, boolean disableDepthSeal) {
        if (shadow || opaque) return 1;
        if (sharedWorld || worldFallback || !toon) return 2;
        if (depthPrepass) return 3;
        return disableDepthSeal ? 2 : 3;
    }

    /** A GPU-only model needs glGetBufferSubData() each animation frame for CPU triangle sorting. */
    static boolean useSortedSubmission(boolean toon, boolean hasCpuPositions, boolean optInReadback) {
        return toon || hasCpuPositions || optInReadback;
    }
}
