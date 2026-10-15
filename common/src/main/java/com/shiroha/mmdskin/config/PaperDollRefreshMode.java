package com.shiroha.mmdskin.config;

/** Offscreen throttling is an explicit quality option; FULL preserves per-frame animation. */
public enum PaperDollRefreshMode {
    FULL(0), FPS_60(60), FPS_30(30);
    public final int framesPerSecond;
    PaperDollRefreshMode(int fps) { framesPerSecond = fps; }
}
