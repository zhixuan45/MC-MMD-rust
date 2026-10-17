package com.shiroha.mmdskin.render.shader;

import com.shiroha.mmdskin.util.AssetsUtil;
import com.shiroha.mmdskin.render.material.ProgramSamplerLayout;
import java.util.HashMap;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** 只允许由本模组源码编译并登记的主体程序使用材质表。 */
public final class TranslucentBatchShader {
    public record Uniforms(int program, int enabled, int triangleOffset, int cullMode, int textures, int alphaPass) { }
    private static final HashMap<Integer, Uniforms> PROGRAMS = new HashMap<>();
    private TranslucentBatchShader() { }

    public static boolean supported() {
        return !"false".equalsIgnoreCase(System.getProperty("mmdskin.translucentBatch"))
                && GL.getCapabilities().OpenGL43
                && GL46C.glGetInteger(GL46C.GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS) >= 2
                && GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_IMAGE_UNITS) > 0;
    }

    static String source(String source) {
        return source.replace("#version 330 core", "#version 430 core\n#define MMD_BATCH_MATERIALS")
                .replace("/* TOON_BATCH_MATERIALS */", AssetsUtil.getAssetsAsString("shader/toon_batch_materials.glsl"));
    }

    static void register(int program) {
        int enabled = GL46C.glGetUniformLocation(program, "MmdBatchEnabled");
        if (enabled >= 0) PROGRAMS.put(program, new Uniforms(program, enabled,
                GL46C.glGetUniformLocation(program, "MmdBatchTriangleOffset"),
                GL46C.glGetUniformLocation(program, "MmdBatchCullMode"),
                GL46C.glGetUniformLocation(program, "MmdBatchTextures[0]"),
                GL46C.glGetUniformLocation(program, "AlphaPass")));
    }

    public static Uniforms current() {
        if ("false".equalsIgnoreCase(System.getProperty("mmdskin.translucentBatch"))) return null;
        return PROGRAMS.get(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM));
    }

    static void forget(int program) {
        PROGRAMS.remove(program);
        ProgramSamplerLayout.forget(program);
    }
}
