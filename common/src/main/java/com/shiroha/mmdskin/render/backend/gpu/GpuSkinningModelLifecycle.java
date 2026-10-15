package com.shiroha.mmdskin.render.backend.gpu;

import com.shiroha.mmdskin.render.shader.ShaderConstants;
import com.shiroha.mmdskin.render.material.MaterialTextureLoader;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

/** 文件职责：释放并统计 GPU skinning 模型实例资源。 */
final class GpuSkinningModelLifecycle {
    private GpuSkinningModelLifecycle() {
    }

    static long getVramUsage(GpuSkinningModelInstance target) {
        if (!target.initialized) {
            return 0;
        }

        long total = 0;
        total += target.staticBuffers.stream().mapToLong(ImmutableGpuBufferPool.Lease::accountedBytes).sum();
        total += target.nativeBackendPort().getFirstPersonIndexCount(target.nativeModelHandle()) > 0
                ? target.nativeBackendPort().getIndexCount(target.nativeModelHandle()) * target.indexElementSize : 0;
        total += (long) target.vertexCount * (8 + 12 * 2); // light coordinates and skinned position/normal
        total += (long) ShaderConstants.MAX_BONES * 64;
        total += target.getEyeEmissionVramUsage();
        total += target.translucentOrders.vramBytes();
        if (target.vertexMorphCount > 0) {
            total += (long) target.vertexMorphCount * 4;
        }
        if (target.uvMorphCount > 0) {
            total += (long) target.uvMorphCount * 4;
        }
        if (target.skinnedUvBuffer > 0) {
            total += (long) target.vertexCount * 8;
        }
        // 默认纹理属于模型实例，不计入文件贴图共享缓存。
        for (var material : target.mats) {
            if (material != null && material.ownsTexture && material.tex > 0) total += 4;
        }
        return total;
    }

    static long getRamUsage(GpuSkinningModelInstance target) {
        if (!target.initialized) {
            return 0;
        }

        long rustRam = target.nativeBackendPort().getModelMemoryUsage(target.nativeModelHandle());
        long javaRam = (long) target.vertexCount * 8;
        javaRam += target.staticBuffers.stream().mapToLong(ImmutableGpuBufferPool.Lease::accountedBytes).sum();
        if (target.firstPersonIndexBuffer != null) javaRam += target.firstPersonIndexBuffer.capacity();
        if (target.firstPersonMatrixBuffer != null) javaRam += target.firstPersonMatrixBuffer.capacity();
        javaRam += 128;
        if (target.boneMatricesBuffer != null) {
            javaRam += (long) target.boneMatricesBuffer.capacity() * 4;
        }
        if (target.boneMatricesByteBuffer != null) {
            javaRam += target.boneMatricesByteBuffer.capacity();
        }
        if (target.morphWeightsBuffer != null) {
            javaRam += (long) target.morphWeightsBuffer.capacity() * 4;
        }
        if (target.morphWeightsByteBuffer != null) {
            javaRam += target.morphWeightsByteBuffer.capacity();
        }
        if (target.uvMorphWeightsBuffer != null) {
            javaRam += (long) target.uvMorphWeightsBuffer.capacity() * 4;
        }
        if (target.uvMorphWeightsByteBuffer != null) {
            javaRam += target.uvMorphWeightsByteBuffer.capacity();
        }
        if (target.materialMorphResultCountValue() > 0) {
            javaRam += (long) target.materialMorphResultCountValue() * 56 * 4 * 2;
        }
        if (target.subMeshDataBuf != null) {
            javaRam += target.subMeshDataBuf.capacity();
        }
        return rustRam + javaRam + target.translucentOrders.ramBytes();
    }

    static void dispose(GpuSkinningModelInstance target) {
        if (!target.initialized) {
            return;
        }

        target.initialized = false;
        target.releaseBaseResources();
        target.translucentOrders.close();
        target.currentTranslucentOrder = null;
        target.originalIndices = null;
        MaterialTextureLoader.releaseOwnedTextures(target.mats);

        GL46C.glDeleteVertexArrays(target.vertexArrayObject);
        target.staticBuffers.forEach(ImmutableGpuBufferPool.Lease::close);
        target.staticBuffers.clear();
        target.posBuffer = target.norBuffer = target.uv0Buffer = target.colorBuffer = target.uv1Buffer = null;
        if (target.firstPersonIndexBufferObject > 0) {
            GL46C.glDeleteBuffers(target.firstPersonIndexBufferObject);
        }
        GL46C.glDeleteBuffers(target.uv2BufferObject);
        GL46C.glDeleteBuffers(target.skinnedPositionsBuffer);
        GL46C.glDeleteBuffers(target.skinnedNormalsBuffer);

        if (target.boneMatrixSSBO > 0) GL46C.glDeleteBuffers(target.boneMatrixSSBO);
        if (target.morphWeightsSSBO > 0) GL46C.glDeleteBuffers(target.morphWeightsSSBO);
        if (target.uvMorphWeightsSSBO > 0) GL46C.glDeleteBuffers(target.uvMorphWeightsSSBO);
        if (target.skinnedUvBuffer > 0) GL46C.glDeleteBuffers(target.skinnedUvBuffer);
        target.boneMatrixSSBO = 0;
        target.morphOffsetsSSBO = 0;
        target.morphWeightsSSBO = 0;
        target.uvMorphOffsetsSSBO = 0;
        target.uvMorphWeightsSSBO = 0;
        target.skinnedUvBuffer = 0;

        if (target.lightMapMaterial != null && target.lightMapMaterial.ownsTexture && target.lightMapMaterial.tex > 0) {
            GL46C.glDeleteTextures(target.lightMapMaterial.tex);
            target.lightMapMaterial.tex = 0;
        }

        if (target.boneMatricesBuffer != null) {
            MemoryUtil.memFree(target.boneMatricesBuffer);
            target.boneMatricesBuffer = null;
        }
        if (target.boneMatricesByteBuffer != null) {
            MemoryUtil.memFree(target.boneMatricesByteBuffer);
            target.boneMatricesByteBuffer = null;
        }
        if (target.morphWeightsBuffer != null) {
            MemoryUtil.memFree(target.morphWeightsBuffer);
            target.morphWeightsBuffer = null;
        }
        target.morphWeightsByteBuffer = null;
        if (target.uvMorphWeightsBuffer != null) {
            MemoryUtil.memFree(target.uvMorphWeightsBuffer);
            target.uvMorphWeightsBuffer = null;
        }
        target.uvMorphWeightsByteBuffer = null;
        if (target.modelViewMatBuff != null) {
            MemoryUtil.memFree(target.modelViewMatBuff);
            target.modelViewMatBuff = null;
        }
        if (target.projMatBuff != null) {
            MemoryUtil.memFree(target.projMatBuff);
            target.projMatBuff = null;
        }
        if (target.subMeshDataBuf != null) {
            MemoryUtil.memFree(target.subMeshDataBuf);
            target.subMeshDataBuf = null;
        }
        if (target.firstPersonIndexBuffer != null) {
            MemoryUtil.memFree(target.firstPersonIndexBuffer);
            target.firstPersonIndexBuffer = null;
        }
        if (target.firstPersonMatrixBuffer != null) {
            MemoryUtil.memFree(target.firstPersonMatrixBuffer);
            target.firstPersonMatrixBuffer = null;
            target.firstPersonMatrixFloatBuffer = null;
        }
    }
}
