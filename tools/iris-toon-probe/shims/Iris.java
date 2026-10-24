package net.irisshaders.iris;

import net.irisshaders.iris.config.IrisConfig;
import java.nio.file.Path;

/** Probe-only host configuration: run the real GLSL transformer without booting NeoForge. */
public final class Iris {
    private static final IrisConfig CONFIG = new IrisConfig(Path.of("build/iris-toon-probe/unused.properties"),
            Path.of("build/iris-toon-probe/unused-exclusions.json"));
    public static IrisConfig getIrisConfig() { return CONFIG; }
}
