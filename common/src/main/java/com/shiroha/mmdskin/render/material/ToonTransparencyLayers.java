package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

/** 三渲二透明深度层资源及原始 GL 状态作用域。 */
public final class ToonTransparencyLayers implements AutoCloseable {
    private static final int MAX_LAYERS = 8;
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final int[] DEPTH_TEXTURES = new int[MAX_LAYERS];
    private static final int[] COVERAGE_TEXTURES = new int[MAX_LAYERS];
    private static final int[] LAYER_QUERIES = new int[MAX_LAYERS];
    private static int framebuffer, poolWidth, poolHeight, poolFormat, poolCount;
    private static boolean warned;

    private final int program, modeLocation, depthLocation, coverageLocation, previousLocation;
    private final int depthUnit, coverageUnit;
    private final int oldMode, oldDepthUnit, oldCoverageUnit, oldPrevious;
    private final GlState state;
    private final int layers;
    private boolean closed;
    private boolean queryActive, conditionalActive;
    private int peelPasses, colorPasses;

    private ToonTransparencyLayers(int program, int modeLocation, int depthLocation, int coverageLocation,
                                   int previousLocation, int depthUnit, int coverageUnit,
                                   GlState state, int layers) {
        this.program = program;
        this.modeLocation = modeLocation;
        this.depthLocation = depthLocation;
        this.coverageLocation = coverageLocation;
        this.previousLocation = previousLocation;
        this.depthUnit = depthUnit;
        this.coverageUnit = coverageUnit;
        this.state = state;
        this.layers = layers;
        oldMode = GL46C.glGetUniformi(program, modeLocation);
        oldDepthUnit = GL46C.glGetUniformi(program, depthLocation);
        oldCoverageUnit = GL46C.glGetUniformi(program, coverageLocation);
        oldPrevious = GL46C.glGetUniformi(program, previousLocation);
    }

    /** 不支持当前 Toon 程序或目标帧缓冲时返回 null。 */
    public static ToonTransparencyLayers begin() {
        if ("false".equalsIgnoreCase(System.getProperty("mmdskin.toonTransparencyLayers"))) return null;
        if (!GL.getCapabilities().OpenGL40 || !RenderSystem.isOnRenderThread()) return null;
        if (activeQuery(GL46C.GL_SAMPLES_PASSED) || activeQuery(GL46C.GL_ANY_SAMPLES_PASSED)
                || (GL.getCapabilities().OpenGL43 && activeQuery(GL46C.GL_ANY_SAMPLES_PASSED_CONSERVATIVE))) return null;
        int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        if (program <= 0) return null;
        int mode = GL46C.glGetUniformLocation(program, "ToonTransparencyMode");
        int depth = GL46C.glGetUniformLocation(program, "ToonTransparencyDepth");
        int coverage = GL46C.glGetUniformLocation(program, "ToonTransparencyCoverage");
        int previous = GL46C.glGetUniformLocation(program, "ToonTransparencyHasPrevious");
        if (mode < 0 || depth < 0 || coverage < 0 || previous < 0) return null;

        int requestedLayers = requestedLayers();
        if (requestedLayers <= 0) return null;
        GlState state = new GlState();
        ToonTransparencyLayers scope = null;
        try {
            int[] units = freeTextureUnits(program, depth, coverage);
            if (units == null) return null;
            DepthTarget target = depthTarget(state);
            if (target == null || (state.depthFunc != GL46C.GL_LESS && state.depthFunc != GL46C.GL_LEQUAL)) return null;
            long bytesPerLayer = (long) target.width * target.height * (bytesPerPixel(target.format) + 1);
            int layers = (int) Math.min(requestedLayers, Math.min(MAX_LAYERS, MAX_BYTES / bytesPerLayer));
            if (layers < 1 || !allocate(target, layers)) {
                return null;
            }
            scope = new ToonTransparencyLayers(program, mode, depth, coverage, previous,
                    units[0], units[1], state, layers);
            if (framebuffer == 0) {
                scope.close();
                return null;
            }
            ensureQueries(layers);
            state.captureTextureUnit(units[0], units[1]);
            GL46C.glUseProgram(program);
            GL46C.glUniform1i(depth, units[0]);
            GL46C.glUniform1i(coverage, units[1]);
            GL46C.glUniform1i(mode, 0);
            GL46C.glUniform1i(previous, 0);
            GL46C.glUseProgram(state.program);
            return scope;
        } catch (RuntimeException e) {
            if (scope != null) scope.close(); else state.close();
            if (!warned) {
                warned = true;
                LogManager.getLogger().warn("Toon 透明深度分层不可用，保持现有透明绘制: {}", e.getMessage());
            }
            return null;
        }
    }

