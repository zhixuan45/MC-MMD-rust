package com.shiroha.mmdskin.compat.iris;

import net.minecraft.client.renderer.ShaderInstance;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

/** Read-only public Iris access with distinct lookup, pipeline and buffer-switch outcomes. */
final class IrisWorldTransparencyTarget {
    enum Status {
        STABLE, COLOR_BUFFER_SWITCHED, API_LOOKUP_FAILED, API_READ_FAILED,
        NO_PIPELINE, PIPELINE_TYPE_MISMATCH, INVALID_FLIP_STATE
    }

    record Result(Status status, String pipeline, Boolean before, Boolean after, String detail) {
        boolean stable() { return status == Status.STABLE; }
    }

    record ShaderResult(ShaderInstance shader, String detail) {}

    private static Access access;
    private static Result lookupFailure;
    private static Object cachedPipeline;
    private static Result cachedResult;
    private static ShaderAccess shaderAccess;

    private IrisWorldTransparencyTarget() {}

    static Result inspect() {
        if (access == null) {
            if (lookupFailure != null) return lookupFailure;
            try {
                access = new Access();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return lookupFailure = failure(Status.API_LOOKUP_FAILED, null, e);
            }
        }
        try {
            Object manager = access.manager.invoke(null);
            Object pipeline = manager == null ? null : access.pipeline.invoke(manager);
            if (pipeline == cachedPipeline && cachedResult != null) return cachedResult;
            Result result = inspectPipeline(pipeline, access.pipelineType, access.before, access.after);
            // Flip snapshots are immutable for a pipeline's lifetime. Do not cache read errors.
            if (result.status == Status.STABLE || result.status == Status.COLOR_BUFFER_SWITCHED) {
                cachedPipeline = pipeline;
                cachedResult = result;
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return failure(Status.API_READ_FAILED, null, e);
        }
    }

    static Result inspectPipeline(Object pipeline, Class<?> expectedType, Method before, Method after) {
        if (pipeline == null) return new Result(Status.NO_PIPELINE, "null", null, null, "No active Iris pipeline");
        if (!expectedType.isInstance(pipeline))
            return new Result(Status.PIPELINE_TYPE_MISMATCH, pipeline.getClass().getName(), null, null,
                    "Expected " + expectedType.getName());
        try {
            Object early = before.invoke(pipeline), late = after.invoke(pipeline);
            if (!(early instanceof Set<?> earlySet) || !(late instanceof Set<?> lateSet))
                return new Result(Status.INVALID_FLIP_STATE, pipeline.getClass().getName(), null, null,
                        "Flip query must return two non-null sets");
            boolean first = earlySet.contains(0), second = lateSet.contains(0);
            return new Result(first == second ? Status.STABLE : Status.COLOR_BUFFER_SWITCHED,
                    pipeline.getClass().getName(), first, second, "colortex0 flip membership");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return failure(Status.API_READ_FAILED, pipeline, e);
        }
    }

    /** A renderer can run before Iris has replaced GameRenderer's current shader. */
    static ShaderResult entityShader(ShaderInstance current) {
        if (IrisCompat.isIrisProgram(current)) return new ShaderResult(current, "current Iris shader");
        Result result = inspect();
        if (result.status != Status.STABLE && result.status != Status.COLOR_BUFFER_SWITCHED)
            return new ShaderResult(current, "entity shader unavailable: " + result);
        try {
            if (shaderAccess == null) shaderAccess = new ShaderAccess();
            Object map = shaderAccess.map.invoke(cachedPipeline);
            Object shader = shaderAccess.get.invoke(map, shaderAccess.key);
            if (shader instanceof ShaderInstance instance && IrisCompat.isIrisProgram(instance))
                return new ShaderResult(instance, "Iris ShaderMap.ENTITIES_TRANSLUCENT");
            return new ShaderResult(current, "entity shader type mismatch: "
                    + (shader == null ? "null" : shader.getClass().getName()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return new ShaderResult(current, "entity shader API failure: " + describe(e));
        }
    }

    static String describe(Throwable error) {
        while (error instanceof InvocationTargetException wrapped && wrapped.getCause() != null)
            error = wrapped.getCause();
        return error.getClass().getName() + ": " + error.getMessage();
    }

    private static Result failure(Status status, Object pipeline, Throwable error) {
        return new Result(status, pipeline == null ? "unknown" : pipeline.getClass().getName(),
                null, null, describe(error));
    }

    static void reset() {
        access = null;
        lookupFailure = null;
        cachedPipeline = null;
        cachedResult = null;
        shaderAccess = null;
    }

    private static Class<?> irisClass(String name) throws ClassNotFoundException {
        return Class.forName("net.irisshaders.iris." + name, false,
                IrisWorldTransparencyTarget.class.getClassLoader());
    }

    private static final class Access {
        final Class<?> pipelineType;
        final Method manager, pipeline, before, after;

        Access() throws ReflectiveOperationException {
            Class<?> iris = irisClass("Iris");
            Class<?> managerType = irisClass("pipeline.PipelineManager");
            pipelineType = irisClass("pipeline.IrisRenderingPipeline");
            manager = iris.getMethod("getPipelineManager");
            pipeline = managerType.getMethod("getPipelineNullable");
            before = pipelineType.getMethod("getFlippedAfterPrepare");
            after = pipelineType.getMethod("getFlippedAfterTranslucent");
        }
    }

    private static final class ShaderAccess {
        final Method map, get;
        final Object key;

        ShaderAccess() throws ReflectiveOperationException {
            Class<?> keys = irisClass("pipeline.programs.ShaderKey");
            map = access.pipelineType.getMethod("getShaderMap");
            get = irisClass("pipeline.programs.ShaderMap").getMethod("getShader", keys);
            key = keys.getField("ENTITIES_TRANSLUCENT").get(null);
        }
    }
}
