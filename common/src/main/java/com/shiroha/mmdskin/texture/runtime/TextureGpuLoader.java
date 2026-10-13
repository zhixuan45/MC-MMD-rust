package com.shiroha.mmdskin.texture.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.bridge.runtime.NativeTexturePort;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 文件职责：封装纹理从 native 解码到 GPU 上传的无状态操作。 */
final class TextureGpuLoader {
    private static final Logger LOGGER = LogManager.getLogger(TextureGpuLoader.class);
    private static final int MAX_FAILURE_LOGS = 32;
    private static final Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger FAILURE_LOG_COUNT = new AtomicInteger();
    private static final BufferAllocator NATIVE_BUFFER_ALLOCATOR = new BufferAllocator() {
        @Override
        public ByteBuffer allocate(int size) {
            return MemoryUtil.memAlloc(size);
        }

        @Override
        public void free(ByteBuffer buffer) {
            MemoryUtil.memFree(buffer);
        }
    };

    interface BufferAllocator {
        ByteBuffer allocate(int size);

        void free(ByteBuffer buffer);
    }

    private TextureGpuLoader() {
    }

    static TextureRepository.Texture loadToGpu(String filename, NativeTexturePort port) {
        return loadToGpu(filename, port, "unknown", "runtime");
    }

    static TextureRepository.Texture loadToGpu(String filename, NativeTexturePort port,
                                               String kind, String context) {
        long handle = port.loadTexture(filename);
        if (handle == 0) {
            logDecodeFailure(kind, filename, context);
            return null;
        }

        TextureRepository.Texture uploaded = null;
        Throwable failure = null;
        try {
            int width = port.textureWidth(handle);
            int height = port.textureHeight(handle);
            long dataAddr = port.textureData(handle);
            boolean hasAlpha = port.textureHasAlpha(handle);
            int size = width * height * (hasAlpha ? 4 : 3);
            ByteBuffer buf = NATIVE_BUFFER_ALLOCATOR.allocate(size);
            try {
                int texId = 0;
                int previousTexture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
                try {
                    texId = GL46C.glGenTextures();
                    GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texId);
                    port.copyTextureData(buf, dataAddr, size);
                    buf.rewind();
                    uploadPixels(width, height, hasAlpha, buf);
                    configureTexture();
                    uploaded = buildTexture(texId, width, height, hasAlpha);
                    uploaded.hasTranslucentPixels = hasTranslucentPixels(buf, hasAlpha);
                } catch (RuntimeException | Error e) {
                    try {
                        deleteGlTexture(texId);
                    } catch (RuntimeException | Error cleanupError) {
                        addSuppressed(e, cleanupError);
                    }
                    throw e;
                } finally {
                    GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, previousTexture);
                }
            } finally {
                NATIVE_BUFFER_ALLOCATOR.free(buf);
            }
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            Throwable nativeCleanupFailure = null;
            try {
                port.deleteTexture(handle);
            } catch (RuntimeException | Error cleanupError) {
                nativeCleanupFailure = cleanupError;
            }