    public int layers() { return layers; }
    public int peelPasses() { return peelPasses; }
    public int colorPasses() { return colorPasses; }
    public static long allocatedBytes() {
        return (long) poolWidth * poolHeight * (bytesPerPixel(poolFormat) + 1) * poolCount;
    }

    /** 把当前不透明深度复制到目标层，再记录本层最近的透明片元。 */
    public void beginPeel(int layer) {
        checkLayer(layer);
        requireProgram();
        if (queryActive) throw new IllegalStateException("上一透明层查询尚未结束");
        endConditional();
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER,
                poolFormat == GL46C.GL_DEPTH24_STENCIL8 || poolFormat == GL46C.GL_DEPTH32F_STENCIL8
                        ? GL46C.GL_DEPTH_STENCIL_ATTACHMENT : GL46C.GL_DEPTH_ATTACHMENT,
                GL46C.GL_TEXTURE_2D, DEPTH_TEXTURES[layer], 0);
        GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0,
                GL46C.GL_TEXTURE_2D, COVERAGE_TEXTURES[layer], 0);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        if (GL46C.glCheckFramebufferStatus(GL46C.GL_DRAW_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("深度层附件不完整");
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, state.drawFbo);
        GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        int x = state.viewport[0], y = state.viewport[1];
        GL46C.glBlitFramebuffer(x, y, x + state.viewport[2], y + state.viewport[3],
                x, y, x + state.viewport[2], y + state.viewport[3],
                GL46C.GL_DEPTH_BUFFER_BIT, GL46C.GL_NEAREST);
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL46C.glColorMaski(0, true, true, true, true);
        GL46C.glDisablei(GL46C.GL_BLEND, 0);
        GL46C.glScissor(x, y, state.viewport[2], state.viewport[3]);
        GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer clear = stack.callocFloat(4);
            GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, clear);
        }
        restoreReadTarget();
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL46C.glViewport(state.viewport[0], state.viewport[1], state.viewport[2], state.viewport[3]);
        restoreScissor();
        GL46C.glEnable(GL46C.GL_DEPTH_TEST);
        GL46C.glDepthFunc(GL46C.GL_LEQUAL);
        GL46C.glDepthMask(true);
        bindLayer(layer == 0 ? -1 : layer - 1);
        setUniforms(1, layer > 0 ? 1 : 0);
        if (layer > 0) beginConditional(LAYER_QUERIES[layer - 1]);
        GL46C.glBeginQuery(GL46C.GL_ANY_SAMPLES_PASSED, LAYER_QUERIES[layer]);
        queryActive = true;
        peelPasses++;
    }

    /** 回到原目标，供下一层或颜色阶段继续绘制。 */
    public void endPeel() {
        requireOpen();
        if (queryActive) {
            GL46C.glEndQuery(GL46C.GL_ANY_SAMPLES_PASSED);
            queryActive = false;
        }
        endConditional();
        restoreTarget();
        restoreDepthState();
        state.restoreCoverageColorState();
        setUniforms(0, 0);
    }

    /** 绘制超过最远已剥离层的尾部片元。 */
    public void beginTail() {
        requireProgram();
        endConditional();
        restoreTarget();
        restoreDepthState();
        GL46C.glDepthMask(false);
        bindLayer(layers - 1);
        setUniforms(2, 1);
        beginConditional(LAYER_QUERIES[layers - 1]);
        colorPasses++;
    }

    /** 只绘制指定深度层的颜色。按远到近顺序调用。 */
    public void beginLayer(int layer) {
        checkLayer(layer);
        requireProgram();
        endConditional();
        restoreTarget();
        restoreDepthState();
        GL46C.glDepthMask(false);
        bindLayer(layer);
        setUniforms(3, 1);
        beginConditional(LAYER_QUERIES[layer]);
        colorPasses++;
    }

    /** 结束透明阶段，恢复原目标和 Toon shader 的普通模式。 */
    public void endStage() {
        requireOpen();
        endConditional();
        restoreTarget();
        restoreDepthState();
        setUniforms(0, 0);
    }

    private void checkLayer(int layer) {
        requireOpen();
        if (layer < 0 || layer >= layers) throw new IllegalArgumentException("透明层索引越界");
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("透明分层作用域已关闭");
    }

    private void requireProgram() {
        requireOpen();
        if (GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM) != program)
            throw new IllegalStateException("透明分层期间 Toon 程序已切换");
    }

    private void setUniforms(int mode, int hasPrevious) {
        GL46C.glUniform1i(modeLocation, mode);
        GL46C.glUniform1i(previousLocation, hasPrevious);
    }

    private void beginConditional(int query) {
        GL46C.glBeginConditionalRender(query, GL46C.GL_QUERY_NO_WAIT);
        conditionalActive = true;
    }

    private void endConditional() {
        if (!conditionalActive) return;
        GL46C.glEndConditionalRender();
        conditionalActive = false;
    }

    private void bindLayer(int layer) {
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + depthUnit);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, layer < 0 ? 0 : DEPTH_TEXTURES[layer]);
        GL46C.glBindSampler(depthUnit, 0);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + coverageUnit);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, layer < 0 ? 0 : COVERAGE_TEXTURES[layer]);
        GL46C.glBindSampler(coverageUnit, 0);
        GL46C.glActiveTexture(state.activeTexture);
    }

    private void restoreReadTarget() {
        GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, state.readFbo);
    }

    private void restoreTarget() {
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, state.drawFbo);
        restoreReadTarget();
        GL46C.glViewport(state.viewport[0], state.viewport[1], state.viewport[2], state.viewport[3]);
        restoreScissor();
    }

    private void restoreScissor() {
        GL46C.glScissor(state.scissorBox[0], state.scissorBox[1], state.scissorBox[2], state.scissorBox[3]);
        enabled(GL46C.GL_SCISSOR_TEST, state.scissorEnabled);
    }

    private void restoreDepthState() {
        GL46C.glDepthFunc(state.depthFunc);
        GL46C.glDepthMask(state.depthMask);
        enabled(GL46C.GL_DEPTH_TEST, state.depthTest);
    }

    private static void enabled(int capability, boolean value) {
        if (value) GL46C.glEnable(capability); else GL46C.glDisable(capability);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (queryActive) GL46C.glEndQuery(GL46C.GL_ANY_SAMPLES_PASSED);
        queryActive = false;
        endConditional();
        restoreTarget();
        state.restoreCoverageColorState();
        GL46C.glUseProgram(program);
        GL46C.glUniform1i(modeLocation, oldMode);
        GL46C.glUniform1i(depthLocation, oldDepthUnit);
        GL46C.glUniform1i(coverageLocation, oldCoverageUnit);
        GL46C.glUniform1i(previousLocation, oldPrevious);
        state.close();
    }

    /** 渲染线程调用；释放共享分层目标。 */
    public static void cleanup() {
        if (framebuffer != 0) GL46C.glDeleteFramebuffers(framebuffer);
        for (int texture : DEPTH_TEXTURES) if (texture != 0) GL46C.glDeleteTextures(texture);
        for (int texture : COVERAGE_TEXTURES) if (texture != 0) GL46C.glDeleteTextures(texture);
        for (int query : LAYER_QUERIES) if (query != 0) GL46C.glDeleteQueries(query);
        java.util.Arrays.fill(DEPTH_TEXTURES, 0);
        java.util.Arrays.fill(COVERAGE_TEXTURES, 0);
        java.util.Arrays.fill(LAYER_QUERIES, 0);
        framebuffer = poolWidth = poolHeight = poolFormat = poolCount = 0;
        warned = false;
    }

    private static int requestedLayers() {
        String value = System.getProperty("mmdskin.toonTransparencyLayerCount");
        if (value == null) return 4;
        try {
            int count = Integer.parseInt(value);
            return count >= 1 && count <= MAX_LAYERS ? count : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean activeQuery(int target) {
        return GL46C.glGetQueryi(target, GL46C.GL_CURRENT_QUERY) != 0;
    }

    private static void ensureQueries(int count) {
        for (int i = 0; i < count; i++) {
            if (LAYER_QUERIES[i] == 0) LAYER_QUERIES[i] = GL46C.glGenQueries();
            if (LAYER_QUERIES[i] == 0) throw new IllegalStateException("透明层查询对象创建失败");
        }
    }

    private static int[] freeTextureUnits(int program, int depthLocation, int coverageLocation) {
        int limit = GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_IMAGE_UNITS);
        boolean[] occupied = new boolean[limit];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer size = stack.mallocInt(1), type = stack.mallocInt(1);
            int active = GL46C.glGetProgrami(program, GL46C.GL_ACTIVE_UNIFORMS);
            for (int i = 0; i < active; i++) {
                String name = GL46C.glGetActiveUniform(program, i, size, type);
                if (!isSampler(type.get(0)) || name == null) continue;
                for (int element = 0; element < Math.max(1, size.get(0)); element++) {
                    String entry = size.get(0) > 1 ? name.replace("[0]", "[" + element + "]") : name;
                    int location = GL46C.glGetUniformLocation(program, entry);
                    if (location < 0 || location == depthLocation || location == coverageLocation) continue;
                    int unit = GL46C.glGetUniformi(program, location);
                    if (unit < 0 || unit >= limit) return null;
                    occupied[unit] = true;
                }
            }
        }
        int first = -1, second = -1;
        for (int i = limit - 1; i >= 0; i--) {
            if (occupied[i]) continue;
            if (first < 0) first = i; else { second = i; break; }
        }
        return second < 0 ? null : new int[] { first, second };
    }

    private static boolean isSampler(int type) {
        return switch (type) {
            case GL46C.GL_SAMPLER_1D, GL46C.GL_SAMPLER_2D, GL46C.GL_SAMPLER_3D,
                    GL46C.GL_SAMPLER_CUBE, GL46C.GL_SAMPLER_1D_SHADOW, GL46C.GL_SAMPLER_2D_SHADOW,
                    GL46C.GL_SAMPLER_CUBE_SHADOW, GL46C.GL_SAMPLER_1D_ARRAY, GL46C.GL_SAMPLER_2D_ARRAY,
                    GL46C.GL_SAMPLER_1D_ARRAY_SHADOW, GL46C.GL_SAMPLER_2D_ARRAY_SHADOW,
                    GL46C.GL_SAMPLER_2D_RECT, GL46C.GL_SAMPLER_2D_RECT_SHADOW, GL46C.GL_SAMPLER_BUFFER,
                    GL46C.GL_SAMPLER_2D_MULTISAMPLE,
                    GL46C.GL_SAMPLER_2D_MULTISAMPLE_ARRAY, GL46C.GL_SAMPLER_CUBE_MAP_ARRAY,
                    GL46C.GL_SAMPLER_CUBE_MAP_ARRAY_SHADOW, GL46C.GL_INT_SAMPLER_1D,
                    GL46C.GL_INT_SAMPLER_2D, GL46C.GL_INT_SAMPLER_3D, GL46C.GL_INT_SAMPLER_CUBE,
                    GL46C.GL_INT_SAMPLER_1D_ARRAY, GL46C.GL_INT_SAMPLER_2D_ARRAY,
                    GL46C.GL_INT_SAMPLER_2D_RECT, GL46C.GL_INT_SAMPLER_BUFFER,
                    GL46C.GL_INT_SAMPLER_2D_MULTISAMPLE, GL46C.GL_UNSIGNED_INT_SAMPLER_1D,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_2D, GL46C.GL_UNSIGNED_INT_SAMPLER_3D,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_CUBE, GL46C.GL_UNSIGNED_INT_SAMPLER_1D_ARRAY,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY, GL46C.GL_UNSIGNED_INT_SAMPLER_2D_RECT,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_BUFFER, GL46C.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE -> true;
            default -> false;
        };
    }

    private record DepthTarget(int format, int width, int height, int viewportX, int viewportY,
                               int viewportWidth, int viewportHeight, boolean stencil) { }

    private static DepthTarget depthTarget(GlState state) {
        if (state.drawFbo == 0 || GL46C.glIsEnabled(GL46C.GL_STENCIL_TEST)) return null;
        int kind = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_DRAW_FRAMEBUFFER,
                GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (kind == GL46C.GL_NONE) return null;
        int id = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_DRAW_FRAMEBUFFER,
                GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int format, width, height;
        int samples = GL46C.glGetInteger(GL46C.GL_SAMPLES);
        if (kind == GL46C.GL_RENDERBUFFER) {
            int old = GL46C.glGetInteger(GL46C.GL_RENDERBUFFER_BINDING);
            GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, id);
            format = GL46C.glGetRenderbufferParameteri(GL46C.GL_RENDERBUFFER, GL46C.GL_RENDERBUFFER_INTERNAL_FORMAT);
            width = GL46C.glGetRenderbufferParameteri(GL46C.GL_RENDERBUFFER, GL46C.GL_RENDERBUFFER_WIDTH);
            height = GL46C.glGetRenderbufferParameteri(GL46C.GL_RENDERBUFFER, GL46C.GL_RENDERBUFFER_HEIGHT);
            GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, old);
        } else if (kind == GL46C.GL_TEXTURE && (GL.getCapabilities().OpenGL45
                || GL.getCapabilities().GL_ARB_direct_state_access)) {
            if (GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_DRAW_FRAMEBUFFER,
                    GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_LAYERED) != 0
                    || GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_DRAW_FRAMEBUFFER,
                    GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE) != 0) return null;
            int level = GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_DRAW_FRAMEBUFFER,
                    GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
            format = GL46C.glGetTextureLevelParameteri(id, level, GL46C.GL_TEXTURE_INTERNAL_FORMAT);
            width = GL46C.glGetTextureLevelParameteri(id, level, GL46C.GL_TEXTURE_WIDTH);
            height = GL46C.glGetTextureLevelParameteri(id, level, GL46C.GL_TEXTURE_HEIGHT);
        } else return null;
        int x = state.viewport[0], y = state.viewport[1], w = state.viewport[2], h = state.viewport[3];
        if (samples != 0 || x < 0 || y < 0 || w <= 0 || h <= 0
                || (long) x + w > width || (long) y + h > height) return null;
        boolean stencil = format == GL46C.GL_DEPTH24_STENCIL8 || format == GL46C.GL_DEPTH32F_STENCIL8;
        return switch (format) {
            case GL46C.GL_DEPTH_COMPONENT24, GL46C.GL_DEPTH_COMPONENT32,
                    GL46C.GL_DEPTH_COMPONENT32F, GL46C.GL_DEPTH24_STENCIL8, GL46C.GL_DEPTH32F_STENCIL8 ->
                    new DepthTarget(format, width, height, x, y, w, h, stencil);
            default -> null;
        };
    }

    private static boolean allocate(DepthTarget target, int layers) {
        if ((long) target.width * target.height * (bytesPerPixel(target.format) + 1) * layers > MAX_BYTES) return false;
        if (framebuffer != 0 && poolWidth >= target.width && poolHeight >= target.height
                && poolFormat == target.format && poolCount >= layers) return true;
        // 小型 GUI 目标不能反复挤掉世界目标的共享缓冲。
        if (framebuffer != 0 && poolFormat != target.format
                && (long) target.width * target.height <= (long) poolWidth * poolHeight) return false;
        if (framebuffer != 0 && poolFormat == target.format) {
            int width = Math.max(poolWidth, target.width), height = Math.max(poolHeight, target.height);
            int count = Math.max(poolCount, layers);
            if ((long) width * height * (bytesPerPixel(target.format) + 1) * count > MAX_BYTES) return false;
            target = new DepthTarget(target.format, width, height, target.viewportX, target.viewportY,
                    target.viewportWidth, target.viewportHeight, target.stencil);
            layers = count;
        }
        cleanup();
        int oldDraw = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldRead = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
        int oldRenderbuffer = GL46C.glGetInteger(GL46C.GL_RENDERBUFFER_BINDING);
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        int oldTexture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        try {
            framebuffer = GL46C.glGenFramebuffers();
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, framebuffer);
            for (int i = 0; i < layers; i++) {
                DEPTH_TEXTURES[i] = GL46C.glGenTextures();
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, DEPTH_TEXTURES[i]);
                int external = target.stencil ? GL46C.GL_DEPTH_STENCIL : GL46C.GL_DEPTH_COMPONENT;
                int type = target.format == GL46C.GL_DEPTH_COMPONENT16 ? GL46C.GL_UNSIGNED_SHORT
                        : target.format == GL46C.GL_DEPTH_COMPONENT32F ? GL46C.GL_FLOAT
                        : target.format == GL46C.GL_DEPTH32F_STENCIL8 ? GL46C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV
                        : target.stencil ? GL46C.GL_UNSIGNED_INT_24_8 : GL46C.GL_UNSIGNED_INT;
                GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, target.format, target.width, target.height,
                        0, external, type, 0L);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, GL46C.GL_CLAMP_TO_EDGE);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_T, GL46C.GL_CLAMP_TO_EDGE);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_MODE, GL46C.GL_NONE);
                COVERAGE_TEXTURES[i] = GL46C.glGenTextures();
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, COVERAGE_TEXTURES[i]);
                GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_R8, target.width, target.height,
                        0, GL46C.GL_RED, GL46C.GL_UNSIGNED_BYTE, 0L);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, GL46C.GL_CLAMP_TO_EDGE);
                GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_T, GL46C.GL_CLAMP_TO_EDGE);
                GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER,
                        target.stencil ? GL46C.GL_DEPTH_STENCIL_ATTACHMENT : GL46C.GL_DEPTH_ATTACHMENT,
                        GL46C.GL_TEXTURE_2D, DEPTH_TEXTURES[i], 0);
                GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0,
                        GL46C.GL_TEXTURE_2D, COVERAGE_TEXTURES[i], 0);
                GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
                if (GL46C.glCheckFramebufferStatus(GL46C.GL_DRAW_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("深度分层 FBO 不完整");
            }
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, framebuffer);
            GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
            poolWidth = target.width; poolHeight = target.height; poolFormat = target.format; poolCount = layers;
            return true;
        } catch (RuntimeException e) {
            cleanup();
            throw e;
        } finally {
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, oldDraw);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, oldRead);
            GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, oldRenderbuffer);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, oldTexture);
            GL46C.glActiveTexture(active);
        }
    }

    private static int bytesPerPixel(int format) {
        return switch (format) {
            case GL46C.GL_DEPTH_COMPONENT16 -> 2;
            case GL46C.GL_DEPTH32F_STENCIL8 -> 8;
            default -> 4;
        };
    }

    private static final class GlState implements AutoCloseable {
        final int drawFbo = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        final int readFbo = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
        final int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        final int activeTexture = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        final int depthFunc = GL46C.glGetInteger(GL46C.GL_DEPTH_FUNC);
        final boolean depthMask = GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK);
        final boolean depthTest = GL46C.glIsEnabled(GL46C.GL_DEPTH_TEST);
        final int[] viewport = new int[4], scissorBox = new int[4];
        final boolean scissorEnabled = GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST);
        int depthUnit = -1, coverageUnit = -1;
        int textureBinding, samplerBinding, coverageTextureBinding, coverageSamplerBinding;
        final boolean blend0 = GL46C.glIsEnabledi(GL46C.GL_BLEND, 0);
        final boolean[] colorMask0 = new boolean[4];

        GlState() {
            GL46C.glGetIntegerv(GL46C.GL_VIEWPORT, viewport);
            GL46C.glGetIntegerv(GL46C.GL_SCISSOR_BOX, scissorBox);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer mask = stack.malloc(4);
                GL46C.glGetBooleani_v(GL46C.GL_COLOR_WRITEMASK, 0, mask);
                for (int i = 0; i < 4; i++) colorMask0[i] = mask.get(i) != 0;
            }
        }

        void captureTextureUnit(int unit, int coverage) {
            depthUnit = unit; coverageUnit = coverage;
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + depthUnit);
            textureBinding = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
            samplerBinding = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, depthUnit);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + coverageUnit);
            coverageTextureBinding = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
            coverageSamplerBinding = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, coverageUnit);
            GL46C.glActiveTexture(activeTexture);
        }

        void restoreCoverageColorState() {
            GL46C.glColorMaski(0, colorMask0[0], colorMask0[1], colorMask0[2], colorMask0[3]);
            enabledi(GL46C.GL_BLEND, 0, blend0);
        }

        @Override public void close() {
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, readFbo);
            GL46C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL46C.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            enabled(GL46C.GL_SCISSOR_TEST, scissorEnabled);
            GL46C.glDepthFunc(depthFunc);
            GL46C.glDepthMask(depthMask);
            enabled(GL46C.GL_DEPTH_TEST, depthTest);
            if (depthUnit >= 0) {
                GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + depthUnit);
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, textureBinding);
                GL46C.glBindSampler(depthUnit, samplerBinding);
                GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + coverageUnit);
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, coverageTextureBinding);
                GL46C.glBindSampler(coverageUnit, coverageSamplerBinding);
            }
            restoreCoverageColorState();
            GL46C.glActiveTexture(activeTexture);
            GL46C.glUseProgram(program);
        }
    }

    private static void enabledi(int capability, int index, boolean value) {
        if (value) GL46C.glEnablei(capability, index); else GL46C.glDisablei(capability, index);
    }
}
