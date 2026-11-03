package com.shiroha.mmdskin.render.effectpack;

import org.lwjgl.opengl.GL33C;

/** Glass reads an independent pre-model snapshot; blitting also resolves multisampled targets. */
final class FxSceneColor {
    private static int texture, framebuffer, width, height, format;

    static boolean capture(int unit, int[] viewport) {
        if (viewport[2] <= 0 || viewport[3] <= 0) return false;
        int source = GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
        int color = GL33C.glGetInteger(GL33C.GL_DRAW_BUFFER0);
        if (color == GL33C.GL_NONE) return false;
        int oldRead = GL33C.glGetInteger(GL33C.GL_READ_FRAMEBUFFER_BINDING);
        int unpack = GL33C.glGetInteger(GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        boolean scissor = GL33C.glIsEnabled(GL33C.GL_SCISSOR_TEST);
        boolean srgb = GL33C.glIsEnabled(GL33C.GL_FRAMEBUFFER_SRGB);
        int sourceRead = -1;
        int copyFormat = GL33C.glGetInteger(GL33C.GL_SAMPLES) > 0 ? multisampleFormat(source, color) : GL33C.GL_RGBA16F;
        try {
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
            if (texture == 0) texture = GL33C.glGenTextures();
            if (framebuffer == 0) framebuffer = GL33C.glGenFramebuffers();
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            GL33C.glBindSampler(unit, 0);
            if (width != viewport[2] || height != viewport[3] || format != copyFormat) {
                GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
                GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, copyFormat, viewport[2], viewport[3],
                        0, GL33C.GL_RGBA, GL33C.GL_FLOAT, 0L);
                GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
                GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
                GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
                GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
                width = viewport[2]; height = viewport[3]; format = copyFormat;
            }
            GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL33C.glFramebufferTexture2D(GL33C.GL_DRAW_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            GL33C.glDrawBuffer(GL33C.GL_COLOR_ATTACHMENT0);
            if (GL33C.glCheckFramebufferStatus(GL33C.GL_DRAW_FRAMEBUFFER) != GL33C.GL_FRAMEBUFFER_COMPLETE) return false;
            GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, source);
            sourceRead = GL33C.glGetInteger(GL33C.GL_READ_BUFFER);
            GL33C.glReadBuffer(color);
            GL33C.glDisable(GL33C.GL_SCISSOR_TEST);
            GL33C.glDisable(GL33C.GL_FRAMEBUFFER_SRGB);
            GL33C.glBlitFramebuffer(viewport[0], viewport[1], viewport[0]+width, viewport[1]+height,
                    0,0,width,height,GL33C.GL_COLOR_BUFFER_BIT,GL33C.GL_NEAREST);
            return true;
        } finally {
            if (sourceRead >= 0) GL33C.glReadBuffer(sourceRead);
            GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, oldRead);
            GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, source);
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, unpack);
            if (scissor) GL33C.glEnable(GL33C.GL_SCISSOR_TEST);
            if (srgb) GL33C.glEnable(GL33C.GL_FRAMEBUFFER_SRGB);
        }
    }

    private static int multisampleFormat(int source, int attachment) {
        // Multisample resolves require matching formats, including normalized vs floating-point targets.
        if (source == 0) return GL33C.GL_RGBA8;
        int type = GL33C.glGetFramebufferAttachmentParameteri(GL33C.GL_DRAW_FRAMEBUFFER, attachment, GL33C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int object = GL33C.glGetFramebufferAttachmentParameteri(GL33C.GL_DRAW_FRAMEBUFFER, attachment, GL33C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (type == GL33C.GL_RENDERBUFFER) {
            int previous = GL33C.glGetInteger(GL33C.GL_RENDERBUFFER_BINDING);
            try {
                GL33C.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, object);
                return GL33C.glGetRenderbufferParameteri(GL33C.GL_RENDERBUFFER, GL33C.GL_RENDERBUFFER_INTERNAL_FORMAT);
            } finally { GL33C.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, previous); }
        }
        int previous = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D_MULTISAMPLE);
        try {
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D_MULTISAMPLE, object);
            return GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D_MULTISAMPLE, 0, GL33C.GL_TEXTURE_INTERNAL_FORMAT);
        } finally { GL33C.glBindTexture(GL33C.GL_TEXTURE_2D_MULTISAMPLE, previous); }
    }

    static void release() {
        if (texture != 0) GL33C.glDeleteTextures(texture);
        if (framebuffer != 0) GL33C.glDeleteFramebuffers(framebuffer);
        texture = framebuffer = width = height = format = 0;
    }
    private FxSceneColor() { }
}
