package com.shiroha.mmdskin.render.material;

import com.shiroha.mmdskin.render.pipeline.TranslucentDiagnostics;
import com.shiroha.mmdskin.render.shader.TranslucentBatchShader;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

/** Per-model buffers for ordered, multi-material submission on the vanilla Toon program. */
final class TranslucentBatchSubmission implements AutoCloseable {
    private static final int ROW_BYTES = 96;
    private int materialsBuffer, trianglesBuffer;
    private ByteBuffer rows, triangles;
    private long uploadedBytes;
    private long materialBytes, triangleBytesAllocated;

    static boolean supports(TranslucentBatchPlan plan) {
        long bytes = (long) plan.rows.size() * ROW_BYTES;
        return bytes <= Integer.MAX_VALUE && bytes <= GL46C.glGetInteger64(GL46C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
    }

    /** Returns actual draw calls, or -1 before drawing anything when the compatibility path is needed. */
    int draw(ByteBuffer runs, int count, ModelMaterial[] materials, DrawMaterialCache materialCache) {
        var uniforms = TranslucentBatchShader.current();
        if (uniforms == null || count == 0) return -1;
        // Legacy diffuse sampling inherits unit 0's sampler; the table uses texture object parameters.
        if (GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, 0) != 0) return -1;
        var plan = TranslucentBatchPlan.build(runs, count, materials, materialCache::texture);
        return drawPlan(plan, count, materials, materialCache, null);
    }

    int drawGpu(TranslucentBatchPlan plan, ModelMaterial[] materials,
                DrawMaterialCache materialCache, GpuTranslucentPreparation device) {
        return drawPlan(plan, plan.rows.size(), materials, materialCache, device);
    }

