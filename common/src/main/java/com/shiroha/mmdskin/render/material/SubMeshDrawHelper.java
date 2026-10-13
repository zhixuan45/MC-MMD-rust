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
                            boolean shadowPass) {
        RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
        int lastBoundTexture = -1;
        boolean previousDepthMask = GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK);
        MaterialRenderState state = new MaterialRenderState();
        try (MaterialShaderBinding binding = new MaterialShaderBinding()) {
            // 不透明部分先写深度，半透明部分随后按 PMX 材质顺序混合。
            // Toon 对同一贴图的实心与半透明像素也分开，避免黑睫毛的实心部分丢失遮挡。
            for (int pass = 0; pass < (shadowPass ? 1 : 2); pass++) {
                RenderSystem.depthMask(pass == 0);
                for (int i = 0; i < subMeshCount; ++i) {
                    int base = i * SUB_MESH_STRIDE;
                    int materialId = subMeshDataBuf.getInt(base);
                    int beginIndex = subMeshDataBuf.getInt(base + 4);
                    int vertexCount = subMeshDataBuf.getInt(base + 8);
                    float alpha = subMeshDataBuf.getFloat(base + 12);
                    boolean visible = subMeshDataBuf.get(base + 16) != 0;
                    boolean bothFace = subMeshDataBuf.get(base + 17) != 0;

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
                        } else {
                            boolean translucent = state.alpha < MaterialRenderState.OPAQUE_ALPHA || material.hasTranslucentPixels;
                            if (translucent != (pass == 1)) continue;
                        }
                    }
                    binding.apply(state, shadowPass ? 0 : pass + 1);

                    if (bothFace) {
                        RenderSystem.disableCull();
                    } else {
                        RenderSystem.enableCull();
                    }

                    int textureId = textureResolver.resolve(materialId);
                    if (textureId != lastBoundTexture) {
                        RenderSystem.setShaderTexture(0, textureId);
                        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, textureId);
                        lastBoundTexture = textureId;
                    }

                    long startPos = (long) beginIndex * indexElementSize;
                    GL46C.glDrawElements(GL46C.GL_TRIANGLES, vertexCount, indexType, startPos);
                }
            }
        } finally {
            RenderSystem.depthMask(previousDepthMask);
            // 恢复默认的面剔除状态，防止污染后续实体渲染。
            RenderSystem.enableCull();
        }
    }

}