            if (uploaded != null && (failure != null || nativeCleanupFailure != null)) {
                try {
                    deleteGlTexture(uploaded);
                } catch (RuntimeException | Error glCleanupError) {
                    addSuppressed(failure != null ? failure : nativeCleanupFailure, glCleanupError);
                }
            }
            if (nativeCleanupFailure != null) {
                if (failure != null) {
                    addSuppressed(failure, nativeCleanupFailure);
                } else if (nativeCleanupFailure instanceof RuntimeException runtimeError) {
                    throw runtimeError;
                } else {
                    throw (Error) nativeCleanupFailure;
                }
            }
        }
        return uploaded;
    }

    static TextureRepository.PredecodedTexture decode(String filename, NativeTexturePort port) {
        return decode(filename, port, "unknown", "preload");
    }

    static TextureRepository.PredecodedTexture decode(String filename, NativeTexturePort port,
                                                       String kind, String context) {
        return decode(filename, port, kind, context, NATIVE_BUFFER_ALLOCATOR);
    }

    static TextureRepository.PredecodedTexture decode(String filename, NativeTexturePort port,
                                                       String kind, String context, BufferAllocator allocator) {
        long handle = port.loadTexture(filename);
        if (handle == 0) {
            logDecodeFailure(kind, filename, context);
            return null;
        }
        TextureRepository.PredecodedTexture predecoded = null;
        ByteBuffer transferredBuffer = null;
        Throwable failure = null;
        try {
            int width = port.textureWidth(handle);
            int height = port.textureHeight(handle);
            long dataAddr = port.textureData(handle);
            boolean hasAlpha = port.textureHasAlpha(handle);
            int size = width * height * (hasAlpha ? 4 : 3);
            ByteBuffer buf = allocator.allocate(size);
            boolean transferred = false;
            try {
                port.copyTextureData(buf, dataAddr, size);
                buf.rewind();
                predecoded = new TextureRepository.PredecodedTexture(buf, width, height, hasAlpha);
                transferred = true;
                transferredBuffer = buf;
                return predecoded;
            } finally {
                if (!transferred) {
                    allocator.free(buf);
                }
            }
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            try {
                port.deleteTexture(handle);
            } catch (RuntimeException | Error cleanupError) {
                if (transferredBuffer != null) {
                    try {
                        allocator.free(transferredBuffer);
                    } catch (RuntimeException | Error releaseError) {
                        addSuppressed(cleanupError, releaseError);
                    }
                }
                if (failure != null) {
                    addSuppressed(failure, cleanupError);
                } else {
                    throw cleanupError;
                }
            }
        }
    }

    private static void addSuppressed(Throwable primary, Throwable cleanup) {
        if (primary != null && primary != cleanup) primary.addSuppressed(cleanup);
    }

    private static synchronized void logDecodeFailure(String kind, String path, String context) {
        String safeKind = bounded(kind, 32);
        String safePath = bounded(path, 240);
        String key = safeKind + '\0' + safePath;
        if (FAILURE_LOG_COUNT.get() >= MAX_FAILURE_LOGS || !REPORTED_FAILURES.add(key)) return;
        FAILURE_LOG_COUNT.incrementAndGet();
        LOGGER.warn("[MMD纹理] kind={} context={} path={} result=decode_failed fallback=caller-defined",
                safeKind, bounded(context, 96), safePath);
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(Math.min(value.length(), limit));
        for (int i = 0; i < value.length() && result.length() < limit; i++) {
            char c = value.charAt(i);
            result.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (value.length() > limit) result.append("...");
        return result.toString();
    }

    static TextureRepository.Texture uploadPredecoded(TextureRepository.PredecodedTexture pre) {
        int texId = 0;
        int previousTexture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        try {
            texId = GL46C.glGenTextures();
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texId);
            uploadPixels(pre.width, pre.height, pre.hasAlpha, pre.pixelData());
            configureTexture();
            TextureRepository.Texture texture = buildTexture(texId, pre.width, pre.height, pre.hasAlpha);
            texture.hasTranslucentPixels = hasTranslucentPixels(pre.pixelData(), pre.hasAlpha);
            return texture;
        } catch (RuntimeException | Error e) {
            deleteGlTexture(texId);
            throw e;
        } finally {
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, previousTexture);
            pre.release();
        }
    }

    static TextureRepository.Texture createColorTexture(float[] color) {
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        int texId = 0;
        int previousTexture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        try {
            texId = GL46C.glGenTextures();
            for (int i = 0; i < 4; i++) {
                float value = color != null && i < color.length ? color[i] : 1.0f;
                pixel.put((byte) Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 255.0f));
            }
            pixel.flip();
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texId);
            uploadPixels(1, 1, true, pixel);
            configureTexture();
            TextureRepository.Texture texture = buildTexture(texId, 1, 1, true);
            texture.hasTranslucentPixels = hasTranslucentPixels(pixel, true);
            return texture;
        } catch (RuntimeException | Error e) {
            deleteGlTexture(texId);
            throw e;
        } finally {
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, previousTexture);
            MemoryUtil.memFree(pixel);
        }
    }

    static void deleteGlTexture(TextureRepository.Texture tex) {
        if (tex != null) {
            deleteGlTexture(tex.tex);
            tex.tex = 0;
        }
    }

    static boolean hasTranslucentPixels(ByteBuffer pixels, boolean hasAlpha) {
        if (!hasAlpha) return false;
        for (int i = pixels.position() + 3; i < pixels.limit(); i += 4) {
            int alpha = Byte.toUnsignedInt(pixels.get(i));
            if (alpha > 0 && alpha < 255) return true;
        }
        return false;
    }

    static void deleteGlTexture(int texId) {
        if (texId <= 0) return;
        if (RenderSystem.isOnRenderThreadOrInit()) {
            GL46C.glDeleteTextures(texId);
        } else {
            RenderSystem.recordRenderCall(() -> GL46C.glDeleteTextures(texId));
        }
    }

    private static void uploadPixels(int w, int h, boolean hasAlpha, ByteBuffer buf) {
        PixelUnpackState previousState = PixelUnpackState.capture();
        try {
            // 绑定 PBO 时 OpenGL 会把 ByteBuffer 数据解释为偏移量，重置全部
            // 解包参数，避免纹理上传继承其他渲染器遗留的状态。
            GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_ALIGNMENT, 1);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_ROW_LENGTH, 0);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_SKIP_ROWS, 0);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_SKIP_PIXELS, 0);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_IMAGE_HEIGHT, 0);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_SKIP_IMAGES, 0);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_SWAP_BYTES, GL46C.GL_FALSE);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_LSB_FIRST, GL46C.GL_FALSE);

            int format = hasAlpha ? GL46C.GL_RGBA : GL46C.GL_RGB;
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, format, w, h, 0, format, GL46C.GL_UNSIGNED_BYTE, buf);
        } finally {
            previousState.restore();
        }
    }

    private static final class PixelUnpackState {
        private final int pixelUnpackBuffer;
        private final int alignment;
        private final int rowLength;
        private final int skipRows;
        private final int skipPixels;
        private final int imageHeight;
        private final int skipImages;
        private final int swapBytes;
        private final int lsbFirst;

        private PixelUnpackState() {
            pixelUnpackBuffer = GL46C.glGetInteger(GL46C.GL_PIXEL_UNPACK_BUFFER_BINDING);
            alignment = GL46C.glGetInteger(GL46C.GL_UNPACK_ALIGNMENT);
            rowLength = GL46C.glGetInteger(GL46C.GL_UNPACK_ROW_LENGTH);
            skipRows = GL46C.glGetInteger(GL46C.GL_UNPACK_SKIP_ROWS);
            skipPixels = GL46C.glGetInteger(GL46C.GL_UNPACK_SKIP_PIXELS);
            imageHeight = GL46C.glGetInteger(GL46C.GL_UNPACK_IMAGE_HEIGHT);
            skipImages = GL46C.glGetInteger(GL46C.GL_UNPACK_SKIP_IMAGES);
            swapBytes = GL46C.glGetInteger(GL46C.GL_UNPACK_SWAP_BYTES);
            lsbFirst = GL46C.glGetInteger(GL46C.GL_UNPACK_LSB_FIRST);
        }

        static PixelUnpackState capture() {
            return new PixelUnpackState();
        }

        void restore() {
            GL46C.glPixelStorei(GL46C.GL_UNPACK_ALIGNMENT, alignment);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_ROW_LENGTH, rowLength);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_SKIP_ROWS, skipRows);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_SKIP_PIXELS, skipPixels);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_IMAGE_HEIGHT, imageHeight);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_SKIP_IMAGES, skipImages);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_SWAP_BYTES, swapBytes);
            GL46C.glPixelStorei(GL46C.GL_UNPACK_LSB_FIRST, lsbFirst);
            GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, pixelUnpackBuffer);
        }
    }

    private static void configureTexture() {
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAX_LEVEL, 0);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_LINEAR);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_LINEAR);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, 0);
    }

    private static TextureRepository.Texture buildTexture(int texId, int w, int h, boolean hasAlpha) {
        TextureRepository.Texture t = new TextureRepository.Texture();
        t.tex = texId;
        t.hasAlpha = hasAlpha;
        t.vramSize = (long) w * h * (hasAlpha ? 4 : 3);
        return t;
    }
}
