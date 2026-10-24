package com.shiroha.mmdskin.render.pipeline.transparency;

import org.lwjgl.opengl.GL46C;

/** Consume only pixels resolved by the current water batch; retain the rest for the late stage. */
final class WaterResolveStage implements AutoCloseable {
    private int program;

    static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("mmdskin.gpuWaterDecision"));
    }

    void clearResolvedPixels(int meta, int slot, int width, int height) {
        if (program == 0) init();
        int previousProgram = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        int generic = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
        int indexed = GL46C.glGetIntegeri(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, slot);
        long offset = GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_START, slot);
        long size = GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_SIZE, slot);
        try {
            GL46C.glMemoryBarrier(GL46C.GL_SHADER_STORAGE_BARRIER_BIT);
            GL46C.glUseProgram(program);
            GL46C.glShaderStorageBlockBinding(program, 0, slot);
            GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, slot, meta);
            GL46C.glUniform2i(GL46C.glGetUniformLocation(program, "Size"), width, height);
            GL46C.glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
            GL46C.glMemoryBarrier(GL46C.GL_SHADER_STORAGE_BARRIER_BIT | GL46C.GL_BUFFER_UPDATE_BARRIER_BIT);
        } finally {
            if (indexed != 0 && size > 0)
                GL46C.glBindBufferRange(GL46C.GL_SHADER_STORAGE_BUFFER, slot, indexed, offset, size);
            else GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, slot, indexed);
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, generic);
            GL46C.glUseProgram(previousProgram);
        }
    }

    private void init() {
        int shader = TransparencyProgram.compile(GL46C.GL_COMPUTE_SHADER, """
                #version 430 core
                layout(local_size_x=16, local_size_y=16) in;
                struct Pixel { uint head; uint r; uint g; uint b; uint alpha; uint opacity; uint nearest; };
                layout(std430) buffer Metadata {
                    uint allocated; uint overflow; uint beforeWater; uint reserved;
                    Pixel pixels[];
                };
                uniform ivec2 Size;
                void main() {
                    uvec2 p = gl_GlobalInvocationID.xy;
                    if (p.x >= uint(Size.x) || p.y >= uint(Size.y)) return;
                    uint i = p.y * uint(Size.x) + p.x;
                    // On overflow the existing weighted tail has no per-node water identity.
                    bool water = pixels[i].head > beforeWater || (overflow != 0u && allocated != beforeWater);
                    if (water) pixels[i] = Pixel(0u, 0u, 0u, 0u, 0u, 0u, 0u);
                }
                """);
        int linked = GL46C.glCreateProgram();
        try {
            GL46C.glAttachShader(linked, shader); GL46C.glLinkProgram(linked);
            if (GL46C.glGetProgrami(linked, GL46C.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL46C.glGetProgramInfoLog(linked));
            program = linked;
        } finally {
            GL46C.glDeleteShader(shader);
            if (program == 0) GL46C.glDeleteProgram(linked);
        }
    }

    @Override public void close() {
        if (program != 0) GL46C.glDeleteProgram(program);
        program = 0;
    }
}
