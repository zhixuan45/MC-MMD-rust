package com.shiroha.mmdskin.compat.iris;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** Terrain-only translucent replay. The original shadow attachments are restored before entities. */
public final class IrisTerrainTransmission {
    private static int depth, color, backupColor, framebuffer, size, depthFormat, colorFormat;
    private static boolean valid;

    private IrisTerrainTransmission() {}

    public static void beginFrame() { valid = false; }
    public static int depthTexture() { return valid ? depth : 0; }
    public static int colorTexture() { return valid ? color : 0; }

    /** The caller has already verified that replay writes only sourceColor and source depth. */
    public static boolean capture(int sourceDepthFbo, int sourceColor, int opaqueFbo,
                                  int resolution, int opaqueFormat, Runnable replay) {
        valid = false;
        if (!GL.getCapabilities().OpenGL43 || sourceDepthFbo <= 0 || sourceColor <= 0
                || opaqueFbo <= 0 || resolution <= 0
                || (long) resolution * resolution * 12 > 256L * 1024 * 1024) return false;
        try (var state = new ShadowReplayGlState()) {
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, sourceColor);
            int format = GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_INTERNAL_FORMAT);
            int minFilter = GL46C.glGetTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER);
            int magFilter = GL46C.glGetTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER);
            if (GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_WIDTH) != resolution
                    || GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_HEIGHT) != resolution
                    || (format != GL46C.GL_RGBA8 && format != GL46C.GL_RGBA16F
                    && format != GL46C.GL_RGBA32F)) return false;
            // Account for the two color copies as well as the depth snapshot.
            int colorBytes = format == GL46C.GL_RGBA32F ? 16 : format == GL46C.GL_RGBA16F ? 8 : 4;
            if ((long) resolution * resolution * (4 + colorBytes * 2) > 256L * 1024 * 1024) return false;
            allocate(resolution, opaqueFormat, format);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, color);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER,
                    minFilter == GL46C.GL_NEAREST ? GL46C.GL_NEAREST : GL46C.GL_LINEAR);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, magFilter);
            copyColor(sourceColor, backupColor);
            state.restore();
            try {
                GL46C.glViewport(0, 0, size, size);
                replay.run();
                GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
                copyDepth(sourceDepthFbo, framebuffer);
                copyColor(sourceColor, color);
            } finally {
                // Even a failed replay must not remove character casters or alter world transmission.
                GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
                copyDepth(opaqueFbo, sourceDepthFbo);
                copyColor(backupColor, sourceColor);
            }
            valid = true;
            return true;
        }
    }

    private static void allocate(int resolution, int opaqueFormat, int format) {
        if (depth != 0 && size == resolution && depthFormat == opaqueFormat && colorFormat == format) return;
        release();
        size = resolution;
        depthFormat = opaqueFormat;
        colorFormat = format;
        int unpack = GL46C.glGetInteger(GL46C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, 0);
        try {
            depth = texture(opaqueFormat, GL46C.GL_DEPTH_COMPONENT);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_MODE, GL46C.GL_COMPARE_REF_TO_TEXTURE);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_FUNC, GL46C.GL_LEQUAL);
            color = texture(format, GL46C.GL_RGBA);
            backupColor = texture(format, GL46C.GL_RGBA);
        } finally {
            GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, unpack);
        }
        framebuffer = GL46C.glGenFramebuffers();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, framebuffer);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_TEXTURE_2D, depth, 0);
        GL46C.glDrawBuffer(GL46C.GL_NONE);
        GL46C.glReadBuffer(GL46C.GL_NONE);
        if (GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Incomplete terrain transmission depth target");
    }

    private static int texture(int format, int components) {
        int id = GL46C.glGenTextures();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, format, size, size, 0, components, GL46C.GL_FLOAT, 0L);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_BASE_LEVEL, 0);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAX_LEVEL, 0);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, GL46C.GL_CLAMP_TO_EDGE);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_T, GL46C.GL_CLAMP_TO_EDGE);
        return id;
    }

    private static void copyColor(int from, int to) {
        GL46C.glCopyImageSubData(from, GL46C.GL_TEXTURE_2D, 0, 0, 0, 0,
                to, GL46C.GL_TEXTURE_2D, 0, 0, 0, 0, size, size, 1);
    }

    private static void copyDepth(int from, int to) {
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, from);
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, to);
        GL46C.glBlitFramebuffer(0, 0, size, size, 0, 0, size, size, GL46C.GL_DEPTH_BUFFER_BIT, GL46C.GL_NEAREST);
    }

    public static void release() {
        valid = false;
        if (framebuffer != 0) GL46C.glDeleteFramebuffers(framebuffer);
        if (depth != 0) GL46C.glDeleteTextures(depth);
        if (color != 0) GL46C.glDeleteTextures(color);
        if (backupColor != 0) GL46C.glDeleteTextures(backupColor);
        framebuffer = depth = color = backupColor = size = depthFormat = colorFormat = 0;
    }
}
