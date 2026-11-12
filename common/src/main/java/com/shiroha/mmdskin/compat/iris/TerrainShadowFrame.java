package com.shiroha.mmdskin.compat.iris;

import org.lwjgl.opengl.GL46C;

/**
 * Single owner for the per-frame Iris terrain shadow capture and replay resources.
 */
final class TerrainShadowFrame {
    private static final TerrainShadowFrame CURRENT = new TerrainShadowFrame();

    static TerrainShadowFrame current() {
        return CURRENT;
    }

    Object owner;
    IrisShadowSource sourceAccess;

    boolean requested;
    boolean captured;
    boolean transmission;
    boolean reported;
    boolean transmissionReported;

    int texture;
    int framebuffer;
    int width;
    int height;
    int format;

    int transmissionDepth;
    int transmissionColor;
    int transmissionBackupColor;
    int transmissionBackupAuxColor;
    int transmissionFramebuffer;
    int transmissionSize;
    int transmissionDepthFormat;
    int transmissionColorFormat;
    int transmissionAuxColorFormat;

    String status = "not captured";
    String reason = "";
    String warnedStatus;
    String transmissionFailure;

    Object replayProgramSet;
    boolean replaySupported;
    int replayColorTargets;
    String replayStatus = "not attempted";
    String packStatus = "not checked";

    private TerrainShadowFrame() {}

    boolean captureValid() {
        return requested && captured && texture != 0;
    }

    boolean transmissionValid() {
        return transmission && transmissionDepth != 0 && transmissionColor != 0;
    }

    void begin(Object newOwner, boolean enabled) {
        if (owner != newOwner) {
            releaseAll();
            owner = newOwner;
        }
        requested = enabled;
        captured = false;
        transmission = false;
        status = enabled ? "terrain capture not reached" : "disabled";
        reason = "";
        replayStatus = "not attempted";
    }

    void failCapture(String detail) {
        captured = false;
        transmission = false;
        status = detail;
        reason = detail;
    }

    void captured(String detail) {
        captured = true;
        status = detail;
        reason = "";
    }

    void failTransmission(String detail) {
        transmission = false;
        transmissionFailure = detail;
    }

    void capturedTransmission(String detail) {
        transmission = true;
        transmissionFailure = null;
        replayStatus = detail;
    }

    void releaseCaptureStorage() {
        if (framebuffer != 0) GL46C.glDeleteFramebuffers(framebuffer);
        if (texture != 0) GL46C.glDeleteTextures(texture);
        texture = framebuffer = width = height = format = 0;
        captured = false;
        transmission = false;
    }

    void releaseTransmissionStorage() {
        if (transmissionFramebuffer != 0) GL46C.glDeleteFramebuffers(transmissionFramebuffer);
        if (transmissionDepth != 0) GL46C.glDeleteTextures(transmissionDepth);
        if (transmissionColor != 0) GL46C.glDeleteTextures(transmissionColor);
        if (transmissionBackupColor != 0) GL46C.glDeleteTextures(transmissionBackupColor);
        if (transmissionBackupAuxColor != 0) GL46C.glDeleteTextures(transmissionBackupAuxColor);
        transmissionFramebuffer = transmissionDepth = transmissionColor = transmissionBackupColor = 0;
        transmissionBackupAuxColor = transmissionAuxColorFormat = 0;
        transmissionSize = transmissionDepthFormat = transmissionColorFormat = 0;
        transmission = false;
    }

    void resetReplayState() {
        replayProgramSet = null;
        replaySupported = false;
        replayColorTargets = 0;
        replayStatus = "not attempted";
        packStatus = "not checked";
    }

    void releaseAll() {
        releaseCaptureStorage();
        releaseTransmissionStorage();
        requested = false;
        owner = null;
        sourceAccess = null;
        reported = false;
        transmissionReported = false;
        status = "not captured";
        reason = "";
        warnedStatus = null;
        transmissionFailure = null;
        resetReplayState();
    }
}
