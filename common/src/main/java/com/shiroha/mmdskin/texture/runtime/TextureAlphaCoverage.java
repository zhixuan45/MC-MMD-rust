package com.shiroha.mmdskin.texture.runtime;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Conservative UV bounds for the original alpha channel; no texture pixels are changed. */
public final class TextureAlphaCoverage {
    private static final int TILE = 16;
    private final int width, height, columns;
    private final byte[] minimum, maximum;
    private final int wordsPerRow;
    private final long[] positivePixels, nonOpaquePixels;
    private final boolean fullyOpaque, fullyTransparent;

    private TextureAlphaCoverage(int width, int height, byte[] minimum, byte[] maximum,
                                 long[] positivePixels, long[] nonOpaquePixels,
                                 boolean fullyOpaque, boolean fullyTransparent) {
        this.width = width;
        this.height = height;
        columns = (width + TILE - 1) / TILE;
        this.minimum = minimum;
        this.maximum = maximum;
        wordsPerRow = (width + 63) / 64;
        this.positivePixels = positivePixels;
        this.nonOpaquePixels = nonOpaquePixels;
        this.fullyOpaque = fullyOpaque;
        this.fullyTransparent = fullyTransparent;
    }

    public static TextureAlphaCoverage fromRgba(ByteBuffer pixels, int width, int height) {
        int columns = (width + TILE - 1) / TILE, rows = (height + TILE - 1) / TILE;
        byte[] min = new byte[columns * rows], max = new byte[min.length];
        int words = (width + 63) / 64;
        long[] positive = new long[words * height], nonOpaque = new long[positive.length];
        boolean opaque = true, transparent = true;
        Arrays.fill(min, (byte) 255);
        int base = pixels.position();
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int alpha = pixels.get(base + (y * width + x) * 4 + 3) & 255,
                        tile = (y / TILE) * columns + x / TILE;
                if (alpha < (min[tile] & 255)) min[tile] = (byte) alpha;
                if (alpha > (max[tile] & 255)) max[tile] = (byte) alpha;
                int word = y * words + x / 64;
                long bit = 1L << (x & 63);
                if (alpha > 0) positive[word] |= bit;
                if (alpha < 255) nonOpaque[word] |= bit;
                opaque &= alpha == 255;
                transparent &= alpha == 0;
            }
        // 常量 alpha 无需保留逐像素位图。
        if (opaque || transparent) { positive = new long[0]; nonOpaque = new long[0]; }
        return new TextureAlphaCoverage(width, height, min, max, positive, nonOpaque,
                opaque, transparent);
    }

    public boolean mayBlend(
            float u0, float v0, float u1, float v1, float u2, float v2, boolean includeOpaque) {
        float loU = Math.min(u0, Math.min(u1, u2)), hiU = Math.max(u0, Math.max(u1, u2));
        float loV = Math.min(v0, Math.min(v1, v2)), hiV = Math.max(v0, Math.max(v1, v2));
        if (!Float.isFinite(loU + hiU + loV + hiV)) return true;
        // 格式含 alpha 不代表像素透明；整图常量也适用于重复采样。
        if (fullyTransparent || (fullyOpaque && !includeOpaque)) return false;
        if (fullyOpaque) return true;
        // 混合贴图的重复边界保持保守。
        if (loU <= .5f / width
                || loV <= .5f / height
                || hiU >= 1 - .5f / width
                || hiV >= 1 - .5f / height) return true;
        // 外扩采样足迹，避免二值 alpha 的线性过滤边缘漏绘。
        int px0 = Math.max(0, (int) Math.floor(loU * width) - 1),
                px1 = Math.min(width - 1, (int) Math.ceil(hiU * width) + 1);
        int py0 = Math.max(0, (int) Math.floor(loV * height) - 1),
                py1 = Math.min(height - 1, (int) Math.ceil(hiV * height) + 1);
        int x0 = px0 / TILE, x1 = px1 / TILE;
        int y0 = py0 / TILE, y1 = py1 / TILE;
        int min = 255, max = 0;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                int tile = y * columns + x;
                min = Math.min(min, minimum[tile] & 255);
                max = Math.max(max, maximum[tile] & 255);
            }
        if (max == 0 || (!includeOpaque && min == 255)) return false;
        // 粗块不确定时细查像素位图；候选结果由排序器缓存。
        return anyPixel(positivePixels, px0, py0, px1, py1)
                && (includeOpaque || anyPixel(nonOpaquePixels, px0, py0, px1, py1));
    }

    private boolean anyPixel(long[] pixels, int x0, int y0, int x1, int y1) {
        int first = x0 / 64, last = x1 / 64;
        for (int y = y0; y <= y1; y++) {
            int row = y * wordsPerRow;
            for (int word = first; word <= last; word++) {
                long mask = -1L;
                if (word == first) mask &= -1L << (x0 & 63);
                if (word == last) mask &= -1L >>> (63 - (x1 & 63));
                if ((pixels[row + word] & mask) != 0) return true;
            }
        }
        return false;
    }

    public long ramBytes() {
        return (long) minimum.length + maximum.length
                + ((long) positivePixels.length + nonOpaquePixels.length) * Long.BYTES;
    }
}
