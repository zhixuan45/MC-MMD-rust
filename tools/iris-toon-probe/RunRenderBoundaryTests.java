import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.PrintWriter;
import java.util.List;

/** Runs the focused Java tests without Gradle's aggregate compileTestJava task. */
public final class RunRenderBoundaryTests {
    private RunRenderBoundaryTests() {}

    public static void main(String[] args) {
        List<String> classes = List.of(
                "com.shiroha.mmdskin.config.ConfigDataTest",
                "com.shiroha.mmdskin.compat.iris.TerrainShadowFrameTest",
                "com.shiroha.mmdskin.compat.iris.IrisTerrainShadowReplayTest",
                "com.shiroha.mmdskin.render.backend.ShadowModelViewMatrixTest"
        );
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(classes.stream().map(DiscoverySelectors::selectClass).toList())
                .build();
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request, listener);
        listener.getSummary().printTo(new PrintWriter(System.out, true));
        if (listener.getSummary().getTotalFailureCount() != 0) {
            System.exit(1);
        }
    }
}
