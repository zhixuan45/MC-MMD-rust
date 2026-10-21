package com.shiroha.mmdskin.compat.iris;

import java.lang.reflect.Method;
import java.util.Set;

/** Read-only public Iris pipeline access; never assumes that a loader event binds colortex0. */
final class IrisWorldTransparencyTarget {
    private static Access access;
    private static boolean unavailable;

    private IrisWorldTransparencyTarget() {}

    static boolean hasStablePrimaryColor() {
        try {
            if (access == null && !unavailable) access = new Access();
            if (access == null) return false;
            Object manager = access.manager.invoke(null);
            Object pipeline = access.pipeline.invoke(manager);
            if (!access.pipelineType.isInstance(pipeline)) return false;
            Set<?> before = (Set<?>) access.before.invoke(pipeline);
            Set<?> after = (Set<?>) access.after.invoke(pipeline);
            // Entities use the post-prepare side, water/composite the post-deferred side.
            // Reusing the entity attachment is valid only if colortex0 has not flipped.
            return before.contains(0) == after.contains(0);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (access == null) unavailable = true;
            return false;
        }
    }

    private static final class Access {
        final Class<?> pipelineType;
        final Method manager, pipeline, before, after;

        Access() throws ReflectiveOperationException {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Class<?> managerType = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
            pipelineType = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            manager = iris.getMethod("getPipelineManager");
            pipeline = managerType.getMethod("getPipelineNullable");
            before = pipelineType.getMethod("getFlippedAfterPrepare");
            after = pipelineType.getMethod("getFlippedAfterTranslucent");
        }
    }
}
