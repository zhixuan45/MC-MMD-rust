package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.*;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Readbacks occur only in this oracle, never in the production GPU sort/draw chain. */
public class GpuPreparationGlProbe {
    static ByteBuffer buffer(int size) { return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()); }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static FloatBuffer matrix(Matrix4f matrix) { FloatBuffer b = buffer(64).asFloatBuffer(); matrix.get(b); return b; }
    public static void main(String[] args) {
        check(GLFW.glfwInit(), "GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = GLFW.glfwCreateWindow(64, 64, "GPU preparation probe", 0, 0);
        check(window != 0, "GL 4.3 window");
        try { GLFW.glfwMakeContextCurrent(window); GL.createCapabilities(); run(); }
        finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }
    static void run() {
        RenderSystem.initRenderThread();
        check(GpuTranslucentPreparation.enabled(), "GPU preparation enabled");
        check(GpuTranslucentPreparation.capacityFor(0) == 0, "empty capacity");
        check(GpuTranslucentPreparation.capacityFor((1 << 24) + 1) == 0, "capacity guard");
        int positions = GL46C.glGenBuffers(), host = GL46C.glGenBuffers();
        int alignment = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, host);
        GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, alignment + 256, GL46C.GL_STATIC_DRAW);
        for (int slot : new int[]{0, 1, 3, 4, 5, 6})
            GL46C.glBindBufferRange(GL46C.GL_SHADER_STORAGE_BUFFER, slot, host, alignment, 128);
        FloatBuffer view = matrix(new Matrix4f().rotateY(.31f).rotateX(-.17f));
        FloatBuffer projection = matrix(new Matrix4f());
        int cases = 0;
        try (var device = new GpuTranslucentPreparation()) {
            for (int count : new int[]{1, 2, 3, 127, 128, 129, 255, 256, 257, 1023, 4097, 7}) {
                for (boolean clip : new boolean[]{false, true}) {
                    compare(device, positions, count, view, clip ? projection : null, false, host, alignment);
                    cases++;
                }
            }
            compare(device, positions, 257, view, projection, true, host, alignment); cases++;
            check(!device.prepare(new TranslucentDrawOrder.Inputs(null, positions, 0, view, 1, false), new int[0]), "empty topology fallback");
            device.close(); device.close();
            check(device.ramBytes() == 0 && device.vramBytes() == 0, "idempotent resource release");
        }
        GL46C.glDeleteBuffers(positions); GL46C.glDeleteBuffers(host);
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL error");
        System.out.println("GpuPreparationGlProbe PASS: " + cases + " CPU/GPU oracles; clipping, stable ties, NaN/zero, tile boundaries, resize, SSBO state, cleanup");
    }
    static void compare(GpuTranslucentPreparation device, int positions, int count, FloatBuffer view,
                        FloatBuffer projection, boolean allClipped, int host, int alignment) {
        ByteBuffer data = buffer(count * 36);
        int[] topology = new int[count * 4];
        Random random = new Random(9182 + count);
        for (int i = 0; i < count; i++) {
            float x = allClipped ? 20 : (random.nextInt(9) - 4) * .25f;
            float y = allClipped ? 20 : (random.nextInt(7) - 3) * .25f;
            float z = (random.nextInt(11) - 5) * .25f;
            if (!allClipped && i % 31 == 0) { x = y = 0; z = i % 62 == 0 ? 0.0f : -0.0f; }
            if (!allClipped && i % 101 == 100) z = Float.NaN;
            for (int v = 0; v < 3; v++) data.putFloat(x).putFloat(y).putFloat(z);
            topology[i * 4] = i * 3; topology[i * 4 + 1] = i * 3 + 1;
            topology[i * 4 + 2] = i * 3 + 2; topology[i * 4 + 3] = i % 7;
        }
        data.flip();
        GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, positions);
        GL46C.glBufferData(GL46C.GL_COPY_WRITE_BUFFER, data, GL46C.GL_DYNAMIC_DRAW);
        ArrayList<Long> expected = new ArrayList<>();
        Matrix4f clip = projection == null ? null : new Matrix4f().set(projection).mul(new Matrix4f().set(view));
        for (int i = 0; i < count; i++) {
            float sum = 0; int outside = 63;
            for (int v = 0; v < 3; v++) {
                int offset = (i * 3 + v) * 12;
                float x = data.getFloat(offset), y = data.getFloat(offset + 4), z = data.getFloat(offset + 8);
                sum += view.get(2) * x + view.get(6) * y + view.get(10) * z;
                outside &= clipCode(clip, x, y, z);
            }
            if (outside != 0) continue;
            int bits = Float.floatToIntBits(sum), ordered = bits ^ ((bits >> 31) & 0x7fffffff);
            expected.add(((long) ordered << 32) | (i & 0xffffffffL));
        }
        Collections.sort(expected);
        int savedProgram = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, host);
        var input = new TranslucentDrawOrder.Inputs(null, positions, count * 3, view, projection, count, false, null, 0, 0);
        check(device.prepare(input, topology), "prepare count=" + count);
        check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM) == savedProgram, "program restored");
        check(GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING) == host, "generic SSBO restored");
        for (int slot : new int[]{0, 1, 3, 4, 5, 6}) {
            check(GL46C.glGetIntegeri(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, slot) == host, "indexed SSBO restored");
            check(GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_START, slot) == alignment, "SSBO range restored");
            check(GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_SIZE, slot) == 128, "SSBO range length restored");
        }
        GL46C.glMemoryBarrier(GL46C.GL_BUFFER_UPDATE_BARRIER_BIT);
        ByteBuffer command = buffer(20);
        GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, device.commandBuffer());
        GL46C.glGetBufferSubData(GL46C.GL_COPY_READ_BUFFER, 0, command);
        check(command.getInt(0) == expected.size() * 3 && command.getInt(4) == 1
                && command.getInt(8) == 0 && command.getInt(12) == 0 && command.getInt(16) == 0, "indirect command");
        if (!expected.isEmpty()) {
            ByteBuffer indices = buffer(expected.size() * 12), rows = buffer(expected.size() * 4);
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, device.indexBuffer());
            GL46C.glGetBufferSubData(GL46C.GL_COPY_READ_BUFFER, 0, indices);
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, device.rowsBuffer());
            GL46C.glGetBufferSubData(GL46C.GL_COPY_READ_BUFFER, 0, rows);
            for (int i = 0; i < expected.size(); i++) {
                int ordinal = (int) (long) expected.get(i);
                for (int v = 0; v < 3; v++) check(indices.getInt((i * 3 + v) * 4) == topology[ordinal * 4 + v],
                        "sort mismatch count=" + count + " clip=" + (projection!=null) + " triangle=" + i
                        + " expected=" + ordinal + " actual=" + indices.getInt(i*12)/3
                        + " key=" + Long.toHexString(expected.get(i))
                        + " expectedPosition=" + data.getFloat(ordinal*36) + "," + data.getFloat(ordinal*36+4) + "," + data.getFloat(ordinal*36+8)
                        + " actualPosition=" + data.getFloat(indices.getInt(i*12)*12) + "," + data.getFloat(indices.getInt(i*12)*12+4) + "," + data.getFloat(indices.getInt(i*12)*12+8));
                check(rows.getInt(i * 4) == topology[ordinal * 4 + 3], "material row follows sorted triangle");
            }
        }
    }
    static int clipCode(Matrix4f m, float x, float y, float z) {
        if (m == null) return 0;
        float cx = m.m00()*x + m.m10()*y + m.m20()*z + m.m30();
        float cy = m.m01()*x + m.m11()*y + m.m21()*z + m.m31();
        float cz = m.m02()*x + m.m12()*y + m.m22()*z + m.m32();
        float cw = m.m03()*x + m.m13()*y + m.m23()*z + m.m33();
        return (cx < -cw ? 1 : 0) | (cx > cw ? 2 : 0) | (cy < -cw ? 4 : 0)
                | (cy > cw ? 8 : 0) | (cz < -cw ? 16 : 0) | (cz > cw ? 32 : 0);
    }
}
