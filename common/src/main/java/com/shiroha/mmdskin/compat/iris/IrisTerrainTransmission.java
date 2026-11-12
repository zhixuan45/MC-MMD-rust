package com.shiroha.mmdskin.compat.iris;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** Terrain-only translucent replay. The original shadow attachments are restored before entities. */
public final class IrisTerrainTransmission {
    private IrisTerrainTransmission() {}

    public static void beginFrame() {
        TerrainShadowFrame.current().transmission = false;
    }

    public static int depthTexture() {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        return frame.transmissionValid() ? frame.transmissionDepth : 0;
    }

    public static int colorTexture() {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        return frame.transmissionValid() ? frame.transmissionColor : 0;
    }

    static String status() {
        return TerrainShadowFrame.current().replayStatus;
    }

    /** Single-color BSL compatibility entry point. */
    public static boolean capture(int sourceDepthFbo, int sourceColor, int opaqueFbo,
                                  int resolution, int opaqueFormat, Runnable replay) {
        return capture(sourceDepthFbo, new int[]{sourceColor}, opaqueFbo, resolution, opaqueFormat, replay);
    }

    /** Every color written by the verified shadow program must be backed up before replay. */
    public static boolean capture(int sourceDepthFbo, int[] sourceColors, int opaqueFbo,
                                  int resolution, int opaqueFormat, Runnable replay) {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        frame.transmission = false;
        if (!GL.getCapabilities().OpenGL43 || sourceDepthFbo <= 0 || sourceColors == null
                || sourceColors.length < 1 || sourceColors.length > 2
                || java.util.Arrays.stream(sourceColors).anyMatch(id -> id <= 0)
                || (sourceColors.length == 2 && sourceColors[0] == sourceColors[1])
                || opaqueFbo <= 0 || resolution <= 0
                || (long) resolution * resolution * 12 > 256L * 1024 * 1024)
            return unavailable("unsupported context, invalid transmission target, or allocation budget exceeded");
        int sourceColor = sourceColors[0];
        try (var state = new ShadowReplayGlState()) {
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, sourceColor);
            int sourceFormat = GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_INTERNAL_FORMAT);
            int format = sourceFormat;
            // Iris defaults to unsized RGBA; NVIDIA reports that enum for its actual RGBA8 storage.
            if (sourceFormat == GL46C.GL_RGBA) {
                for (int channel : new int[]{GL46C.GL_TEXTURE_RED_SIZE, GL46C.GL_TEXTURE_GREEN_SIZE,
                        GL46C.GL_TEXTURE_BLUE_SIZE, GL46C.GL_TEXTURE_ALPHA_SIZE}) {
                    if (GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, channel) != 8)
                        return unavailable("unsupported unsized RGBA channel precision");
                }
                format = GL46C.GL_RGBA8;
            }
            int minFilter = GL46C.glGetTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER);
            int magFilter = GL46C.glGetTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER);
            if (GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_WIDTH) != resolution
                    || GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_HEIGHT) != resolution
                    || (format != GL46C.GL_RGBA8 && format != GL46C.GL_RGBA16F
                    && format != GL46C.GL_RGBA32F))
                return unavailable("unsupported transmission color format " + sourceFormat + " or extent mismatch");
            int auxFormat = 0;
            if (sourceColors.length == 2) {
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, sourceColors[1]);
                auxFormat = colorFormat(resolution);
                if (auxFormat == 0) return unavailable("unsupported auxiliary shadow color format or extent");
            }
            // The auxiliary light-shaft target is restored, but is not a receiver texture.
            int colorBytes = format == GL46C.GL_RGBA32F ? 16 : format == GL46C.GL_RGBA16F ? 8 : 4;
            int auxBytes = auxFormat == 0 ? 0 : auxFormat == GL46C.GL_RGBA32F ? 16
                    : auxFormat == GL46C.GL_RGBA16F ? 8 : 4;
            if ((long) resolution * resolution * (4 + colorBytes * 2 + auxBytes) > 256L * 1024 * 1024)
                return unavailable("terrain transmission allocation budget exceeded");
            allocate(resolution, opaqueFormat, format, auxFormat);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, frame.transmissionColor);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER,
                    minFilter == GL46C.GL_NEAREST ? GL46C.GL_NEAREST : GL46C.GL_LINEAR);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, magFilter);
            copyColor(sourceColor, frame.transmissionBackupColor);
            if (sourceColors.length == 2) copyColor(sourceColors[1], frame.transmissionBackupAuxColor);
            state.restore();
            try {
                GL46C.glViewport(0, 0, frame.transmissionSize, frame.transmissionSize);
                replay.run();
                GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
                copyDepth(sourceDepthFbo, frame.transmissionFramebuffer);
                copyColor(sourceColor, frame.transmissionColor);
            } finally {
                // Even a failed replay must not remove character casters or alter world transmission.
                GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
                copyDepth(opaqueFbo, sourceDepthFbo);
                copyColor(frame.transmissionBackupColor, sourceColor);
                if (sourceColors.length == 2) copyColor(frame.transmissionBackupAuxColor, sourceColors[1]);
            }
            frame.transmission = true;
            frame.replayStatus = "ready: sourceFormat=" + sourceFormat + ", storedFormat=" + format;
            return true;
        }
    }

    private static boolean unavailable(String reason) {
        TerrainShadowFrame.current().replayStatus = reason;
        return false;
    }

    private static int colorFormat(int resolution) {
        if (GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_WIDTH) != resolution
                || GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_HEIGHT) != resolution)
            return 0;
        int format = GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_INTERNAL_FORMAT);
        if (format == GL46C.GL_RGBA) {
            for (int channel : new int[]{GL46C.GL_TEXTURE_RED_SIZE, GL46C.GL_TEXTURE_GREEN_SIZE,
                    GL46C.GL_TEXTURE_BLUE_SIZE, GL46C.GL_TEXTURE_ALPHA_SIZE})
                if (GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, channel) != 8) return 0;
            format = GL46C.GL_RGBA8;
        }
        return format == GL46C.GL_RGBA8 || format == GL46C.GL_RGBA16F || format == GL46C.GL_RGBA32F ? format : 0;
    }

    private static void allocate(int resolution, int opaqueFormat, int format, int auxFormat) {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        if (frame.transmissionDepth != 0 && frame.transmissionSize == resolution
                && frame.transmissionDepthFormat == opaqueFormat
                && frame.transmissionColorFormat == format && frame.transmissionAuxColorFormat == auxFormat) return;
        frame.releaseTransmissionStorage();
        frame.transmissionSize = resolution;
        frame.transmissionDepthFormat = opaqueFormat;
        frame.transmissionColorFormat = format;
        frame.transmissionAuxColorFormat = auxFormat;
        int unpack = GL46C.glGetInteger(GL46C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, 0);
        try {
            frame.transmissionDepth = texture(opaqueFormat, GL46C.GL_DEPTH_COMPONENT);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_MODE, GL46C.GL_COMPARE_REF_TO_TEXTURE);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_FUNC, GL46C.GL_LEQUAL);
            frame.transmissionColor = texture(format, GL46C.GL_RGBA);
            frame.transmissionBackupColor = texture(format, GL46C.GL_RGBA);
            if (auxFormat != 0) frame.transmissionBackupAuxColor = texture(auxFormat, GL46C.GL_RGBA);
        } finally {
            GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, unpack);
        }
        frame.transmissionFramebuffer = GL46C.glGenFramebuffers();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, frame.transmissionFramebuffer);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                GL46C.GL_TEXTURE_2D, frame.transmissionDepth, 0);
        GL46C.glDrawBuffer(GL46C.GL_NONE);
        GL46C.glReadBuffer(GL46C.GL_NONE);
        if (GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Incomplete terrain transmission depth target");
    }

    private static int texture(int format, int components) {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        int id = GL46C.glGenTextures();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, format,
                frame.transmissionSize, frame.transmissionSize, 0, components, GL46C.GL_FLOAT, 0L);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_BASE_LEVEL, 0);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAX_LEVEL, 0);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, GL46C.GL_CLAMP_TO_EDGE);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_T, GL46C.GL_CLAMP_TO_EDGE);
        return id;
    }

    private static void copyColor(int from, int to) {
        int size = TerrainShadowFrame.current().transmissionSize;
        GL46C.glCopyImageSubData(from, GL46C.GL_TEXTURE_2D, 0, 0, 0, 0,
                to, GL46C.GL_TEXTURE_2D, 0, 0, 0, 0, size, size, 1);
    }

    private static void copyDepth(int from, int to) {
        int size = TerrainShadowFrame.current().transmissionSize;
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, from);
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, to);
        GL46C.glBlitFramebuffer(0, 0, size, size, 0, 0, size, size, GL46C.GL_DEPTH_BUFFER_BIT, GL46C.GL_NEAREST);
    }

    public static void release() {
        TerrainShadowFrame.current().releaseTransmissionStorage();
    }
}
