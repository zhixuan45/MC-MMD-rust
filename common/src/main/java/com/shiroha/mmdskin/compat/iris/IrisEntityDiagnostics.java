package com.shiroha.mmdskin.compat.iris;

import com.shiroha.mmdskin.render.pipeline.LightingHelper;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.systems.RenderSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** 一次性读取 Iris 实体程序状态，便于排查属性和贴图接口。 */
public final class IrisEntityDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<ShaderInstance, Boolean> RECORDED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private IrisEntityDiagnostics() {}

    /** 调用方须先 apply shader；本方法只读取 GL 状态。 */
    public static void record(ShaderInstance shader, LightingHelper.LightData light) {
        if (shader == null || light == null || IrisCompat.isRenderingShadows()
                || !IrisCompat.isIrisProgram(shader)) return;
        synchronized (RECORDED) {
            if (RECORDED.putIfAbsent(shader, Boolean.TRUE) != null) return;
        }

        int program = shader.getId();
        int modulator = GL46C.glGetUniformLocation(program, "iris_ColorModulator");
        String color = "缺失";
        if (modulator >= 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer values = stack.mallocFloat(4);
                GL46C.glGetUniformfv(program, modulator, values);
                color = String.format("%.3f,%.3f,%.3f,%.3f",
                        values.get(0), values.get(1), values.get(2), values.get(3));
            }
        }
        LOGGER.info("Iris实体诊断 shader={} 光照(block={},sky={},intensity={}) ColorModulator={} 属性(UV1={},UV2={},tangent={}) 纹理(unit1={},unit2={})",
                shader.getName(), light.blockLight(), light.skyLight(), light.intensity(), color,
                GL46C.glGetAttribLocation(program, "iris_UV1"),
                GL46C.glGetAttribLocation(program, "iris_UV2"),
                GL46C.glGetAttribLocation(program, "at_tangent"),
                RenderSystem.getShaderTexture(1), RenderSystem.getShaderTexture(2));
    }

    public static void reset() {
        RECORDED.clear();
    }
}
