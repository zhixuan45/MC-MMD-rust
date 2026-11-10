package com.shiroha.mmdskin.compat.iris;

import com.shiroha.mmdskin.config.ConfigManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL46C;

/** Copies the terrain depth before Iris submits entities, without changing the host shadow map. */
public final class IrisTerrainShadow {
    private static final Logger LOGGER = LogManager.getLogger();

    private IrisTerrainShadow() {}

    public static void beginFrame(Object owner, boolean enabled) {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        frame.begin(owner, enabled);
        IrisTerrainTransmission.beginFrame();
    }

    public static void capture(Object owner) {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        if (frame.owner != owner || !frame.requested) return;
        frame.captured = false;
        IrisShadowSource.Source shadow;
        try {
            if (frame.sourceAccess == null || !frame.sourceAccess.matches(owner))
                frame.sourceAccess = new IrisShadowSource(owner.getClass());
            shadow = frame.sourceAccess.read(owner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            frame.failCapture("Iris shadow target lookup failed: " + e.getClass().getSimpleName());
            return;
        }
        if (shadow.framebuffer() <= 0 || shadow.texture() <= 0 || shadow.resolution() <= 0) {
            frame.failCapture("invalid Iris shadow target");
            return;
        }
        try (ShadowReplayGlState state = ShadowReplayGlState.depthCopy()) {
            int draw = state.draw;
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, shadow.framebuffer());
            if (GL46C.glCheckFramebufferStatus(GL46C.GL_READ_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE) {
                frame.failCapture("incomplete Iris shadow source framebuffer");
                return;
            }
            int attachmentType = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_READ_FRAMEBUFFER,
                    GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int source = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_READ_FRAMEBUFFER,
                    GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int level = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_READ_FRAMEBUFFER,
                    GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
            if (attachmentType != GL46C.GL_TEXTURE || source != shadow.texture() || level != 0) {
                frame.failCapture("Iris shadow depth identity mismatch");
                return;
            }
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, source);
            int sourceWidth = GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_WIDTH);
            int sourceHeight = GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_HEIGHT);
            int sourceFormat = GL46C.glGetTexLevelParameteri(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_TEXTURE_INTERNAL_FORMAT);
            if (sourceWidth != shadow.resolution() || sourceHeight != shadow.resolution()) {
                frame.failCapture("Iris shadow depth extent mismatch");
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
                frame.failCapture("unsupported shadow depth format " + sourceFormat);
                return;
            }
            int minFilter = GL46C.glGetTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER);
            int magFilter = GL46C.glGetTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER);
            if (frame.texture == 0 || frame.width != sourceWidth || frame.height != sourceHeight
                    || frame.format != storageFormat) {
                frame.releaseCaptureStorage();
                frame.releaseTransmissionStorage();
                frame.width = sourceWidth;
                frame.height = sourceHeight;
                frame.format = storageFormat;
                frame.texture = GL46C.glGenTextures();
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, frame.texture);
                GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, 0);
                GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, frame.format, frame.width, frame.height, 0,
                        GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, 0L);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_BASE_LEVEL, 0);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAX_LEVEL, 0);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, GL46C.GL_CLAMP_TO_EDGE);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_T, GL46C.GL_CLAMP_TO_EDGE);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_MODE, GL46C.GL_COMPARE_REF_TO_TEXTURE);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_FUNC, GL46C.GL_LEQUAL);
                frame.framebuffer = GL46C.glGenFramebuffers();
                GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, frame.framebuffer);
                GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                        GL46C.GL_TEXTURE_2D, frame.texture, 0);
                GL46C.glDrawBuffer(GL46C.GL_NONE);
                GL46C.glReadBuffer(GL46C.GL_NONE);
                if (GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE) {
                    frame.releaseCaptureStorage();
                    frame.failCapture("incomplete terrain snapshot framebuffer");
                    return;
                }
            }
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, frame.texture);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER,
                    minFilter == GL46C.GL_NEAREST ? GL46C.GL_NEAREST : GL46C.GL_LINEAR);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, magFilter);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, shadow.framebuffer());
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, frame.framebuffer);
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glBlitFramebuffer(0, 0, frame.width, frame.height, 0, 0, frame.width, frame.height,
                    GL46C.GL_DEPTH_BUFFER_BIT, GL46C.GL_NEAREST);
            frame.captured("ready");
            frame.warnedStatus = null;
            if (!frame.reported) {
                frame.reported = true;
                LOGGER.info("[MMD terrain shadow] captured Iris-owned depth before entities: {}x{}, sourceFbo={}, sourceDepth={}, hostDrawFbo={}, sourceFormat={}, storedFormat={}",
                        frame.width, frame.height, shadow.framebuffer(), source, draw, sourceFormat, frame.format);
            }
        }
    }

    public static int texture() {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        return frame.captureValid() ? frame.texture : 0;
    }

    public static void captureTransmission(Object owner, Object level, net.minecraft.client.Camera camera) {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        if (frame.owner != owner || !frame.requested || !frame.captureValid()) return;
        if (!ConfigManager.isTerrainTransmissionEnabled()) {
            frame.transmission = false;
            frame.replayStatus = "disabled by configuration";
            return;
        }
        try {
            if (IrisTerrainShadowReplay.capture(owner, level, camera, frame.sourceAccess.read(owner),
                    frame.framebuffer, frame.format)) {
                frame.capturedTransmission(IrisTerrainShadowReplay.status());
                if (!frame.transmissionReported) {
                    frame.transmissionReported = true;
                    LOGGER.info("[MMD terrain shadow] captured terrain transmission before entities: {}x{}, {}",
                            frame.width, frame.height, IrisTerrainShadowReplay.status());
                }
            } else {
                warnTransmissionUnavailable(IrisTerrainShadowReplay.status());
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IrisTerrainTransmission.beginFrame();
            String detail = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            warnTransmissionUnavailable(detail);
        }
    }

    private static void warnTransmissionUnavailable(String detail) {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        if (!detail.equals(frame.transmissionFailure)) {
            frame.failTransmission(detail);
            LOGGER.warn("[MMD terrain shadow] terrain transmission unavailable; opaque terrain remains active: {}", detail);
        }
    }

    public static void warnUnavailable() {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        if (frame.requested && !frame.captureValid() && !frame.status.equals(frame.warnedStatus)) {
            frame.warnedStatus = frame.status;
            LOGGER.warn("[MMD terrain shadow] classic receiver fallback: {}", frame.status);
        }
    }

    public static void destroy(Object owner) {
        TerrainShadowFrame frame = TerrainShadowFrame.current();
        if (frame.owner == owner) release();
    }

    public static void release() {
        TerrainShadowFrame.current().releaseAll();
    }

    private static boolean depthFormat(int value) {
        return value == GL46C.GL_DEPTH_COMPONENT16 || value == GL46C.GL_DEPTH_COMPONENT24
                || value == GL46C.GL_DEPTH_COMPONENT32 || value == GL46C.GL_DEPTH_COMPONENT32F;
    }
}
