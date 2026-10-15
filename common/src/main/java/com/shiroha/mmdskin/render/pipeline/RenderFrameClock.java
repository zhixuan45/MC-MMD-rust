package com.shiroha.mmdskin.render.pipeline;

import com.shiroha.mmdskin.render.policy.RenderPriorityService;

/** GameRenderer.render boundary, including paused frames and GUI rendering. Render thread only. */
public final class RenderFrameClock {
    private static long frame;
    private RenderFrameClock() { }
    public static long current() { return frame; }
    public static void begin() { frame++; }
    public static void finish() { RenderPriorityService.get().completeRenderFrame(); }
}
