package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.pipeline.TranslucentDiagnostics;
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
        DrawMaterialCache materialCache = new DrawMaterialCache(materials, materialMorphs, alphaResolver, textureResolver);
        try (EntityPixelAlphaPass pixels = EntityPixelAlphaPass.begin(shadowPass);
             MaterialShaderBinding binding = new MaterialShaderBinding();
             SphereTextureBinding sphere = new SphereTextureBinding(shadowPass || !hasSphere(materials));
             ToonTransparencyLayers layers = !shadowPass && !binding.disablesTransparency()
                     && hasTranslucency(subMeshDataBuf, subMeshCount, materials, materialCache)
                     ? ToonTransparencyLayers.begin() : null) {
            boolean forceOpaque = !shadowPass && binding.disablesTransparency();
            boolean sorted=!forceOpaque && !shadowPass && order!=null && geometry!=null
                    && order.prepare(geometry,subMeshDataBuf,subMeshCount,indexElementSize,materials,materialMorphs,
                    alphaResolver,textureResolver,materialCache);
            long submitTimer = TranslucentDiagnostics.startTimer();
            try {
            // 不透明部分先写深度；半透明三角形跨材质由远到近混合。
                // Toon 对同一贴图的实心与半透明像素也分开，避免黑睫毛的实心部分丢失遮挡。
                int passCount = shadowPass || forceOpaque ? 1 : 3;
                for (int pass = 0; pass < passCount; pass++) {
                    if (pass == 2 && !hasTranslucentDraws) break;
                    int stages = pass == 1 && layers != null ? layers.layers() * 2 + 1 : 1;
                    for (int stage = 0; stage < stages; stage++) {
                        long passDraws = 0;
                        // 保留半透明层间混合，再补覆盖深度，避免后画的云穿过模型。
                        RenderSystem.depthMask(pass != 1);
                        boolean layerStageStarted = false;
                        if (pass == 1 && layers != null) {
                            if (stage < layers.layers()) layers.beginPeel(stage);
                            else if (stage == layers.layers()) layers.beginTail();
                            else layers.beginLayer(stages - stage - 1);
                            layerStageStarted = true;
                        }
                        // 已选中的像素层无需三角形排序，按原材质提交以限制 draw 数。
                        boolean ordered=pass==1 && sorted
                                && (layers == null || stage == layers.layers());
                        ByteBuffer draws;
                        int drawCount;
                        // 批量材质表与排序索引必须使用同一三角形顺序。
                        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ordered ? order.indexBuffer() : originalIndices);
                        try (MaterialDepthSeal seal = pass == 2 ? new MaterialDepthSeal() : null) {
                            if (ordered) {
                                int batchDraws = order.submitBatch(materials, materialCache);
                                if (batchDraws >= 0) {
                                    passDraws = batchDraws;
                                    hasTranslucentDraws = batchDraws > 0;
                                    continue;
                                }
                                // 保留 CPU 排序；GPU 晚期回退时补建同帧的 CPU 排序。
                                GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, originalIndices);
                                ordered = order.prepareCompatibility(geometry, subMeshDataBuf, subMeshCount,
                                        indexElementSize, materials, materialMorphs, alphaResolver, textureResolver, materialCache);
                                TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.BATCH_FALLBACK, 1);
                            }
                            draws = ordered ? order.submeshes() : subMeshDataBuf;
                            drawCount = ordered ? order.drawCount() : subMeshCount;
                            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ordered ? order.indexBuffer() : originalIndices);
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
                                MaterialRenderState state = materialCache.resolve(materialId, alpha);
                                if (state.alpha < 0.001f) continue;
                                if (!shadowPass && !forceOpaque) {
                                    if (binding.splitsTextureAlpha()) {
                                        if (pass == 0 && state.alpha < MaterialRenderState.OPAQUE_ALPHA) continue;
                                        if (pass == 1 && state.alpha >= MaterialRenderState.OPAQUE_ALPHA && !material.hasAlpha) continue;
                                        if (pass == 2 && state.alpha >= MaterialRenderState.OPAQUE_ALPHA && !material.hasTranslucentPixels) continue;
                                    } else {
                                        boolean translucent = state.alpha < MaterialRenderState.OPAQUE_ALPHA || material.hasTranslucentPixels;
                                        if (translucent != (pass != 0)) continue;
                                    }
                                }
                                // 混合结束后为可见半透明像素补深度，阻挡后画的描边与云；
                                // 透明孔洞仍由当前 shader 的 alpha 裁切保留。
                                binding.apply(state, shadowPass || pass == 2 ? 0 : pass + 1);
                                sphere.apply(material, state);

                                if (bothFace) {
                                    RenderSystem.disableCull();
                                } else {
                                    RenderSystem.enableCull();
                                }

                                int textureId = materialCache.texture(materialId);
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
                                passDraws++;
                            }
                        } finally {
                            if (layerStageStarted) {
                                if (stage < layers.layers()) layers.endPeel();
                                else layers.endStage();
                            }
                            TranslucentDiagnostics.add(
                                    shadowPass ? TranslucentDiagnostics.Counter.DRAW_SHADOW
                                            : pass == 0 ? TranslucentDiagnostics.Counter.DRAW_OPAQUE
                                            : pass == 1 ? TranslucentDiagnostics.Counter.DRAW_TRANSLUCENT
                                            : TranslucentDiagnostics.Counter.DRAW_DEPTH_SEAL,
                                    passDraws);
                        }
                        }
                }
            } finally {
                TranslucentDiagnostics.endTimer(TranslucentDiagnostics.Stage.SUBMIT, submitTimer);
            }
        } finally {
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,originalIndices);
            RenderSystem.depthMask(previousDepthMask);
            // 恢复默认的面剔除状态，防止污染后续实体渲染。
            RenderSystem.enableCull();
        }
    }

    private static boolean hasTranslucency(ByteBuffer source, int count, ModelMaterial[] materials,
                                           DrawMaterialCache cache) {
        for (int i = 0; i < count; i++) {
            int base = i * SUB_MESH_STRIDE, id = source.getInt(base);
            if (source.get(base + 16) == 0 || source.getInt(base + 8) <= 0
                    || id < 0 || id >= materials.length) continue;
            float alpha = cache.resolve(id, source.getFloat(base + 12)).alpha;
            if (alpha >= 0.001f && (alpha < MaterialRenderState.OPAQUE_ALPHA
                    || materials[id].hasTranslucentPixels)) return true;
        }
        return false;
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
