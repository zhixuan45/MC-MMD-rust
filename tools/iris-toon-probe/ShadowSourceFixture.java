package com.shiroha.mmdskin.render.shader;

/** Mirrors the public Iris shadow-target API without loading a Minecraft world. */
public final class ShadowSourceFixture {
    public static final class Renderer {
        private Targets targets;

        public Renderer(int framebuffer, int texture, int resolution) {
            update(framebuffer, texture, resolution);
        }

        public void update(int framebuffer, int texture, int resolution) {
            targets = new Targets(framebuffer, texture, resolution);
        }
    }

    public static final class Targets {
        private final Framebuffer framebuffer;
        private final DepthTexture texture;
        private final int resolution;

        Targets(int framebuffer, int texture, int resolution) {
            this.framebuffer = new Framebuffer(framebuffer);
            this.texture = new DepthTexture(texture);
            this.resolution = resolution;
        }

        public Framebuffer getDepthSourceFb() { return framebuffer; }
        public DepthTexture getDepthTexture() { return texture; }
        public int getResolution() { return resolution; }
    }

    public record Framebuffer(int id) {
        public int getId() { return id; }
    }

    public record DepthTexture(int id) {
        public int getTextureId() { return id; }
    }
}
