package com.shiroha.mmdskin.render.shader;

import org.lwjgl.opengl.GL43C;

/**
 * SSBO 绑定状态清理工具。
 * 避免使用 glGet* 同步查询驱动状态导致 CPU-GPU 管线停顿，
 * 在 Compute Shader 执行完成后快速解绑 MMD 使用的 SSBO 绑定点。
 */
public class SSBOBindings {

    private final int maxSlot;

    public SSBOBindings() {
        this(16);
    }

    public SSBOBindings(int maxSlot) {
        this.maxSlot = Math.max(1, maxSlot);
    }

    public static int getMaxBindings() {
        return 16;
    }

    public void restore() {
        // 快速解绑 MMD 占用的 0..maxSlot 槽位，避免 GPU 状态残留与 CPU-GPU 驱动同步阻塞
        for (int i = 0; i < maxSlot; i++) {
            GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, i, 0);
        }
    }
}
