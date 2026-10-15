package com.shiroha.mmdskin.render.material;

import com.shiroha.mmdskin.render.pipeline.TranslucentDiagnostics;
import com.shiroha.mmdskin.util.AssetsUtil;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

/** Current-frame depth, stable sort and EBO/material gather with no position or command readback. */
final class GpuTranslucentPreparation implements AutoCloseable {
    private int program, topology, keys, indices, rows, command, capacity;
    private int[] uploadedTopology;
    private boolean failed;
    private long bytes;

    static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("mmdskin.gpuTranslucent"))
                && GL.getCapabilities().OpenGL43
                && GL46C.glGetInteger(GL46C.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS) >= 6;
    }

    boolean prepare(TranslucentDrawOrder.Inputs input, int[] triangles) {
        if (failed || !enabled() || triangles.length == 0) return false;
        int n = triangles.length / 4;
        int required = capacityFor(n);
        long maxBlock = GL46C.glGetInteger64(GL46C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        if (required == 0 || (long) required * 12 > maxBlock || (long) triangles.length * 4 > maxBlock
                || (required + 127L) / 128 > GL46C.glGetIntegeri(GL46C.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0)) return false;
        int previousProgram = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        int generic = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
        int[] slots = {0, 1, 3, 4, 5, 6};
        BufferBinding[] bindings = Arrays.stream(slots).mapToObj(BufferBinding::capture).toArray(BufferBinding[]::new);
        try {
            if (program == 0) program = createProgram();
            if (topology == 0) {
                topology = GL46C.glGenBuffers(); keys = GL46C.glGenBuffers(); indices = GL46C.glGenBuffers();
                rows = GL46C.glGenBuffers(); command = GL46C.glGenBuffers();
            }
            if (!Arrays.equals(uploadedTopology, triangles)) {
                GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, topology);
                GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, triangles, GL46C.GL_STATIC_DRAW);
                uploadedTopology = triangles.clone();
                TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.UPLOAD_BYTES, triangles.length * 4L);
            }
            if (capacity < required) {
                capacity = required;
                allocate(keys, capacity * 8L); allocate(indices, capacity * 12L); allocate(rows, capacity * 4L);
            }
            // Use only required entries even if an earlier topology needed a larger allocation.
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, command);
            GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, new int[]{0, 1, 0, 0, 0}, GL46C.GL_STREAM_DRAW);
            bytes = (long) uploadedTopology.length * 4 + capacity * 24L + 20;
            GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, 0, input.positionBuffer());
            GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, 1, topology);
            GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, 3, rows);
            GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, 4, keys);
            GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, 5, indices);
            GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, 6, command);
            GL46C.glUseProgram(program);
            uniform("TriangleCount", n); uniform("Capacity", required);
            GL46C.glUniform3f(location("DepthRow"), input.modelView().get(2), input.modelView().get(6), input.modelView().get(10));
            GL46C.glUniform1i(location("ClipEnabled"), input.projection() == null ? 0 : 1);
            if (input.projection() != null) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    FloatBuffer clip = stack.mallocFloat(16);
                    new Matrix4f().set(input.projection()).mul(new Matrix4f().set(input.modelView())).get(clip);
                    GL46C.glUniformMatrix4fv(location("ClipMatrix"), false, clip);
                }
            }
            GL46C.glMemoryBarrier(GL46C.GL_SHADER_STORAGE_BARRIER_BIT);
            dispatch(0, required);
            // The final tile must be ascending when the whole sort fits in a tile.
            if (required <= 128) {
                for (int width = 2; width <= required; width <<= 1) {
                    uniform("SortWidth", width);
                    for (int stride = width >> 1; stride > 0; stride >>= 1) {
                        uniform("SortStride", stride); dispatch(1, required);
                    }
                }
            } else {
                dispatch(3, required);
                for (int width = 256; width <= required; width <<= 1) {
                    uniform("SortWidth", width);
                    for (int stride = width >> 1; stride >= 128; stride >>= 1) {
                        uniform("SortStride", stride); dispatch(1, required);
                    }
                    dispatch(4, required);
                }
            }
            dispatch(2, required);
            GL46C.glMemoryBarrier(GL46C.GL_ELEMENT_ARRAY_BARRIER_BIT | GL46C.GL_SHADER_STORAGE_BARRIER_BIT
                    | GL46C.GL_COMMAND_BARRIER_BIT);
            TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.GPU_PREPARE, 1);
            return true;
        } catch (IllegalStateException e) {
            failed = true;
            org.apache.logging.log4j.LogManager.getLogger().warn("GPU translucent preparation disabled: {}", e.getMessage());
            return false;
        } finally {
            GL46C.glUseProgram(previousProgram);
            for (int i = 0; i < slots.length; i++) bindings[i].restore(slots[i]);
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, generic);
        }
    }

    static int capacityFor(int count) {
        if (count <= 0 || count > (1 << 24)) return 0;
        return count == 1 ? 1 : Integer.highestOneBit(count - 1) << 1;
    }
    private static void allocate(int buffer, long bytes) {
        GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, buffer);
        GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, bytes, GL46C.GL_DYNAMIC_DRAW);
    }
    private int location(String name) { return GL46C.glGetUniformLocation(program, name); }
    private void uniform(String name, int value) { GL46C.glUniform1ui(location(name), value); }
    private void dispatch(int mode, int size) {
        GL46C.glUniform1i(location("Mode"), mode);
        GL46C.glDispatchCompute((size + 127) / 128, 1, 1);
        GL46C.glMemoryBarrier(GL46C.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    private static int createProgram() {
        int shader = GL46C.glCreateShader(GL46C.GL_COMPUTE_SHADER), result = 0;
        try {
            GL46C.glShaderSource(shader, AssetsUtil.getAssetsAsString("shader/translucent_prepare.comp.glsl"));
            GL46C.glCompileShader(shader);
            if (GL46C.glGetShaderi(shader, GL46C.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL46C.glGetShaderInfoLog(shader));
            result = GL46C.glCreateProgram(); GL46C.glAttachShader(result, shader); GL46C.glLinkProgram(result);
            if (GL46C.glGetProgrami(result, GL46C.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL46C.glGetProgramInfoLog(result));
            return result;
        } catch (RuntimeException e) {
            if (result != 0) GL46C.glDeleteProgram(result);
            throw e;
        } finally { GL46C.glDeleteShader(shader); }
    }
    int indexBuffer() { return indices; }
    int rowsBuffer() { return rows; }
    int commandBuffer() { return command; }
    long vramBytes() { return bytes; }
    long ramBytes() { return uploadedTopology == null ? 0 : uploadedTopology.length * 4L; }

    record BufferBinding(int buffer, long start, long size) {
        static BufferBinding capture(int slot) {
            return new BufferBinding(GL46C.glGetIntegeri(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, slot),
                    GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_START, slot),
                    GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_SIZE, slot));
        }
        void restore(int slot) {
            if (buffer != 0 && size > 0) GL46C.glBindBufferRange(GL46C.GL_SHADER_STORAGE_BUFFER, slot, buffer, start, size);
            else GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, slot, buffer);
        }
    }
    @Override public void close() {
        for (int buffer : new int[]{topology, keys, indices, rows, command}) if (buffer != 0) GL46C.glDeleteBuffers(buffer);
        if (program != 0) GL46C.glDeleteProgram(program);
        topology = keys = indices = rows = command = program = capacity = 0;
        uploadedTopology = null; bytes = 0;
    }
}
