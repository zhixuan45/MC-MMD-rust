package com.shiroha.mmdskin.render.outline;

import com.shiroha.mmdskin.render.shader.ToonConfig;
import com.shiroha.mmdskin.render.material.ToonTransparencySettings;
import com.shiroha.mmdskin.render.shader.ToonShaderBase;
import org.lwjgl.opengl.GL46C;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.IntPredicate;

/** 集中执行 CPU 与 GPU 蒙皮共用的描边绘制。 */
public final class OutlineRenderPass {
    private static final int SUB_MESH_STRIDE = 20;

    @FunctionalInterface
    public interface AlphaResolver {
        // 返回包含材质 morph 和全局透明度的最终 alpha。
        float resolve(int materialId, float baseAlpha);
    }

    @FunctionalInterface
    public interface TextureResolver {
        int resolve(int materialId);
    }

    private OutlineRenderPass() {
    }

    public record Inputs(int positionsBuffer,
                         int normalsBuffer,
                         int uvBuffer,
                         ByteBuffer subMeshData,
                         int subMeshCount,
                         int indexElementSize,
                         int indexType,
                         FloatBuffer projection,
                         FloatBuffer modelView,
                         float modelRootScale) {
    }

    public static void draw(ToonShaderBase shader, Inputs inputs,
                            AlphaResolver alphaResolver, TextureResolver textureResolver) {
        draw(shader, inputs, alphaResolver, textureResolver, id -> false);
    }

    public static void draw(ToonShaderBase shader, Inputs inputs,
                            AlphaResolver alphaResolver, TextureResolver textureResolver, IntPredicate hairResolver) {
        int positionLocation = shader.getOutlinePositionLocation();
        int normalLocation = shader.getOutlineNormalLocation();
        int uvLocation = shader.getOutlineUvLocation();
        FloatBuffer projection = inputs.projection();
        int projectionStart = projection.position();
        boolean orthographic = Math.abs(projection.get(projectionStart + 11)) < 0.000001f
                && Math.abs(projection.get(projectionStart + 15) - 1.0f) < 0.000001f;
        try (OutlineCoverage pass = OutlineCoverage.begin(shader.getOutlineColorSlot())) {
            shader.useOutline();
            shader.setOutlinePremultiplied(pass.isSampled());
            shader.setOutlineAlphaCutoff(0.1f);
            if (positionLocation != -1) {
                GL46C.glEnableVertexAttribArray(positionLocation);
                GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, inputs.positionsBuffer());
                GL46C.glVertexAttribPointer(positionLocation, 3, GL46C.GL_FLOAT, false, 0, 0);
            }
            if (normalLocation != -1) {
                GL46C.glEnableVertexAttribArray(normalLocation);
                GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, inputs.normalsBuffer());
                GL46C.glVertexAttribPointer(normalLocation, 3, GL46C.GL_FLOAT, false, 0, 0);
            }
            if (uvLocation != -1) {
                GL46C.glEnableVertexAttribArray(uvLocation);
                GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, inputs.uvBuffer());
                GL46C.glVertexAttribPointer(uvLocation, 2, GL46C.GL_FLOAT, false, 0, 0);
            }

            ToonConfig config = ToonConfig.getInstance();
            float width = config.getOutlineWidth();
            if (orthographic) width = orthographicWidth(width, inputs);
            shader.setOutlineProjectionMatrix(inputs.projection());
            shader.setOutlineModelViewMatrix(inputs.modelView());
            if (pass.isSampled() && ToonTransparencySettings.disableDepthSeal()) {
                pass.beginOcclusion();
                shader.setOutlineWidth(0);
                shader.setOutlineOcclusion(true);
                try {
                    drawSubMeshes(shader, inputs, alphaResolver, textureResolver, hairResolver, config, true);
                } finally {
                    shader.setOutlineOcclusion(false);
                    pass.endOcclusion();
                }
            }
            shader.setOutlineWidth(width);
            shader.setOutlineColor(config.getOutlineColorR(), config.getOutlineColorG(), config.getOutlineColorB());
            GL46C.glDepthMask(false);
            // GUI 的同深度薄片不能由倒置外壳再次覆盖，外缘仍通过背景深度。
            if (orthographic) GL46C.glDepthFunc(GL46C.GL_LESS);
            GL46C.glCullFace(GL46C.GL_FRONT);
            GL46C.glEnable(GL46C.GL_CULL_FACE);
            drawSubMeshes(shader, inputs, alphaResolver, textureResolver, hairResolver, config, false);
            pass.composite();
        }
    }

    private static float orthographicWidth(float width, Inputs inputs) {
        FloatBuffer matrix = inputs.modelView();
        int start = matrix.position();
        float x = matrix.get(start), y = matrix.get(start + 1), z = matrix.get(start + 2);
        float viewScale = (float) Math.sqrt(x * x + y * y + z * z);
        float rootScale = Math.abs(inputs.modelRootScale());
        if (!Float.isFinite(viewScale) || !Float.isFinite(rootScale) || rootScale == 0.0f) return 0.0f;
        // 只补偿 GUI 额外缩放，保留世界基础宽度与人物大小的比例。
        return width * (viewScale / rootScale);
    }

    private static void drawSubMeshes(ToonShaderBase shader, Inputs inputs, AlphaResolver alphaResolver,
                                      TextureResolver textureResolver, IntPredicate hairResolver, ToonConfig config, boolean occlusion) {
        ByteBuffer data = inputs.subMeshData();
        for (int i = 0; i < inputs.subMeshCount(); i++) {
            int base = i * SUB_MESH_STRIDE;
            if (!occlusion && data.get(base + 16) == 0) continue;
            if (occlusion) {
                if (data.get(base + 17) != 0) GL46C.glDisable(GL46C.GL_CULL_FACE);
                else { GL46C.glEnable(GL46C.GL_CULL_FACE); GL46C.glCullFace(GL46C.GL_BACK); }
            }
            float alpha = alphaResolver.resolve(data.getInt(base), data.getFloat(base + 12));
            if (alpha < 0.001f) continue;
            float cutoff = hairResolver.test(data.getInt(base)) ? config.getHairAlphaCutoff() : config.getAlphaCutoff();
            // 主体裁掉的像素不留下描边；保留原贴图遮罩的 0.1 下限。
            shader.setOutlineAlphaCutoff(occlusion ? Math.max(0.001f, cutoff) / alpha
                    : Math.max(0.1f, cutoff / alpha));
            // 无纹理材质沿用实心遮罩，不采样上一材质的残留绑定。
            int texture = textureResolver.resolve(data.getInt(base));
            shader.setOutlineUseTextureAlpha(texture > 0);
            if (texture > 0) GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
            shader.setOutlineAlpha(alpha);
            GL46C.glDrawElements(GL46C.GL_TRIANGLES, data.getInt(base + 8), inputs.indexType(),
                    (long) data.getInt(base + 4) * inputs.indexElementSize());
        }
    }
}
