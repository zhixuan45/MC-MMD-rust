package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL46C;

import java.nio.ByteBuffer;

/**
 * 子网格绘制公共逻辑。
 */
public final class SubMeshDrawHelper {

    private static final int SUB_MESH_STRIDE = 20;

    private SubMeshDrawHelper() {
    }

    @FunctionalInterface
    public interface TextureResolver {
        int resolve(int materialId);
    }

    @FunctionalInterface
    public interface AlphaResolver {
        float resolve(int materialId, float baseAlpha);
    }

    public static void draw(ByteBuffer subMeshDataBuf,
                            int subMeshCount,
                            int indexElementSize,
                            int indexType,
                            TextureResolver textureResolver,
                            AlphaResolver alphaResolver,
                            ModelMaterial[] materials,
                            ByteBuffer materialMorphs,
                            boolean shadowPass,
                            java.util.function.IntConsumer beforeDraw) {
        draw(subMeshDataBuf,subMeshCount,indexElementSize,indexType,textureResolver,alphaResolver,
                materials,materialMorphs,shadowPass,beforeDraw,null,null);
    }

    /**
     * Sorted batches can merge materials with equal effective shader state. Additional
     * material-dependent callback state must also be represented in TranslucentDrawOrder's batch key.
     */
    public static void draw(ByteBuffer subMeshDataBuf, int subMeshCount, int indexElementSize, int indexType,
                            TextureResolver textureResolver, AlphaResolver alphaResolver,
                            ModelMaterial[] materials, ByteBuffer materialMorphs, boolean shadowPass,
                            java.util.function.IntConsumer beforeDraw, TranslucentDrawOrder order,
                            TranslucentDrawOrder.Inputs geometry) {
        RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
        int lastBoundTexture = -1;
        boolean previousDepthMask = GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK);
        boolean hasTranslucentDraws = false;
        int originalIndices=GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        MaterialRenderState state = new MaterialRenderState();
        try (EntityPixelAlphaPass pixels = EntityPixelAlphaPass.begin(shadowPass);
             MaterialShaderBinding binding = new MaterialShaderBinding();
             SphereTextureBinding sphere = new SphereTextureBinding(shadowPass || !hasSphere(materials))) {
            boolean sorted=!shadowPass && order!=null && geometry!=null
                    && order.prepare(geometry,subMeshDataBuf,subMeshCount,indexElementSize,materials,materialMorphs,alphaResolver);
            // 不透明部分先写深度；半透明三角形跨材质由远到近混合。
            // Toon 对同一贴图的实心与半透明像素也分开，避免黑睫毛的实心部分丢失遮挡。
            for (int pass = 0; pass < (shadowPass ? 1 : 3); pass++) {
                if (pass == 2 && !hasTranslucentDraws) break;
                // 保留半透明层间混合，再补覆盖深度，避免后画的云穿过模型。
                RenderSystem.depthMask(pass != 1);
                boolean ordered=pass==1 && sorted;
                ByteBuffer draws=ordered?order.submeshes():subMeshDataBuf;
                int drawCount=ordered?order.drawCount():subMeshCount;
                GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,ordered?order.indexBuffer():originalIndices);
                try (MaterialDepthSeal seal = pass == 2 ? new MaterialDepthSeal() : null) {
                    for (int i = 0; i < drawCount; ++i) {
                        int base = i * SUB_MESH_STRIDE;
                        int materialId = draws.getInt(base);
                        int beginIndex = draws.getInt(base + 4);
                        int vertexCount = draws.getInt(base + 8);
                        float alpha = draws.getFloat(base + 12);
                        boolean visible = draws.get(base + 16) != 0;
                        boolean bothFace = draws.get(base + 17) != 0;

                        if (!visible || vertexCount <= 0 || materialId < 0 || materialId >= materials.length) {
                            continue;
                        }
                        ModelMaterial material = materials[materialId];
                        state.resolve(material, materialMorphs, materialId, alphaResolver.resolve(materialId, alpha));
                        if (state.alpha < 0.001f) continue;
                        if (!shadowPass) {
                            if (binding.splitsTextureAlpha()) {
                                if (pass == 0 && state.alpha < MaterialRenderState.OPAQUE_ALPHA) continue;
                                if (pass == 1 && state.alpha >= MaterialRenderState.OPAQUE_ALPHA && !material.hasAlpha) continue;
                                if (pass == 2 && state.alpha >= MaterialRenderState.OPAQUE_ALPHA && !material.hasTranslucentPixels) continue;
                            } else {
                                boolean translucent = state.alpha < MaterialRenderState.OPAQUE_ALPHA || material.hasTranslucentPixels;
                                if (translucent != (pass != 0)) continue;
                            }
                        }
                        binding.apply(state, shadowPass || pass == 2 ? 0 : pass + 1);
                        sphere.apply(material, state);

                        if (bothFace) {
                            RenderSystem.disableCull();
                        } else {
                            RenderSystem.enableCull();
                        }

                        int textureId = textureResolver.resolve(materialId);
                        if (textureId != lastBoundTexture) {
                            RenderSystem.setShaderTexture(0, textureId);
                            RenderSystem.bindTexture(0);
                            RenderSystem.bindTexture(textureId);
                            lastBoundTexture = textureId;
                        }

                        beforeDraw.accept(materialId);
                        if (pass == 1) hasTranslucentDraws = true;
                        long startPos = (long) beginIndex * (ordered?4:indexElementSize);
                        GL46C.glDrawElements(GL46C.GL_TRIANGLES, vertexCount, ordered?GL46C.GL_UNSIGNED_INT:indexType, startPos);
                    }
                }
            }
        } finally {
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,originalIndices);
            RenderSystem.depthMask(previousDepthMask);
            // 恢复默认的面剔除状态，防止污染后续实体渲染。
            RenderSystem.enableCull();
        }
    }

    private static boolean hasSphere(ModelMaterial[] materials) {
        // 没有球面层的模型不做额外的采样器枚举和纹理状态查询。
        for (ModelMaterial material : materials) {
            if (material != null && material.sphereTex > 0
                    && (material.sphereMode == 1 || material.sphereMode == 2)) return true;
        }
        return false;
    }

}
