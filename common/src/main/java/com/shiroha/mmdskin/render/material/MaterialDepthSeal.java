package com.shiroha.mmdskin.render.material;

import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;

/** 半透明颜色混合结束后只补深度；保存所有 MRT 写掩码，不改 Blaze3D 缓存。 */
final class MaterialDepthSeal implements AutoCloseable {
    private final byte[][] masks;

    MaterialDepthSeal() {
        masks = new byte[GL46C.glGetInteger(GL46C.GL_MAX_DRAW_BUFFERS)][4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            for (int i = 0; i < masks.length; i++) {
                GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, i, mask);
                for (int c = 0; c < 4; c++) masks[i][c] = mask.get(c);
                GL46C.glColorMaski(i, false, false, false, false);
            }
        }
    }

    @Override public void close() {
        for (int i = 0; i < masks.length; i++) {
            byte[] mask = masks[i];
            GL46C.glColorMaski(i, mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0);
        }
    }
}
