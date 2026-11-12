package net.irisshaders.iris;

import net.irisshaders.iris.config.IrisConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

/** Probe-only host configuration: run Iris/Oculus GLSL transforms without booting the game. */
public final class Iris {
    private static final IrisConfig CONFIG = createConfig();

    public static IrisConfig getIrisConfig() { return CONFIG; }

    private static IrisConfig createConfig() {
        Path config = Path.of("build/iris-toon-probe/unused.properties");
        Path exclusions = Path.of("build/iris-toon-probe/unused-exclusions.json");
        try {
            for (Constructor<?> constructor : IrisConfig.class.getDeclaredConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                try {
                    if (parameters.length == 1 && parameters[0] == Path.class) {
                        return (IrisConfig) constructor.newInstance(config);
                    }
                    if (parameters.length == 2 && parameters[0] == Path.class && parameters[1] == Path.class) {
                        return (IrisConfig) constructor.newInstance(config, exclusions);
                    }
                } catch (InvocationTargetException exception) {
                    if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
                    if (exception.getCause() instanceof Error error) throw error;
                }
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported IrisConfig constructor", exception);
        }
        throw new IllegalStateException("Unsupported IrisConfig constructor");
    }
}
