package com.shiroha.mmdskin.compat.iris;

import java.util.HashSet;
import java.util.Set;

/** Populated from transformed bytecode, not from Iris installation or version strings. */
public final class ToonIrisHooks {
    private static final Set<String> VERIFIED = new HashSet<>();
    private ToonIrisHooks() {}

    public static synchronized void verified(String target) { VERIFIED.add(target); }

    public static synchronized boolean complete() {
        return VERIFIED.containsAll(Set.of("IrisRenderingPipeline", "ExtendedShader", "FallbackShader", "SodiumShader"));
    }
}
