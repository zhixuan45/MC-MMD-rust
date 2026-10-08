package com.shiroha.mmdskin.render.outline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.shader.ToonConfig;
import com.shiroha.mmdskin.render.shader.ToonShaderBase;
import org.lwjgl.opengl.GL46C;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/** 集中执行 CPU 与 GPU 蒙皮共用的描边绘制。 */
public final class OutlineRenderPass {
    private static final int SUB_MESH_STRIDE = 20;

    @FunctionalInterface
    public interface AlphaResolver {
        // 返回包含材质 morph 和全局透明度的最终 alpha。
        float resolve(int materialId, float baseAlpha);
    }

    private OutlineRenderPass() {
    }

    public record Inputs(int positionsBuffer,
                         int normalsBuffer,
                         ByteBuffer subMeshData,
                         int subMeshCount,
                         int indexElementSize,
                         int indexType,
                         FloatBuffer projection,
                         FloatBuffer modelView,
                         float modelRootScale) {
    }

    public static void draw(ToonShaderBase shader, Inputs inputs,
                            AlphaResolver alphaResolver) {
        int positionLocation = shader.getOutlinePositionLocation();
        int normalLocation = shader.getOutlineNormalLocation();
        boolean positionEnabled = false;
        boolean normalEnabled = false;
        FloatBuffer projection = inputs.projection();
        int projectionStart = projection.position();
        boolean orthographic = Math.abs(projection.get(projectionStart + 11)) < 0.000001f
                && Math.abs(projection.get(projectionStart + 15) - 1.0f) < 0.000001f;
        int previousDepthFunc = orthographic ? GL46C.glGetInteger(GL46C.GL_DEPTH_FUNC) : 0;
        try {
            shader.useOutline();
            if (positionLocation != -1) {
                GL46C.glEnableVertexAttribArray(positionLocation);
                positionEnabled = true;
                GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, inputs.positionsBuffer());
                GL46C.glVertexAttribPointer(positionLocation, 3, GL46C.GL_FLOAT, false, 0, 0);
            }
            if (normalLocation != -1) {
                GL46C.glEnableVertexAttribArray(normalLocation);
                normalEnabled = true;
                GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, inputs.normalsBuffer());
                GL46C.glVertexAttribPointer(normalLocation, 3, GL46C.GL_FLOAT, false, 0, 0);
            }

            ToonConfig config = ToonConfig.getInstance();
            float width = config.getOutlineWidth();
            if (orthographic) width = orthographicWidth(width, inputs);
            shader.setOutlineProjectionMatrix(inputs.projection());
            shader.setOutlineModelViewMatrix(inputs.modelView());
            shader.setOutlineWidth(width);
            shader.setOutlineColor(config.getOutlineColorR(), config.getOutlineColorG(), config.getOutlineColorB());
            RenderSystem.depthMask(false);
            // GUI 的同深度薄片不能由倒置外壳再次覆盖，外缘仍通过背景深度。
            if (orthographic) RenderSystem.depthFunc(GL46C.GL_LESS);
            GL46C.glCullFace(GL46C.GL_FRONT);
            RenderSystem.enableCull();
            drawSubMeshes(shader, inputs, alphaResolver);
        } finally {
            // 只清理本 pass 启用的属性，并恢复描边使用的固定状态。
            if (positionEnabled) GL46C.glDisableVertexAttribArray(positionLocation);
            if (normalEnabled) GL46C.glDisableVertexAttribArray(normalLocation);
            GL46C.glCullFace(GL46C.GL_BACK);
            RenderSystem.depthMask(true);
            if (orthographic) RenderSystem.depthFunc(previousDepthFunc);
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

    private static void drawSubMeshes(ToonShaderBase shader, Inputs inputs, AlphaResolver alphaResolver) {
        ByteBuffer data = inputs.subMeshData();
        for (int i = 0; i < inputs.subMeshCount(); i++) {
            int base = i * SUB_MESH_STRIDE;
            if (data.get(base + 16) == 0) continue;
            float alpha = alphaResolver.resolve(data.getInt(base), data.getFloat(base + 12));
            if (alpha < 0.001f) continue;
            // 逐子网格透明度来自可见性策略，纯色通道不绑定材质纹理。
            shader.setOutlineAlpha(alpha);
            GL46C.glDrawElements(GL46C.GL_TRIANGLES, data.getInt(base + 8), inputs.indexType(),
                    (long) data.getInt(base + 4) * inputs.indexElementSize());
        }
    }
}
