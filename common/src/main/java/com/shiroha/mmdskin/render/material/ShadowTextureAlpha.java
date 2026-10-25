package com.shiroha.mmdskin.render.material;

import org.lwjgl.opengl.GL46C;

import java.util.HashMap;
import java.util.Map;

/** 阴影实心对照只临时覆盖采样 alpha，保留宿主程序和 GPU 蒙皮输入。 */
final class ShadowTextureAlpha implements AutoCloseable {
    private final Map<Integer, Integer> swizzles = new HashMap<>();

    void apply(int texture) {
        if (texture <= 0 || swizzles.containsKey(texture)) return;
        // Caller has bound this material's diffuse texture on unit 0.
        int previous = GL46C.glGetTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_SWIZZLE_A);
        swizzles.put(texture, previous);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_SWIZZLE_A, GL46C.GL_ONE);
    }

    @Override
    public void close() {
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        int bound = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        try {
            for (var entry : swizzles.entrySet()) {
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, entry.getKey());
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_SWIZZLE_A, entry.getValue());
            }
        } finally {
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, bound);
            GL46C.glActiveTexture(active);
        }
    }
}
