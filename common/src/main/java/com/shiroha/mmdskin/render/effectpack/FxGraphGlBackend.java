package com.shiroha.mmdskin.render.effectpack;

import org.lwjgl.opengl.GL33C;
import java.util.HashMap;
import java.util.Map;

/** 世界颜色图的 OpenGL 后端；复用节点目标，所有修改限定在保存的 GL 状态内。 */
final class FxGraphGlBackend implements FxRenderGraph.Backend<Integer> {
    private record Target(int framebuffer, int texture) { }
    private static final Map<String, Target> targets = new HashMap<>();
    private static FxEffect active;
    private static int width, height;
    private final int destination;
    private final FxWorldGraph graph;

    private FxGraphGlBackend(int destination, FxWorldGraph graph) { this.destination = destination; this.graph = graph; }

    static void render(FxEffect effect, int framebuffer, int w, int h) {
        try (var state = new WorldColorEffect.State()) {
            if (active != effect || width != w || height != h) {
                release(); active = effect; width = w; height = h;
            }
            GL33C.glDisable(GL33C.GL_SCISSOR_TEST);
            GL33C.glDisable(GL33C.GL_FRAMEBUFFER_SRGB);
            GL33C.glColorMaski(0, true, true, true, true);
            effect.graph().compile(w, h).execute(Map.of("scene", framebuffer), new FxGraphGlBackend(framebuffer, effect.graph()));
        } catch (RuntimeException e) {
            EffectPackRuntime.get().problem("graph:" + effect.id(), "世界 FX 图执行失败: " + e.getMessage());
        }
    }

    @Override public Integer create(FxRenderGraph.Resource resource) {
        Target existing = targets.get(resource.name());
        if (existing != null) return existing.framebuffer();
        int texture = GL33C.glGenTextures(), framebuffer = GL33C.glGenFramebuffers();
        try {
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA16F, resource.width(), resource.height(), 0, GL33C.GL_RGBA, GL33C.GL_FLOAT, 0L);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
            GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL33C.glFramebufferTexture2D(GL33C.GL_DRAW_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, texture, 0);
            GL33C.glDrawBuffer(GL33C.GL_COLOR_ATTACHMENT0);
            if (GL33C.glCheckFramebufferStatus(GL33C.GL_DRAW_FRAMEBUFFER) != GL33C.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("FX 图 framebuffer 不完整");
            targets.put(resource.name(), new Target(framebuffer, texture));
            return framebuffer;
        } catch (RuntimeException e) {
            GL33C.glDeleteFramebuffers(framebuffer); GL33C.glDeleteTextures(texture); throw e;
        }
    }

    @Override public void draw(FxRenderGraph.Pass pass, java.util.List<Integer> inputs, Integer output) {
        copy(inputs.getFirst(), output);
        FxEffect effect = graph.stages().stream().filter(s -> s.name().equals(pass.name())).findFirst().orElseThrow().effect();
        if (!WorldColorEffect.apply(effect, output, width, height)) throw new IllegalStateException("FX 节点编译失败: " + pass.name());
    }
    @Override public void present(Integer output) { copy(output, destination); }
    @Override public void release(Integer framebuffer) { /* 池化目标由重载/尺寸改变统一释放。 */ }

    private void copy(int source, int target) {
        GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, source);
        int previousRead = GL33C.glGetInteger(GL33C.GL_READ_BUFFER);
        try {
            GL33C.glReadBuffer(GL33C.GL_COLOR_ATTACHMENT0);
            GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, target);
            int[] buffers = new int[GL33C.glGetInteger(GL33C.GL_MAX_DRAW_BUFFERS)];
            for (int i = 0; i < buffers.length; i++) buffers[i] = GL33C.glGetInteger(GL33C.GL_DRAW_BUFFER0 + i);
            try {
                GL33C.glDrawBuffer(GL33C.GL_COLOR_ATTACHMENT0);
                GL33C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL33C.GL_COLOR_BUFFER_BIT, GL33C.GL_NEAREST);
            } finally { GL33C.glDrawBuffers(buffers); }
        } finally { GL33C.glReadBuffer(previousRead); }
    }
    static void release() {
        for (Target target : targets.values()) { GL33C.glDeleteFramebuffers(target.framebuffer()); GL33C.glDeleteTextures(target.texture()); }
        targets.clear(); active = null; width = height = 0;
    }
}
