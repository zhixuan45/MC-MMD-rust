package com.shiroha.mmdskin.texture.runtime;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Conservative UV bounds for the original alpha channel; no texture pixels are changed. */
public final class TextureAlphaCoverage {
    private static final int TILE = 16;
    private final int width, height, columns;
    private final byte[] minimum, maximum;

    private TextureAlphaCoverage(int width, int height, byte[] minimum, byte[] maximum) {
        this.width = width;
        this.height = height;
        columns = (width + TILE - 1) / TILE;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public static TextureAlphaCoverage fromRgba(ByteBuffer pixels, int width, int height) {
        int columns = (width + TILE - 1) / TILE, rows = (height + TILE - 1) / TILE;
        byte[] min = new byte[columns * rows], max = new byte[min.length];
        Arrays.fill(min, (byte) 255);
        int base = pixels.position();
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int alpha = pixels.get(base + (y * width + x) * 4 + 3) & 255,
                        tile = (y / TILE) * columns + x / TILE;
                if (alpha < (min[tile] & 255)) min[tile] = (byte) alpha;
                if (alpha > (max[tile] & 255)) max[tile] = (byte) alpha;
            }
        return new TextureAlphaCoverage(width, height, min, max);
    }

    public boolean mayBlend(
            float u0, float v0, float u1, float v1, float u2, float v2, boolean includeOpaque) {
        float loU = Math.min(u0, Math.min(u1, u2)), hiU = Math.max(u0, Math.max(u1, u2));
        float loV = Math.min(v0, Math.min(v1, v2)), hiV = Math.max(v0, Math.max(v1, v2));
        // Repeat wrapping or non-finite UVs keep the conservative original path.
        if (!Float.isFinite(loU + hiU + loV + hiV)
                || loU <= .5f / width
                || loV <= .5f / height
                || hiU >= 1 - .5f / width
                || hiV >= 1 - .5f / height) return true;
        int x0 = Math.max(0, ((int) Math.floor(loU * width) - 1) / TILE),
                x1 = Math.min(columns - 1, ((int) Math.ceil(hiU * width) + 1) / TILE);
        int y0 = Math.max(0, ((int) Math.floor(loV * height) - 1) / TILE),
                y1 = Math.min((height - 1) / TILE, ((int) Math.ceil(hiV * height) + 1) / TILE);
        int min = 255, max = 0;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                int tile = y * columns + x;
                min = Math.min(min, minimum[tile] & 255);
                max = Math.max(max, maximum[tile] & 255);
                if (max > 0 && (includeOpaque || min < 255)) return true;
            }
        return false;
    }

    public long ramBytes() {
        return (long) minimum.length + maximum.length;
    }
}
