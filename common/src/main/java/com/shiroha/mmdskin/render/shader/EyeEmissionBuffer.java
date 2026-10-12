package com.shiroha.mmdskin.render.shader;

import com.google.gson.Gson;
import com.shiroha.mmdskin.bridge.runtime.NativeRenderBackendPort;
import com.shiroha.mmdskin.render.material.EyeBoneMask;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 实例拥有的静态眼瞳属性；CPU/GPU 使用同一组原始顶点索引。 */
public final class EyeEmissionBuffer implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger();
    private int buffer;
    private final long bytes;
    private int boundLocation = -1, previousDivisor;

    private EyeEmissionBuffer(int buffer, int vertexCount) {
        this.buffer = buffer;
        this.bytes = (long) vertexCount * Float.BYTES;
    }

    public static EyeEmissionBuffer create(NativeRenderBackendPort port, long model, int vertexCount) {
        ByteBuffer indices = null, weights = null;
        int buffer = 0;
        int previousBuffer = GL46C.glGetInteger(GL46C.GL_ARRAY_BUFFER_BINDING);
        try {
            String[] names = new Gson().fromJson(port.getBoneNames(model), String[].class);
            if (names == null || java.util.Arrays.stream(names).noneMatch(EyeBoneMask::isEyeBone)) {
                logger.warn("模型 {} 缺少可识别眼骨，眼瞳发光仅按明确眼瞳材质范围", model);
                return null;
            }
            // CPU 后端也需要先初始化这组只读权重，避免读取尚未分配的 native 数组。
            port.initGpuSkinningData(model);
            int size = Math.multiplyExact(vertexCount, 16);
            indices = MemoryUtil.memAlloc(size).order(ByteOrder.LITTLE_ENDIAN);
            weights = MemoryUtil.memAlloc(size).order(ByteOrder.LITTLE_ENDIAN);
            if (port.copyBoneIndicesToBuffer(model, indices, vertexCount) != vertexCount
                    || port.copyBoneWeightsToBuffer(model, weights, vertexCount) != vertexCount) {
                throw new IllegalStateException("顶点骨权重复制不完整");
            }
            float[] mask = EyeBoneMask.build(names, indices.asIntBuffer(), weights.asFloatBuffer(), vertexCount);
            boolean any = false;
            for (float value : mask) any |= value > 0;
            if (!any) {
                logger.warn("模型 {} 没有绑定眼骨的顶点，眼瞳发光仅按明确眼瞳材质范围", model);
                return null;
            }
            buffer = GL46C.glGenBuffers();
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, buffer);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, mask, GL46C.GL_STATIC_DRAW);
            EyeEmissionBuffer result = new EyeEmissionBuffer(buffer, vertexCount);
            buffer = 0;
            return result;
        } catch (Exception e) {
            logger.warn("眼瞳发光遮罩初始化失败，模型 {}: {}", model, e.getMessage());
            return null;
        } finally {
            if (buffer != 0) GL46C.glDeleteBuffers(buffer);
            if (indices != null) MemoryUtil.memFree(indices);
            if (weights != null) MemoryUtil.memFree(weights);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, previousBuffer);
        }
    }

    public void bind(int mainProgram) {
        int location = GL46C.glGetAttribLocation(mainProgram, "EyeMask");
        if (location < 0) return;
        boundLocation = location;
        previousDivisor = GL46C.glGetVertexAttribi(location, GL46C.GL_VERTEX_ATTRIB_ARRAY_DIVISOR);
        int previousBuffer = GL46C.glGetInteger(GL46C.GL_ARRAY_BUFFER_BINDING);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, buffer);
        GL46C.glEnableVertexAttribArray(location);
        GL46C.glVertexAttribPointer(location, 1, GL46C.GL_FLOAT, false, 0, 0);
        // Iris UV1 可能复用此位置并设置 divisor=1，眼瞳遮罩必须逐顶点读取。
        GL46C.glVertexAttribDivisor(location, 0);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, previousBuffer);
    }

    public static void unbind(int mainProgram) {
        int location = GL46C.glGetAttribLocation(mainProgram, "EyeMask");
        if (location >= 0) {
            GL46C.glDisableVertexAttribArray(location);
            GL46C.glVertexAttrib1f(location, 0.0f);
        }
    }

    public void clearBinding(int mainProgram) {
        try {
            unbind(mainProgram);
        } finally {
            if (boundLocation >= 0) GL46C.glVertexAttribDivisor(boundLocation, previousDivisor);
            boundLocation = -1;
        }
    }

    public long byteSize() { return buffer == 0 ? 0 : bytes; }

    @Override public void close() {
        if (buffer != 0) GL46C.glDeleteBuffers(buffer);
        buffer = 0;
    }
}
