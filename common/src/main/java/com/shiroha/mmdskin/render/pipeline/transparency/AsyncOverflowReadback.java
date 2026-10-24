package com.shiroha.mmdskin.render.pipeline.transparency;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

/** Bounded, delayed diagnostic reads. A busy GPU never makes the render thread wait for a fence. */
final class AsyncOverflowReadback implements AutoCloseable {
    private final int[] buffers = new int[3];
    private final long[] fences = new long[3];
    private Object context;

    static boolean enabled() { return !Boolean.getBoolean("mmdskin.syncWorldTransparencyOverflow"); }

    private void refreshContext() {
        Object next = GL.getCapabilities();
        if (next == context) return;
        java.util.Arrays.fill(buffers, 0); java.util.Arrays.fill(fences, 0); context = next;
    }

    boolean issue(int source) {
        refreshContext();
        int slot = -1;
        for (int i = 0; i < fences.length; i++) if (fences[i] == 0) { slot = i; break; }
        if (slot < 0) return false; // Dropping a warning sample must not stall rendering.
        int read = GL46C.glGetInteger(GL46C.GL_COPY_READ_BUFFER_BINDING);
        int write = GL46C.glGetInteger(GL46C.GL_COPY_WRITE_BUFFER_BINDING);
        try {
            if (buffers[slot] == 0) {
                buffers[slot] = GL46C.glGenBuffers();
                GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, buffers[slot]);
                GL46C.glBufferData(GL46C.GL_COPY_WRITE_BUFFER, 4, GL46C.GL_STREAM_READ);
            }
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, source);
            GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, buffers[slot]);
            GL46C.glCopyBufferSubData(GL46C.GL_COPY_READ_BUFFER, GL46C.GL_COPY_WRITE_BUFFER, 4, 0, 4);
            fences[slot] = GL46C.glFenceSync(GL46C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            return true;
        } finally {
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, read);
            GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, write);
        }
    }

    Integer poll() {
        refreshContext();
        if (fences[0] == 0 && fences[1] == 0 && fences[2] == 0) return null;
        int old = GL46C.glGetInteger(GL46C.GL_COPY_READ_BUFFER_BINDING);
        Integer result = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var value = stack.mallocInt(1);
            for (int i = 0; i < fences.length; i++) {
                if (fences[i] == 0) continue;
                int status = GL46C.glClientWaitSync(fences[i], 0, 0);
                if (status == GL46C.GL_TIMEOUT_EXPIRED) continue;
                if (status == GL46C.GL_ALREADY_SIGNALED || status == GL46C.GL_CONDITION_SATISFIED) {
                    GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, buffers[i]);
                    GL46C.glGetBufferSubData(GL46C.GL_COPY_READ_BUFFER, 0, value);
                    if (value.get(0) != 0 || result == null) result = value.get(0);
                }
                GL46C.glDeleteSync(fences[i]); fences[i] = 0;
            }
        } finally { GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, old); }
        return result;
    }

    @Override public void close() {
        if (context == GL.getCapabilities()) {
            for (long fence : fences) if (fence != 0) GL46C.glDeleteSync(fence);
            for (int buffer : buffers) if (buffer != 0) GL46C.glDeleteBuffers(buffer);
        }
        java.util.Arrays.fill(buffers, 0); java.util.Arrays.fill(fences, 0); context = null;
    }
}
