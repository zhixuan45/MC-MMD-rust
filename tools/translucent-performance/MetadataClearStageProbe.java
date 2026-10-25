package com.shiroha.mmdskin.render.pipeline.transparency;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

import java.util.Arrays;

/** Same-context GPU timestamp comparison of the production compute stage and buffer clear. */
public final class MetadataClearStageProbe {
    private static final int SAMPLES = 40;
    private static final int WARMUPS = 20;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static int sentinelProgram() {
        int shader = GL46C.glCreateShader(GL46C.GL_COMPUTE_SHADER);
        GL46C.glShaderSource(shader, "#version 430 core\nlayout(local_size_x=1) in; void main() {}");
        GL46C.glCompileShader(shader);
        check(GL46C.glGetShaderi(shader, GL46C.GL_COMPILE_STATUS) != 0, GL46C.glGetShaderInfoLog(shader));
        int program = GL46C.glCreateProgram();
        GL46C.glAttachShader(program, shader);
        GL46C.glLinkProgram(program);
        GL46C.glDeleteShader(shader);
        check(GL46C.glGetProgrami(program, GL46C.GL_LINK_STATUS) != 0, GL46C.glGetProgramInfoLog(program));
        return program;
    }

    private static void clearBufferData(int buffer) {
        int generic = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
        try {
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, buffer);
            GL46C.glClearBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, GL46C.GL_R32UI,
                    GL46C.GL_RED_INTEGER, GL46C.GL_UNSIGNED_INT, new int[]{0});
            GL46C.glMemoryBarrier(GL46C.GL_BUFFER_UPDATE_BARRIER_BIT | GL46C.GL_SHADER_STORAGE_BARRIER_BIT);
        } finally {
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, generic);
        }
    }

    private static void poison(int buffer) {
        int generic = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
        try {
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, buffer);
            GL46C.glClearBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, GL46C.GL_R32UI,
                    GL46C.GL_RED_INTEGER, GL46C.GL_UNSIGNED_INT, new int[]{0x5a5a5a5a});
        } finally {
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, generic);
        }
    }

    private static void verifyZero(int buffer, int words) {
        int generic = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
        int[] chunk = new int[1 << 20];
        try {
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, buffer);
            for (int first = 0; first < words; first += chunk.length) {
                int count = Math.min(chunk.length, words - first);
                int[] values = new int[count];
                GL46C.glGetBufferSubData(GL46C.GL_SHADER_STORAGE_BUFFER, (long) first * Integer.BYTES, values);
                for (int i = 0; i < count; i++) check(values[i] == 0, "nonzero metadata word=" + (first + i));
            }
        } finally {
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, generic);
        }
    }

    private static double percentile(long[] nanos, double p) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)] / 1_000_000.0;
    }

    private static long[] pairedDurations(int[] starts, int[] ends) {
        long[] values = new long[starts.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = GL46C.glGetQueryObjectui64(ends[i], GL46C.GL_QUERY_RESULT)
                    - GL46C.glGetQueryObjectui64(starts[i], GL46C.GL_QUERY_RESULT);
        }
        return values;
    }

    private static void benchmark(int width, int height, MetadataClearStage stage,
                                  int binding, int sentinel, int genericSentinel, int sentinelProgram) {
        long pixels = (long) width * height;
        long bytes = 16L + 28L * pixels;
        int words = Math.toIntExact(bytes / Integer.BYTES);
        int buffer = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, buffer);
        GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, bytes, GL46C.GL_DYNAMIC_COPY);
        GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, genericSentinel);
        int alignment = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        long offset = ((16L + alignment - 1L) / alignment) * alignment;
        GL46C.glBindBufferRange(GL46C.GL_SHADER_STORAGE_BUFFER, binding, sentinel, offset, 4096);
        GL46C.glUseProgram(sentinelProgram);
        int genericBefore = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
        int indexedBefore = GL46C.glGetIntegeri(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, binding);
        long startBefore = GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_START, binding);
        long sizeBefore = GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_SIZE, binding);
        for (int i = 0; i < WARMUPS; i++) {
            if ((i & 1) == 0) { clearBufferData(buffer); check(stage.clear(buffer, binding, bytes), "compute clear enabled"); }
            else { check(stage.clear(buffer, binding, bytes), "compute clear enabled"); clearBufferData(buffer); }
        }
        GL46C.glFinish();

        poison(buffer);
        clearBufferData(buffer);
        verifyZero(buffer, words);
        poison(buffer);
        check(stage.clear(buffer, binding, bytes), "compute clear enabled");
        verifyZero(buffer, words);

        check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM) == sentinelProgram, "program restore");
        check(GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING) == genericBefore, "generic SSBO restore");
        check(GL46C.glGetIntegeri(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, binding) == indexedBefore,
                "indexed SSBO restore");
        check(GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_START, binding) == startBefore,
                "SSBO range offset restore");
        check(GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_SIZE, binding) == sizeBefore,
                "SSBO range size restore");

        int[] queries = new int[SAMPLES * 4];
        GL46C.glGenQueries(queries);
        int[] clearStart = new int[SAMPLES], clearEnd = new int[SAMPLES];
        int[] computeStart = new int[SAMPLES], computeEnd = new int[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            int q = i * 4;
            if ((i & 1) == 0) {
                GL46C.glQueryCounter(queries[q], GL46C.GL_TIMESTAMP);
                check(stage.clear(buffer, binding, bytes), "compute clear enabled");
                GL46C.glQueryCounter(queries[q + 1], GL46C.GL_TIMESTAMP);
                GL46C.glQueryCounter(queries[q + 2], GL46C.GL_TIMESTAMP);
                clearBufferData(buffer);
                GL46C.glQueryCounter(queries[q + 3], GL46C.GL_TIMESTAMP);
                computeStart[i] = queries[q]; computeEnd[i] = queries[q + 1];
                clearStart[i] = queries[q + 2]; clearEnd[i] = queries[q + 3];
            } else {
                GL46C.glQueryCounter(queries[q], GL46C.GL_TIMESTAMP);
                clearBufferData(buffer);
                GL46C.glQueryCounter(queries[q + 1], GL46C.GL_TIMESTAMP);
                GL46C.glQueryCounter(queries[q + 2], GL46C.GL_TIMESTAMP);
                check(stage.clear(buffer, binding, bytes), "compute clear enabled");
                GL46C.glQueryCounter(queries[q + 3], GL46C.GL_TIMESTAMP);
                clearStart[i] = queries[q]; clearEnd[i] = queries[q + 1];
                computeStart[i] = queries[q + 2]; computeEnd[i] = queries[q + 3];
            }
        }
        GL46C.glFinish();
        long[] clear = pairedDurations(clearStart, clearEnd);
        long[] compute = pairedDurations(computeStart, computeEnd);
        poison(buffer);
        check(stage.clear(buffer, binding, bytes), "compute clear enabled");
        verifyZero(buffer, words);
        System.out.printf("%dx%d bytes=%d pixels=%d words=%d%n", width, height, bytes, pixels, words);
        System.out.printf("  glClearBufferData+barrier median=%.4fms p95=%.4fms%n",
                percentile(clear, .50), percentile(clear, .95));
        System.out.printf("  MetadataClearStage median=%.4fms p95=%.4fms%n",
                percentile(compute, .50), percentile(compute, .95));
        System.out.printf("  median_saved=%.2f%%%n",
                100.0 * (percentile(clear, .50) - percentile(compute, .50)) / percentile(clear, .50));
        GL46C.glDeleteQueries(queries);
        GL46C.glDeleteBuffers(buffer);
    }

    public static void main(String[] args) {
        check(GLFW.glfwInit(), "GLFW init");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = GLFW.glfwCreateWindow(32, 32, "metadata clear stage probe", 0, 0);
        check(window != 0, "GL 4.3 context");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.printf("GL_VENDOR=%s%nGL_RENDERER=%s%nGL_VERSION=%s%n",
                    GL46C.glGetString(GL46C.GL_VENDOR), GL46C.glGetString(GL46C.GL_RENDERER),
                    GL46C.glGetString(GL46C.GL_VERSION));
            System.setProperty("mmdskin.metadataClearCompute", "true");
            int binding = GL46C.glGetInteger(GL46C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) - 2;
            int sentinel = GL46C.glGenBuffers(), genericSentinel = GL46C.glGenBuffers();
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, sentinel);
            GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, 16384, GL46C.GL_DYNAMIC_COPY);
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, genericSentinel);
            GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, 8192, GL46C.GL_DYNAMIC_COPY);
            int offset = Math.max(16, GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT));
            GL46C.glBindBufferRange(GL46C.GL_SHADER_STORAGE_BUFFER, binding, sentinel, offset, 4096);
            int oldProgram = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
            int sentinelProgram = sentinelProgram();
            GL46C.glUseProgram(sentinelProgram);
            try (MetadataClearStage stage = new MetadataClearStage()) {
                benchmark(1920, 1080, stage, binding, sentinel, genericSentinel, sentinelProgram);
                benchmark(2560, 1600, stage, binding, sentinel, genericSentinel, sentinelProgram);
                System.setProperty("mmdskin.metadataClearCompute", "false");
                check(!stage.clear(sentinel, binding, 16), "JVM fallback switch");
                System.setProperty("mmdskin.metadataClearCompute", "true");
            }
            GL46C.glUseProgram(oldProgram);
            GL46C.glDeleteProgram(sentinelProgram);
            GL46C.glDeleteBuffers(sentinel);
            GL46C.glDeleteBuffers(genericSentinel);
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL error");
            System.out.println("PASS MetadataClearStageProbe: exact full-buffer zero fill, header/pixels/tail, "
                    + "program/generic/indexed-range restore and JVM fallback switch");
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }
}
