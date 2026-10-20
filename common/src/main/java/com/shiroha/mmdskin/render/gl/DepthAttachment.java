package com.shiroha.mmdskin.render.gl;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** Shared description of the currently bound draw FBO. Never guesses a texture target. */
public record DepthAttachment(int objectType, int objectId, int rawFormat, int format,
                              int depthBits, int stencilBits, int componentType,
                              int width, int height, int samples) {
    public static DepthAttachment inspect() {
        if (GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == 0) return null;
        int kind = parameter(GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (kind == GL46C.GL_NONE) return null;
        int id = parameter(GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int bits = parameter(GL46C.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
        int stencil = parameter(GL46C.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE);
        int type = parameter(GL46C.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE);
        int samples = GL46C.glGetInteger(GL46C.GL_SAMPLES);
        int raw, width, height;
        if (kind == GL46C.GL_RENDERBUFFER) {
            int old = GL46C.glGetInteger(GL46C.GL_RENDERBUFFER_BINDING);
            try {
                GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, id);
                raw = GL46C.glGetRenderbufferParameteri(GL46C.GL_RENDERBUFFER, GL46C.GL_RENDERBUFFER_INTERNAL_FORMAT);
                width = GL46C.glGetRenderbufferParameteri(GL46C.GL_RENDERBUFFER, GL46C.GL_RENDERBUFFER_WIDTH);
                height = GL46C.glGetRenderbufferParameteri(GL46C.GL_RENDERBUFFER, GL46C.GL_RENDERBUFFER_HEIGHT);
            } finally {
                GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, old);
            }
        } else if (kind == GL46C.GL_TEXTURE && (GL.getCapabilities().OpenGL45
                || GL.getCapabilities().GL_ARB_direct_state_access)) {
            if (parameter(GL46C.GL_FRAMEBUFFER_ATTACHMENT_LAYERED) != 0
                    || parameter(GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE) != 0) return null;
            int level = parameter(GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
            raw = GL46C.glGetTextureLevelParameteri(id, level, GL46C.GL_TEXTURE_INTERNAL_FORMAT);
            width = GL46C.glGetTextureLevelParameteri(id, level, GL46C.GL_TEXTURE_WIDTH);
            height = GL46C.glGetTextureLevelParameteri(id, level, GL46C.GL_TEXTURE_HEIGHT);
        } else return null;
        return new DepthAttachment(kind, id, raw, resolveFormat(raw, bits, stencil, type),
                bits, stencil, type, width, height, samples);
    }

    private static int parameter(int name) {
        return GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_DRAW_FRAMEBUFFER,
                GL46C.GL_DEPTH_ATTACHMENT, name);
    }

    /** Unsized formats must use actual storage precision AND component type, never a D24 default. */
    public static int resolveFormat(int raw, int bits, int stencil, int type) {
        return switch (raw) {
            case GL46C.GL_DEPTH_COMPONENT16, GL46C.GL_DEPTH_COMPONENT24, GL46C.GL_DEPTH_COMPONENT32,
                    GL46C.GL_DEPTH_COMPONENT32F, GL46C.GL_DEPTH24_STENCIL8, GL46C.GL_DEPTH32F_STENCIL8 -> raw;
            case GL46C.GL_DEPTH_COMPONENT, GL46C.GL_DEPTH_STENCIL -> {
                if (type == GL46C.GL_FLOAT && bits == 32)
                    yield stencil == 0 ? GL46C.GL_DEPTH_COMPONENT32F
                            : stencil == 8 ? GL46C.GL_DEPTH32F_STENCIL8 : 0;
                if (type != GL46C.GL_UNSIGNED_NORMALIZED) yield 0;
                if (stencil == 8 && bits == 24) yield GL46C.GL_DEPTH24_STENCIL8;
                if (stencil != 0) yield 0;
                yield switch (bits) {
                    case 16 -> GL46C.GL_DEPTH_COMPONENT16;
                    case 24 -> GL46C.GL_DEPTH_COMPONENT24;
                    case 32 -> GL46C.GL_DEPTH_COMPONENT32;
                    default -> 0;
                };
            }
            default -> 0;
        };
    }

    public boolean contains(int[] viewport) {
        return viewport[0] >= 0 && viewport[1] >= 0 && viewport[2] > 0 && viewport[3] > 0
                && (long) viewport[0] + viewport[2] <= width
                && (long) viewport[1] + viewport[3] <= height;
    }

    /** Check the copy itself, not just framebuffer completeness. The caller restores GL state. */
    public static boolean blit(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1) {
        // Do not clear a pre-existing error and then misreport our copy as successful.
        if (GL46C.glGetError() != GL46C.GL_NO_ERROR) return false;
        GL46C.glBlitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1,
                GL46C.GL_DEPTH_BUFFER_BIT, GL46C.GL_NEAREST);
        return GL46C.glGetError() == GL46C.GL_NO_ERROR;
    }
}
