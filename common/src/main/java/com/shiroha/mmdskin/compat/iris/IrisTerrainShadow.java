package com.shiroha.mmdskin.compat.iris;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL46C;

/** Copies the terrain depth before Iris submits entities, without changing the host shadow map. */
public final class IrisTerrainShadow {
    private static final Logger LOGGER = LogManager.getLogger();
    private static Object renderer;
    private static boolean requested, valid, reported;
    private static int texture, framebuffer, width, height, format;
    private static String status = "not captured", warnedStatus;
    private static IrisShadowSource sourceAccess;

    private IrisTerrainShadow() {}

    public static void beginFrame(Object owner, boolean enabled) {
        if (renderer != owner) {
            release();
            renderer = owner;
        }
        requested = enabled;
        valid = false;
        status = enabled ? "terrain capture not reached" : "disabled";
    }

    public static void capture(Object owner) {
        if (renderer != owner || !requested) return;
        valid = false;
        IrisShadowSource.Source shadow;
        try {
            if (sourceAccess == null || !sourceAccess.matches(owner))
                sourceAccess = new IrisShadowSource(owner.getClass());
            shadow = sourceAccess.read(owner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            status = "Iris shadow target lookup failed: " + e.getClass().getSimpleName();
            return;
        }
        if (shadow.framebuffer() <= 0 || shadow.texture() <= 0 || shadow.resolution() <= 0) {
            status = "invalid Iris shadow target";
            return;
        }
        int draw = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
        int previousTexture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        boolean scissor = GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST);
        try {
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, shadow.framebuffer());
            if (GL46C.glCheckFramebufferStatus(GL46C.GL_READ_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE) {
                status = "incomplete Iris shadow source framebuffer";
                return;
            }
            int attachmentType = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_READ_FRAMEBUFFER,
                    GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int source = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_READ_FRAMEBUFFER,
                    GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int level = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_READ_FRAMEBUFFER,
                    GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
            if (attachmentType != GL46C.GL_TEXTURE || source != shadow.texture() || level != 0) {
                status = "Iris shadow depth identity mismatch";
                return;
            }
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, source);
            int sourceWidth = GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_WIDTH);
            int sourceHeight = GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_HEIGHT);
            int sourceFormat = GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_INTERNAL_FORMAT);
            if (sourceWidth != shadow.resolution() || sourceHeight != shadow.resolution()) {
                status = "Iris shadow depth extent mismatch";
                return;
            }
            int storageFormat = sourceFormat;
            // Iris requests unsized DEPTH_COMPONENT; NVIDIA reports that enum even for 24-bit storage.
            if (sourceFormat == GL46C.GL_DEPTH_COMPONENT) {
                int bits = GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_DEPTH_SIZE);
                int type = GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_DEPTH_TYPE);
                storageFormat = type == GL46C.GL_FLOAT ? GL46C.GL_DEPTH_COMPONENT32F
                        : bits > 24 ? GL46C.GL_DEPTH_COMPONENT32
                        : bits > 16 ? GL46C.GL_DEPTH_COMPONENT24 : GL46C.GL_DEPTH_COMPONENT16;
            }
            if (sourceWidth <= 0 || sourceHeight <= 0 || !depthFormat(storageFormat)) {
                status = "unsupported shadow depth format " + sourceFormat;
                return;
            }
            int minFilter = GL46C.glGetTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER);
            int magFilter = GL46C.glGetTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER);
            if (texture == 0 || width != sourceWidth || height != sourceHeight || format != storageFormat) {
                releaseStorage();
                width = sourceWidth;
                height = sourceHeight;
                format = storageFormat;
                texture = GL46C.glGenTextures();
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
                int unpack = GL46C.glGetInteger(GL46C.GL_PIXEL_UNPACK_BUFFER_BINDING);
                GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, 0);
                try {
                    GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, format, width, height, 0,
                            GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, 0L);
                } finally {
                    GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, unpack);
                }
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_BASE_LEVEL, 0);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAX_LEVEL, 0);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, GL46C.GL_CLAMP_TO_EDGE);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_T, GL46C.GL_CLAMP_TO_EDGE);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_MODE, GL46C.GL_COMPARE_REF_TO_TEXTURE);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_FUNC, GL46C.GL_LEQUAL);
                framebuffer = GL46C.glGenFramebuffers();
                GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, framebuffer);
                GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                        GL46C.GL_TEXTURE_2D, texture, 0);
                GL46C.glDrawBuffer(GL46C.GL_NONE);
                GL46C.glReadBuffer(GL46C.GL_NONE);
                if (GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE) {
                    releaseStorage();
                    status = "incomplete terrain snapshot framebuffer";
                    return;
                }
            }
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER,
                    minFilter == GL46C.GL_NEAREST ? GL46C.GL_NEAREST : GL46C.GL_LINEAR);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, magFilter);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, shadow.framebuffer());
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL46C.GL_DEPTH_BUFFER_BIT, GL46C.GL_NEAREST);
            valid = true;
            status = "ready";
            warnedStatus = null;
            if (!reported) {
                reported = true;
                LOGGER.info("[MMD terrain shadow] captured Iris-owned depth before entities: {}x{}, sourceFbo={}, sourceDepth={}, hostDrawFbo={}, sourceFormat={}, storedFormat={}",
                        width, height, shadow.framebuffer(), source, draw, sourceFormat, format);
            }
        } finally {
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, previousTexture);
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, draw);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, read);
            if (scissor) GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
        }
    }

    public static int texture() { return valid ? texture : 0; }

    public static void warnUnavailable() {
        if (requested && !valid && !status.equals(warnedStatus)) {
            warnedStatus = status;
            LOGGER.warn("[MMD terrain shadow] classic receiver fallback: {}", status);
        }
    }

    public static void destroy(Object owner) {
        if (renderer == owner) release();
    }

    public static void release() {
        valid = false;
        requested = false;
        renderer = null;
        reported = false;
        status = "not captured";
        warnedStatus = null;
        sourceAccess = null;
        releaseStorage();
    }

    private static void releaseStorage() {
        if (framebuffer != 0) GL46C.glDeleteFramebuffers(framebuffer);
        if (texture != 0) GL46C.glDeleteTextures(texture);
        texture = framebuffer = width = height = format = 0;
        valid = false;
    }

    private static boolean depthFormat(int value) {
        return value == GL46C.GL_DEPTH_COMPONENT16 || value == GL46C.GL_DEPTH_COMPONENT24
                || value == GL46C.GL_DEPTH_COMPONENT32 || value == GL46C.GL_DEPTH_COMPONENT32F;
    }
}
