package com.shiroha.mmdskin.render.pipeline.transparency;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** Real-context checks for bounded readback, copied-frame data and timestamp query coexistence. */
public final class WorldTransparencyTimingGlProbe {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        check(GLFW.glfwInit(), "GLFW init");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = GLFW.glfwCreateWindow(32, 32, "timing probe", 0, 0);
        check(window != 0, "GL 4.3 context");
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            int source = GL46C.glGenBuffers(), read = GL46C.glGenBuffers(), write = GL46C.glGenBuffers();
            GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, source);
            GL46C.glBufferData(GL46C.GL_COPY_WRITE_BUFFER, new int[]{0, 37, 0}, GL46C.GL_DYNAMIC_COPY);
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, read);
            GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, write);
            try (var async = new AsyncOverflowReadback()) {
                check(async.poll() == null, "empty poll");
                check(async.issue(source), "first issue");
                check(async.issue(source), "second issue");
                check(async.issue(source), "third issue");
                check(!async.issue(source), "full ring skips without waiting");
                check(GL46C.glGetInteger(GL46C.GL_COPY_READ_BUFFER_BINDING) == read, "issue restores read binding");
                check(GL46C.glGetInteger(GL46C.GL_COPY_WRITE_BUFFER_BINDING) == write, "issue restores write binding");
                // The source can be cleared for a later frame without changing the queued sample.
                GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, source);
                GL46C.glBufferSubData(GL46C.GL_COPY_WRITE_BUFFER, 4, new int[]{0});
                GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, write);
                GL46C.glFinish(); // Test-only completion; production only polls with timeout 0.
                check(Integer.valueOf(37).equals(async.poll()), "readback keeps the original frame's overflow");
                check(GL46C.glGetInteger(GL46C.GL_COPY_READ_BUFFER_BINDING) == read, "poll restores read binding");
                check(async.poll() == null, "drained ring");
                check(async.issue(source), "ring reused");
                GL46C.glFinish();
                check(Integer.valueOf(0).equals(async.poll()), "zero overflow collected");
            }
            waterPixels();
            System.setProperty("mmdskin.profileWorldTransparency", "true");
            WorldTransparencyProfiler.beginFrame(true);
            int host = GL46C.glGenQueries();
            GL46C.glBeginQuery(GL46C.GL_TIME_ELAPSED, host);
            try (var timing = WorldTransparencyProfiler.measure(WorldTransparencyProfiler.Stage.STORAGE_CLEAR, true)) {
                GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, source);
                GL46C.glBufferSubData(GL46C.GL_COPY_WRITE_BUFFER, 4, new int[]{1});
            }
            check(GL46C.glGetQueryi(GL46C.GL_TIME_ELAPSED, GL46C.GL_CURRENT_QUERY) == host,
                    "timestamp scopes preserve the host TIME_ELAPSED query");
            GL46C.glEndQuery(GL46C.GL_TIME_ELAPSED);
            GL46C.glFinish();
            WorldTransparencyProfiler.beginFrame(false);
            var instanceField = WorldTransparencyProfiler.class.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            Object profiler = instanceField.get(null);
            var gpuField = WorldTransparencyProfiler.class.getDeclaredField("gpu");
            gpuField.setAccessible(true);
            var samples = (com.shiroha.mmdskin.render.pipeline.PerformanceSampleWindow[]) gpuField.get(profiler);
            check(samples[WorldTransparencyProfiler.Stage.STORAGE_CLEAR.ordinal()].snapshot().count() == 1,
                    "completed timestamp pair is collected");
            var logField = WorldTransparencyProfiler.class.getDeclaredField("lastLog");
            logField.setAccessible(true);
            logField.setLong(profiler, System.nanoTime() - 6_000_000_000L);
            WorldTransparencyProfiler.beginFrame(true);
            System.setProperty("mmdskin.profileWorldTransparency", "false");
            WorldTransparencyProfiler.beginFrame(false);
            System.setProperty("mmdskin.profileWorldTransparency", "true");
            WorldTransparencyProfiler.beginFrame(true);
            check(samples[WorldTransparencyProfiler.Stage.STORAGE_CLEAR.ordinal()].snapshot().count() == 0,
                    "sampling generation clears old GPU samples");
            for (int i = 0; i < 70; i++) {
                try (var timing = WorldTransparencyProfiler.measure(WorldTransparencyProfiler.Stage.RESOLVE_DRAW, true)) { }
            }
            try (var timing = WorldTransparencyProfiler.measure(WorldTransparencyProfiler.Stage.RESOLVE_DRAW, true)) { }
            WorldTransparencyProfiler.release();
            GL46C.glDeleteQueries(host);
            GL46C.glDeleteBuffers(source); GL46C.glDeleteBuffers(read); GL46C.glDeleteBuffers(write);
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "no GL errors");
            System.out.println("PASS WorldTransparencyTimingGlProbe: ring bound, copied-frame data, bindings, reuse, "
                    + "host query coexistence, sampling toggle and release");
        } finally {
            GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate();
        }
    }

    private static void waterPixels() {
        int meta = GL46C.glGenBuffers();
        int old = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
        int slot = GL46C.glGetInteger(GL46C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) - 2;
        // Header + three 28-byte pixels: model-only, model/water, water-only.
        int[] input = {12, 0, 10, 0,
                8, 11, 12, 13, 14, 15, 16,
                11, 21, 22, 23, 24, 25, 26,
                12, 31, 32, 33, 34, 35, 36};
        try (var stage = new WaterResolveStage()) {
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, meta);
            GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, input, GL46C.GL_DYNAMIC_COPY);
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, old);
            int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
            stage.clearResolvedPixels(meta, slot, 3, 1);
            check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM) == program, "water clear restores program");
            check(GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING) == old, "water clear restores generic binding");
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, meta);
            int[] actual = new int[input.length];
            GL46C.glGetBufferSubData(GL46C.GL_SHADER_STORAGE_BUFFER, 0, actual);
            for (int i = 0; i < 11; i++) check(actual[i] == input[i], "model-only pixel and header retained");
            for (int i = 11; i < actual.length; i++) check(actual[i] == 0, "water pixels consumed once");
            input[0] = input[2]; input[1] = 1; // Empty water batch, existing overflow tail.
            GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, input, GL46C.GL_DYNAMIC_COPY);
            stage.clearResolvedPixels(meta, slot, 3, 1);
            // The fixture must contain no post-snapshot head for an actually empty batch.
            input[11] = 9; input[18] = 10;
            GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, input, GL46C.GL_DYNAMIC_COPY);
            stage.clearResolvedPixels(meta, slot, 3, 1);
            GL46C.glGetBufferSubData(GL46C.GL_SHADER_STORAGE_BUFFER, 0, actual);
            check(java.util.Arrays.equals(input, actual), "empty water preserves model and overflow pixels");
            input[0] = 12; // With actual water and overflow, keep full-resolve fallback.
            GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, input, GL46C.GL_DYNAMIC_COPY);
            stage.clearResolvedPixels(meta, slot, 3, 1);
            GL46C.glGetBufferSubData(GL46C.GL_SHADER_STORAGE_BUFFER, 0, actual);
            for (int i = 4; i < actual.length; i++) check(actual[i] == 0, "overflow fallback consumes all pixels");
        } finally {
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, old);
            GL46C.glDeleteBuffers(meta);
        }
    }
}
