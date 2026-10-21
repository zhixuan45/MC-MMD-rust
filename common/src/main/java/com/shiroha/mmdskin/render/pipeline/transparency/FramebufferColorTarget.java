package com.shiroha.mmdskin.render.pipeline.transparency;

import org.lwjgl.opengl.GL46C;

/**
 * Identifies the actual primary color storage, not the FBO wrapper ID.
 * Used for cautious composition across Iris entity and post-particle stages.
 */
record FramebufferColorTarget(int type, int objectId, int width, int height) {
    static FramebufferColorTarget capture() {
        if (GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == 0
                || GL46C.glGetInteger(GL46C.GL_DRAW_BUFFER0) != GL46C.GL_COLOR_ATTACHMENT0)
            return null;
        int type = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_DRAW_FRAMEBUFFER,
                GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type != GL46C.GL_TEXTURE && type != GL46C.GL_RENDERBUFFER) return null;
        int name = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_DRAW_FRAMEBUFFER,
                GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int[] viewport = new int[4];
        GL46C.glGetIntegerv(GL46C.GL_VIEWPORT, viewport);
        if (name == 0 || viewport[2] <= 0 || viewport[3] <= 0) return null;
        return new FramebufferColorTarget(type, name, viewport[2], viewport[3]);
    }

    static boolean compatible(FramebufferColorTarget a, FramebufferColorTarget b) {
        return a != null && b != null && a.type == b.type && a.objectId == b.objectId
                && a.width == b.width && a.height == b.height;
    }
}