    private int drawPlan(TranslucentBatchPlan plan, int count, ModelMaterial[] materials,
                         DrawMaterialCache materialCache, GpuTranslucentPreparation device) {
        var uniforms = TranslucentBatchShader.current();
        if (uniforms == null) return -1;
        long rowBytes = (long) plan.rows.size() * ROW_BYTES;
        long triangleBytes = (long) plan.triangleRows.length * Integer.BYTES;
        long maxBlock = GL46C.glGetInteger64(GL46C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        if (rowBytes > maxBlock || triangleBytes > maxBlock
                || rowBytes > Integer.MAX_VALUE || triangleBytes > Integer.MAX_VALUE) return -1;
        rows = resize(rows, (int) rowBytes);
        if (device == null) triangles = resize(triangles, (int) triangleBytes);
        for (var row : plan.rows) {
            var key = row.key();
            ModelMaterial material = materials[key.material()];
            MaterialRenderState state = materialCache.resolve(key.material(), Float.intBitsToFloat(key.alphaBits()));
            rows.putFloat(state.color[0]).putFloat(state.color[1]).putFloat(state.color[2]).putFloat(state.alpha);
            rows.putFloat(state.specular[0]).putFloat(state.specular[1]).putFloat(state.specular[2]).putFloat(state.specularPower);
            for (float value : state.sphereMul) rows.putFloat(value);
            for (float value : state.sphereAdd) rows.putFloat(value);
            rows.putFloat(material.eyeEmissionMaskMode()).putFloat(row.sphereSlot() < 0 ? 0 : material.sphereMode)
                    .putFloat(row.diffuseSlot()).putFloat(row.sphereSlot());
            rows.putFloat(key.bothFace() ? 1 : 0).putFloat(0).putFloat(0).putFloat(0);
        }
        if (device == null) for (int row : plan.triangleRows) triangles.putInt(row);
        rows.flip();
        if (device == null) triangles.flip();

        int genericBuffer = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
        Binding materialBinding = Binding.capture(2), triangleBinding = Binding.capture(3);
        int activeTexture = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        int[] savedTextures = new int[TranslucentBatchPlan.TEXTURE_SLOTS];
        int[] savedSamplers = new int[savedTextures.length];
        boolean cull = GL46C.glIsEnabled(GL46C.GL_CULL_FACE);
        int cullMode = GL46C.glGetInteger(GL46C.GL_CULL_FACE_MODE);
        int enabled = GL46C.glGetUniformi(uniforms.program(), uniforms.enabled());
        int oldPass = GL46C.glGetUniformi(uniforms.program(), uniforms.alphaPass());
        int alphaPass = uniforms.alphaPass();
        int oldIndirect = GL46C.glGetInteger(GL46C.GL_DRAW_INDIRECT_BUFFER_BINDING);
        // All raw state changes are restored, including indexed SSBO ranges and sampler objects.
        for (int i = 0; i < savedTextures.length; i++) {
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + i);
            savedTextures[i] = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
            savedSamplers[i] = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, i);
        }
        try {
            if (materialsBuffer == 0) materialsBuffer = GL46C.glGenBuffers();
            if (device == null && trianglesBuffer == 0) trianglesBuffer = GL46C.glGenBuffers();
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, materialsBuffer);
            GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, rows, GL46C.GL_STREAM_DRAW);
            GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, 2, materialsBuffer);
            if (device == null) {
                GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, trianglesBuffer);
                GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, triangles, GL46C.GL_STREAM_DRAW);
            }
            GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, 3, device == null ? trianglesBuffer : device.rowsBuffer());
            materialBytes = rowBytes;
            if (device == null) triangleBytesAllocated = triangleBytes;
            uploadedBytes = rowBytes + triangleBytes;
            TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.UPLOAD_BYTES, uploadedBytes);
            GL46C.glUniform1i(uniforms.enabled(), 1);
            GL46C.glUniform1i(alphaPass, 2);
            GL46C.glUniform1i(uniforms.cullMode(), cullMode == GL46C.GL_BACK ? 0 : cullMode == GL46C.GL_FRONT ? 1 : 2);
            GL46C.glUniform1iv(uniforms.textures(), new int[]{0, 1, 2, 3, 4, 5, 6, 7});
            GL46C.glDisable(GL46C.GL_CULL_FACE);
            for (var window : plan.windows) {
                for (int slot = 0; slot < window.textures().length; slot++) {
                    GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + slot);
                    GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, window.textures()[slot]);
                    GL46C.glBindSampler(slot, 0);
                }
                GL46C.glUniform1i(uniforms.triangleOffset(), window.firstIndex() / 3);
                if (device == null) {
                    GL46C.glDrawElements(GL46C.GL_TRIANGLES, window.indexCount(), GL46C.GL_UNSIGNED_INT,
                            (long) window.firstIndex() * Integer.BYTES);
                } else {
                    GL46C.glBindBuffer(GL46C.GL_DRAW_INDIRECT_BUFFER, device.commandBuffer());
                    GL46C.glDrawElementsIndirect(GL46C.GL_TRIANGLES, GL46C.GL_UNSIGNED_INT, 0L);
                }
            }
            TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.BATCH_SUBMISSIONS, 1);
            TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.BATCH_RUNS, count);
            return plan.windows.size();
        } finally {
            GL46C.glBindBuffer(GL46C.GL_DRAW_INDIRECT_BUFFER, oldIndirect);
            GL46C.glUniform1i(uniforms.enabled(), enabled);
            GL46C.glUniform1i(alphaPass, oldPass);
            if (cull) GL46C.glEnable(GL46C.GL_CULL_FACE); else GL46C.glDisable(GL46C.GL_CULL_FACE);
            for (int i = 0; i < savedTextures.length; i++) {
                GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + i);
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, savedTextures[i]);
                GL46C.glBindSampler(i, savedSamplers[i]);
            }
            GL46C.glActiveTexture(activeTexture);
            materialBinding.restore(2);
            triangleBinding.restore(3);
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, genericBuffer);
        }
    }

    private record Binding(int buffer, long start, long size) {
        static Binding capture(int slot) {
            return new Binding(GL46C.glGetIntegeri(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, slot),
                    GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_START, slot),
                    GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_SIZE, slot));
        }
        void restore(int slot) {
            if (buffer != 0 && size > 0) GL46C.glBindBufferRange(GL46C.GL_SHADER_STORAGE_BUFFER, slot, buffer, start, size);
            else GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, slot, buffer);
        }
    }

    private static ByteBuffer resize(ByteBuffer buffer, int bytes) {
        if (buffer == null || buffer.capacity() < bytes) {
            if (buffer != null) MemoryUtil.memFree(buffer);
            buffer = MemoryUtil.memAlloc(Math.max(4, bytes));
        }
        return buffer.clear().limit(bytes);
    }

    long ramBytes() { return (rows == null ? 0 : rows.capacity()) + (triangles == null ? 0 : triangles.capacity()); }
    long vramBytes() { return materialBytes + triangleBytesAllocated; }

    @Override public void close() {
        if (materialsBuffer != 0) GL46C.glDeleteBuffers(materialsBuffer);
        if (trianglesBuffer != 0) GL46C.glDeleteBuffers(trianglesBuffer);
        materialsBuffer = trianglesBuffer = 0;
        if (rows != null) MemoryUtil.memFree(rows);
        if (triangles != null) MemoryUtil.memFree(triangles);
        rows = triangles = null;
        uploadedBytes = 0;
        materialBytes = triangleBytesAllocated = 0;
    }
}
