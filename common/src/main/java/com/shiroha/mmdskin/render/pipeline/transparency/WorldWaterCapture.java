package com.shiroha.mmdskin.render.pipeline.transparency;

import org.lwjgl.opengl.GL46C;

/** A native Iris water program keeps its auxiliary outputs/depth; only primary RGB joins OIT. */
public final class WorldWaterCapture implements AutoCloseable {
    private static WorldWaterCapture active;
    private static int snapshotProgram;
    private final int program, enabled, generic;
    private final Range first, second;
    private final boolean blend;
    private final int rgbEquation, alphaEquation, srcRgb, dstRgb, srcAlpha, dstAlpha;
    private boolean closed;

    private WorldWaterCapture(int program, int enabled, int metaSlot, int nodeSlot) {
        this.program = program; this.enabled = enabled;
        generic = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
        first = new Range(metaSlot); second = new Range(nodeSlot);
        blend = GL46C.glIsEnabledi(GL46C.GL_BLEND, 0);
        rgbEquation = GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_RGB, 0);
        alphaEquation = GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_ALPHA, 0);
        srcRgb = GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_RGB, 0);
        dstRgb = GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_RGB, 0);
        srcAlpha = GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_ALPHA, 0);
        dstAlpha = GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_ALPHA, 0);
    }

    static WorldWaterCapture begin(int program, int meta, int nodes, int metaSlot, int nodeSlot,
                                   int capacity, int[] viewport) {
        int enabled = GL46C.glGetUniformLocation(program, "MmdWaterCapture");
        if (enabled < 0) return null;
        int heads = GL46C.glGetProgramResourceIndex(program, GL46C.GL_SHADER_STORAGE_BLOCK, "MmdWaterHeads");
        int fragments = GL46C.glGetProgramResourceIndex(program, GL46C.GL_SHADER_STORAGE_BLOCK, "MmdWaterNodes");
        if (heads == GL46C.GL_INVALID_INDEX || fragments == GL46C.GL_INVALID_INDEX) return null;
        for (int i = 0; i < GL46C.glGetProgramInterfacei(program, GL46C.GL_SHADER_STORAGE_BLOCK,
                GL46C.GL_ACTIVE_RESOURCES); i++) {
            if (i == heads || i == fragments) continue;
            int[] binding = new int[1];
            GL46C.glGetProgramResourceiv(program, GL46C.GL_SHADER_STORAGE_BLOCK, i,
                    new int[]{GL46C.GL_BUFFER_BINDING}, null, binding);
            if (binding[0] == metaSlot || binding[0] == nodeSlot) return null;
        }
        endActive();
        WorldWaterCapture scope = new WorldWaterCapture(program, enabled, metaSlot, nodeSlot);
        try {
            GL46C.glShaderStorageBlockBinding(program, heads, metaSlot);
            GL46C.glShaderStorageBlockBinding(program, fragments, nodeSlot);
            GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, metaSlot, meta);
            GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, nodeSlot, nodes);
            GL46C.glProgramUniform4i(program, GL46C.glGetUniformLocation(program, "MmdViewport"),
                    viewport[0], viewport[1], viewport[2], viewport[3]);
            GL46C.glProgramUniform1ui(program, GL46C.glGetUniformLocation(program, "MmdCapacity"), capacity);
            snapshotAllocation(program, metaSlot);
            GL46C.glMemoryBarrier(GL46C.GL_SHADER_STORAGE_BARRIER_BIT);
            GL46C.glEnablei(GL46C.GL_BLEND, 0);
            GL46C.glBlendEquationSeparatei(0, GL46C.GL_FUNC_ADD,
                    scope.blend ? scope.alphaEquation : GL46C.GL_FUNC_ADD);
            GL46C.glBlendFuncSeparatei(0, GL46C.GL_ZERO, GL46C.GL_ONE,
                    scope.blend ? scope.srcAlpha : GL46C.GL_ONE,
                    scope.blend ? scope.dstAlpha : GL46C.GL_ZERO);
            GL46C.glProgramUniform1i(program, enabled, 1);
            return active = scope;
        } catch (RuntimeException error) { scope.close(); throw error; }
    }

    public static void endActive() { if (active != null) active.close(); }

    /** One GPU invocation replaces the extra global counter atomic on every water fragment. */
    private static void snapshotAllocation(int waterProgram, int metaSlot) {
        if (snapshotProgram == 0) {
            int shader = TransparencyProgram.compile(GL46C.GL_COMPUTE_SHADER, """
                    #version 430 core
                    layout(local_size_x=1) in;
                    layout(std430) buffer MmdWaterSnapshot {
                        uint allocated; uint overflow; uint beforeWater;
                    };
                    void main() { beforeWater = allocated; }
                    """);
            int linked = GL46C.glCreateProgram();
            try {
                GL46C.glAttachShader(linked, shader);
                GL46C.glLinkProgram(linked);
                if (GL46C.glGetProgrami(linked, GL46C.GL_LINK_STATUS) == 0)
                    throw new IllegalStateException(GL46C.glGetProgramInfoLog(linked));
                snapshotProgram = linked;
            } finally {
                GL46C.glDeleteShader(shader);
                if (snapshotProgram == 0) GL46C.glDeleteProgram(linked);
            }
        }
        GL46C.glShaderStorageBlockBinding(snapshotProgram, 0, metaSlot);
        GL46C.glMemoryBarrier(GL46C.GL_SHADER_STORAGE_BARRIER_BIT);
        try {
            GL46C.glUseProgram(snapshotProgram);
            GL46C.glDispatchCompute(1, 1, 1);
        } finally { GL46C.glUseProgram(waterProgram); }
    }

    static void release() {
        endActive();
        if (snapshotProgram != 0) GL46C.glDeleteProgram(snapshotProgram);
        snapshotProgram = 0;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        GL46C.glProgramUniform1i(program, enabled, 0);
        GL46C.glMemoryBarrier(GL46C.GL_SHADER_STORAGE_BARRIER_BIT);
        GL46C.glBlendEquationSeparatei(0, rgbEquation, alphaEquation);
        GL46C.glBlendFuncSeparatei(0, srcRgb, dstRgb, srcAlpha, dstAlpha);
        if (blend) GL46C.glEnablei(GL46C.GL_BLEND, 0); else GL46C.glDisablei(GL46C.GL_BLEND, 0);
        first.restore(); second.restore();
        GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, generic);
        if (active == this) active = null;
    }

    private record Range(int slot, int buffer, long start, long size) {
        Range(int slot) {
            this(slot, GL46C.glGetIntegeri(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, slot),
                    GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_START, slot),
                    GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_SIZE, slot));
        }
        void restore() {
            if (buffer != 0 && size > 0)
                GL46C.glBindBufferRange(GL46C.GL_SHADER_STORAGE_BUFFER, slot, buffer, start, size);
            else GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, slot, buffer);
        }
    }
}
