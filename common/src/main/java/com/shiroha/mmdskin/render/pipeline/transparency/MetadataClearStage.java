package com.shiroha.mmdskin.render.pipeline.transparency;

import org.lwjgl.opengl.GL46C;

/** Linear compute clear for the complete world-transparency metadata buffer. */
final class MetadataClearStage implements AutoCloseable {
    private int program;
    private boolean failed;

    static boolean enabled() {
        String override = System.getProperty("mmdskin.metadataClearCompute");
        if (override != null) return Boolean.parseBoolean(override);
        // This path has a measured benefit on NVIDIA; retain the driver clear on unmeasured vendors.
        String vendor = GL46C.glGetString(GL46C.GL_VENDOR);
        return vendor != null && vendor.contains("NVIDIA");
    }

    /** Returns false when the caller should use its glClearBufferData fallback. */
    boolean clear(int buffer, int slot, long byteSize) {
        if (!enabled() || failed || buffer <= 0 || slot < 0 || byteSize <= 0 || (byteSize & 3L) != 0L)
            return false;
        long wordCount = byteSize / Integer.BYTES;
        if (wordCount > Integer.MAX_VALUE) return false;
        int groups = (int) ((wordCount + 255L) / 256L);
        if (groups > GL46C.glGetIntegeri(GL46C.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0)) return false;
        try {
            if (program == 0) init();
            int previousProgram = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
            int generic = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
            int indexed = GL46C.glGetIntegeri(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, slot);
            long offset = GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_START, slot);
            long size = GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_SIZE, slot);
            try {
                GL46C.glUseProgram(program);
                GL46C.glShaderStorageBlockBinding(program, 0, slot);
                GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, slot, buffer);
                GL46C.glUniform1ui(GL46C.glGetUniformLocation(program, "WordCount"), (int) wordCount);
                GL46C.glDispatchCompute(groups, 1, 1);
                GL46C.glMemoryBarrier(GL46C.GL_SHADER_STORAGE_BARRIER_BIT
                        | GL46C.GL_BUFFER_UPDATE_BARRIER_BIT);
            } finally {
                if (indexed != 0 && size > 0)
                    GL46C.glBindBufferRange(GL46C.GL_SHADER_STORAGE_BUFFER, slot, indexed, offset, size);
                else GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, slot, indexed);
                GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, generic);
                GL46C.glUseProgram(previousProgram);
            }
            return true;
        } catch (RuntimeException failure) {
            failed = true;
            return false;
        }
    }

    private void init() {
        int shader = TransparencyProgram.compile(GL46C.GL_COMPUTE_SHADER, """
                #version 430 core
                layout(local_size_x = 256) in;
                layout(std430) buffer Metadata { uint words[]; };
                uniform uint WordCount;
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index < WordCount) words[index] = 0u;
                }
                """);
        int linked = GL46C.glCreateProgram();
        try {
            GL46C.glAttachShader(linked, shader);
            GL46C.glLinkProgram(linked);
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
        failed = false;
    }
}
