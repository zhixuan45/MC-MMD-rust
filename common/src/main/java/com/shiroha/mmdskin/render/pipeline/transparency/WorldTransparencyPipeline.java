package com.shiroha.mmdskin.render.pipeline.transparency;

import com.shiroha.mmdskin.compat.iris.IrisToonCompat;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.render.gl.DepthAttachment;
import com.shiroha.mmdskin.render.scene.RenderScene;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Deferred MMD fragments resolve after terrain translucency via public loader render events.
 * No terrain draw calls or renderer-private internals are intercepted. Opaque model pixels
 * remain in the entity pass; transparent fragments never seal main depth.
 * Water/model intersections are approximate since terrain is not captured in the queue.
 */
public final class WorldTransparencyPipeline {
    private static final WorldTransparencyPipeline INSTANCE = new WorldTransparencyPipeline();

    /** JVM options override the live GUI configuration, when explicitly provided. */
    static boolean configuredOrOverride(String override, boolean configured) {
        return override == null ? configured : Boolean.parseBoolean(override);
    }

    private static boolean queueEnabled() {
        return configuredOrOverride(System.getProperty("mmdskin.worldTransparencyQueue"),
                ConfigManager.isToonWorldTransparencyQueueEnabled());
    }

    private static boolean debugStages() {
        return configuredOrOverride(System.getProperty("mmdskin.debugTransparencyStages"),
                ConfigManager.isDebugTransparencyStagesEnabled());
    }
    private static final String FULLSCREEN = """
            #version 430 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private final Map<Integer, TransparencyProgram> programs = new HashMap<>();
    private final WorldTransparencyStageGate stageGate = new WorldTransparencyStageGate();
    private boolean frame, queueActive, worldScene, queued, fabulous, shaderPack;
    private boolean warnedTargetMismatch;
    private int debugFrame;
    private boolean debugModelLogged;
    private FramebufferColorTarget previousTranslucentColor, previousOpaqueColor, sourceColor;
    private boolean opaqueDepthCaptured, fallbackDepthCaptured;
    private int sourceDepthObjectType, sourceDepthObjectId;
    private int[] sourceDrawBuffers;
    private int modelCaptures;
    private int meta, nodes, capacity, resolveProgram, vao, depthTexture, depthFbo;
    private int metaBinding, nodeBinding, sourceFbo, depthFormat;
    private int[] viewport;
    private boolean overflowWarned;

    private WorldTransparencyPipeline() {}

    /** The public stage must have fired in the preceding frame before we defer any draw. */
    public static void beginFrame(boolean fabulous, boolean supportedShaderMode, boolean shaderPack) {
        INSTANCE.frame = true;
        INSTANCE.debugFrame++;
        INSTANCE.debugModelLogged = false;
        INSTANCE.shaderPack = shaderPack;
        INSTANCE.sourceColor = null;
        INSTANCE.sourceDrawBuffers = null;
        INSTANCE.opaqueDepthCaptured = INSTANCE.fallbackDepthCaptured = false;
        INSTANCE.queueActive = INSTANCE.queued = INSTANCE.worldScene = false;
        INSTANCE.modelCaptures = 0;
        boolean stageAvailable = INSTANCE.stageGate.beginFrame();
        if (!supportedShaderMode || !stageAvailable || fabulous || !queueEnabled() || !GL.getCapabilities().OpenGL43)
            return;
        try {
            INSTANCE.metaBinding = GL46C.glGetInteger(GL46C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) - 2;
            INSTANCE.nodeBinding = INSTANCE.metaBinding + 1;
            INSTANCE.initResolve();
            INSTANCE.fabulous = fabulous;
            INSTANCE.queueActive = true;
        } catch (RuntimeException e) {
            LogManager.getLogger().warn("World transparency unavailable: {}", e.getMessage());
        }
    }

    /**
     * Record the opaque depth AFTER block entities (chests, signs) but BEFORE
     * translucent water. This is the depthtex1-equivalent point under BSL:
     * copying at AFTER_PARTICLES mistakenly treats water as solid geometry.
     *
     * Public loader stages only; no renderer-private draw call interception.
     */
    public static void afterOpaqueWorld() {
        if (!INSTANCE.frame) return;
        FramebufferColorTarget opaqueColor = INSTANCE.shaderPack
                ? FramebufferColorTarget.capture() : null;
        INSTANCE.previousOpaqueColor = opaqueColor;
        if (debugStages() && INSTANCE.debugFrame % 120 == 1)
            LogManager.getLogger().info("[MMD transparency] after-block-entities color={},"
                    + " sourceColor={}, queued={}, queueActive={}", opaqueColor,
                    INSTANCE.sourceColor, INSTANCE.queued, INSTANCE.queueActive);
        if (!INSTANCE.shaderPack || !INSTANCE.queueActive || !INSTANCE.queued
                || !FramebufferColorTarget.compatible(INSTANCE.sourceColor, opaqueColor))
            return;
        DepthAttachment attachment = DepthAttachment.inspect();
        if (attachment == null || attachment.samples() != 0
                || attachment.format() != INSTANCE.depthFormat
                || attachment.objectType() != INSTANCE.sourceDepthObjectType
                || attachment.objectId() != INSTANCE.sourceDepthObjectId
                || !attachment.contains(INSTANCE.viewport)) return;
        // Replace the preliminary entity-time snapshot with the deeper opaque
        // scene including rendered block entities (but still excluding water).
        INSTANCE.opaqueDepthCaptured = INSTANCE.copyDepthSnapshot(
                GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING));
        if (debugStages() && INSTANCE.debugFrame % 120 == 1)
            LogManager.getLogger().info("[MMD transparency] opaque-depth captured={}, depthTexture={}",
                    INSTANCE.opaqueDepthCaptured, INSTANCE.depthTexture);
    }

    /**
     * Blit opaque depth into the private texture without altering any GL read/draw
     * attachments or the scissor mode. Reused both for the fallback entity-time
     * depth and the preferred post-block-entity depth.
     */
    private boolean copyDepthSnapshot(int readFbo) {
        int oldRead = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
        int oldDraw = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean oldScissor = GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST);
        try {
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, readFbo);
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, depthFbo);
            int[] v = viewport;
            return DepthAttachment.blit(v[0], v[1], v[0] + v[2], v[1] + v[3],
                    0, 0, v[2], v[3]);
        } finally {
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, oldRead);
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, oldDraw);
            if (oldScissor) GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
            else GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        }
    }

    /** Compose MMD fragments after terrain water/glass, without redirecting any chunk draw. */
    public static void afterWorldTranslucency() {
        if (!INSTANCE.stageGate.afterTranslucent()) return;
        // Remember the actual post-particle backing attachment for the next frame.
        // Iris may wrap the same texture in a different FBO for each render stage.
        FramebufferColorTarget stageColor = INSTANCE.shaderPack
                ? FramebufferColorTarget.capture() : null;
        INSTANCE.previousTranslucentColor = stageColor;
        if (debugStages() && INSTANCE.debugFrame % 120 == 1)
            LogManager.getLogger().info("[MMD transparency] after-particles color={},"
                    + " sourceColor={}, opaqueDepthCaptured={}, fallbackDepthCaptured={}",
                    stageColor, INSTANCE.sourceColor, INSTANCE.opaqueDepthCaptured,
                    INSTANCE.fallbackDepthCaptured);
        try {
            if (INSTANCE.queueActive && INSTANCE.queued) {
                boolean compatible = !INSTANCE.shaderPack
                        || (INSTANCE.opaqueDepthCaptured
                            && FramebufferColorTarget.compatible(INSTANCE.sourceColor, stageColor));
                if (!compatible && !INSTANCE.warnedTargetMismatch) {
                    INSTANCE.warnedTargetMismatch = true;
                    LogManager.getLogger().warn("Iris post-particle color attachment changed during the frame; "
                            + "using the original target for queued MMD translucency and rechecking next frame.");
                }
                INSTANCE.resolve(compatible);
            }
        } finally {
            INSTANCE.frame = INSTANCE.queueActive = false;
        }
    }

    public static void endFrame() {
        boolean missedStage = INSTANCE.stageGate.endFrame();
        if (missedStage && INSTANCE.queueActive && INSTANCE.queued)
            LogManager.getLogger().warn("Deferred {} MMD translucent draws but no external translucent stage fired; "
                    + "disabling the queue next frame", INSTANCE.modelCaptures);
        INSTANCE.frame = INSTANCE.queueActive = INSTANCE.queued = INSTANCE.worldScene = false;
    }

    public static SceneScope modelScene(RenderScene scene) {
        boolean old = INSTANCE.worldScene;
        INSTANCE.worldScene = scene != null && scene.isWorldScene();
        return new SceneScope(old);
    }

    public record SceneScope(boolean previous) implements AutoCloseable {
        @Override public void close() { INSTANCE.worldScene = previous; }
    }

    /** Success means the caller may omit model-local peeling, triangle sorting and depth sealing. */
    public static boolean prepareModel() {
        if (!INSTANCE.frame || !INSTANCE.queueActive || !INSTANCE.worldScene) return false;
        // Shader packs may route entities and translucent terrain to unrelated MRTs.
        // Only use the queue with an explicitly verified single primary-color target.
        if (INSTANCE.shaderPack) {
            FramebufferColorTarget currentColor = FramebufferColorTarget.capture();
            boolean verifiedProfile = IrisToonCompat.hasPrimaryColorOutput();
            boolean lateMatch = FramebufferColorTarget.compatible(
                    currentColor, INSTANCE.previousTranslucentColor);
            boolean opaqueMatch = FramebufferColorTarget.compatible(
                    currentColor, INSTANCE.previousOpaqueColor);
            if (debugStages() && !INSTANCE.debugModelLogged && INSTANCE.debugFrame % 120 == 1) {
                INSTANCE.debugModelLogged = true;
                LogManager.getLogger().info("[MMD transparency] model color={},"
                        + " verifiedProfile={}, previousOpaque={}, previousLate={},"
                        + " opaqueMatch={}, lateMatch={}",
                        currentColor, verifiedProfile, INSTANCE.previousOpaqueColor,
                        INSTANCE.previousTranslucentColor, opaqueMatch, lateMatch);
            }
            if (!verifiedProfile || !lateMatch || !opaqueMatch) return false;
        }
        try {
            INSTANCE.program(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM));
            return INSTANCE.prepareStorage();
        } catch (RuntimeException e) {
            LogManager.getLogger().warn("Keeping immediate MMD transparency: {}", e.getMessage());
            return false;
        }
    }

    public static boolean classicDepthPrepass() {
        // Never write translucent MMD depth into the main world buffer during entity
        // submission. Block entities (chests, signs), water, and shader-pack targets
        // can be submitted later, so an early depth-only pass would occlude them.
        return false;
    }

    public static boolean worldFallback(boolean modelJoinedQueue) {
        // A globally active queue does not prove this model passed Iris compatibility
        // checks. A failed admission must never re-enable translucent depth sealing.
        return shouldUseImmediateFallback(INSTANCE.worldScene, INSTANCE.queueActive, modelJoinedQueue);
    }

    static boolean shouldUseImmediateFallback(boolean worldScene, boolean queueActive,
                                               boolean modelJoinedQueue) {
        return worldScene && (!queueActive || !modelJoinedQueue);
    }

    public static Capture capture() {
        return INSTANCE.captureDraw();
    }

    public static boolean hasWorldQueue() {
        return INSTANCE.frame && INSTANCE.worldScene && INSTANCE.queued;
    }

    private TransparencyProgram program(int source) {
        TransparencyProgram cached = programs.get(source);
        if (cached != null && !cached.matches()) {
            cached.close(); programs.remove(source); cached = null;
        }
        if (cached == null) {
            // Preserve other programs' SSBO contracts instead of stealing their binding points.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer binding = stack.mallocInt(1);
                for (int i = 0; i < GL46C.glGetProgramInterfacei(source, GL46C.GL_SHADER_STORAGE_BLOCK,
                        GL46C.GL_ACTIVE_RESOURCES); i++) {
                    GL46C.glGetProgramResourceiv(source, GL46C.GL_SHADER_STORAGE_BLOCK, i,
                            stack.ints(GL46C.GL_BUFFER_BINDING), null, binding);
                    if (binding.get(0) == metaBinding || binding.get(0) == nodeBinding)
                        throw new IllegalArgumentException("Occupied transparency SSBO binding");
                }
            }
            cached = new TransparencyProgram(source, bindings(resource("world_transparency_capture.glsl")),
                    "mmdStore(MMD_COLOR); discard;");
            programs.put(source, cached);
        }
        return cached;
    }

    private boolean prepareStorage() {
        int[] current = new int[4]; GL46C.glGetIntegerv(GL46C.GL_VIEWPORT, current);
        int fbo = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (queued) return sourceFbo == fbo && java.util.Arrays.equals(viewport, current);
        DepthAttachment depth = DepthAttachment.inspect();
        if (depth == null || depth.format() == 0 || depth.samples() != 0 || !depth.contains(current)) return false;
        long pixels = (long) current[2] * current[3];
        long maxBlock = GL46C.glGetInteger64(GL46C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        if (pixels * 28 + 16 > maxBlock) return false;
        int oldBuffer = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!java.util.Arrays.equals(viewport, current) || depthFormat != depth.format() || meta == 0) {
                releaseStorage();
                viewport = current;
                depthFormat = depth.format();
                long budget = Math.max(32, Math.min(512, Integer.getInteger("mmdskin.worldTransparencyMiB", 128))) * 1024L * 1024L;
                capacity = (int) (Math.min(maxBlock, Math.min(budget, pixels * 8 * 16)) / 16);
                meta = allocate(pixels * 28 + 16);
                nodes = allocate((long) capacity * 16);
                initDepth();
            }
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, meta);
            GL46C.glClearBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, GL46C.GL_R32UI,
                    GL46C.GL_RED_INTEGER, GL46C.GL_UNSIGNED_INT, stack.ints(0));
            GL46C.glMemoryBarrier(GL46C.GL_BUFFER_UPDATE_BARRIER_BIT | GL46C.GL_SHADER_STORAGE_BARRIER_BIT);
            sourceFbo = fbo;
            if (shaderPack) {
                sourceColor = FramebufferColorTarget.capture();
                sourceDepthObjectType = depth.objectType();
                sourceDepthObjectId = depth.objectId();
                sourceDrawBuffers = currentDrawBuffers();
                // Retain entity-time depth before water, even if Iris changes
                // framebuffer targets between the public world stages.
                fallbackDepthCaptured = copyDepthSnapshot(fbo);
                if (!fallbackDepthCaptured) return false;
            }
            return true;
        } finally {
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, oldBuffer);
        }
    }

    private static int allocate(long bytes) {
        int buffer = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, buffer);
        GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER, bytes, GL46C.GL_DYNAMIC_DRAW);
        if (GL46C.glGetBufferParameteri64(GL46C.GL_SHADER_STORAGE_BUFFER, GL46C.GL_BUFFER_SIZE) != bytes) {
            GL46C.glDeleteBuffers(buffer);
            throw new IllegalStateException("World transparency buffer allocation failed");
        }
        return buffer;
    }

    private void initDepth() {
        int oldTexture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        int oldDraw = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            depthTexture = GL46C.glGenTextures();
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, depthTexture);
            GL46C.glTexStorage2D(GL46C.GL_TEXTURE_2D, 1, depthFormat, viewport[2], viewport[3]);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_MODE, GL46C.GL_NONE);
            depthFbo = GL46C.glGenFramebuffers();
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, depthFbo);
            GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                    GL46C.GL_TEXTURE_2D, depthTexture, 0);
            GL46C.glDrawBuffer(GL46C.GL_NONE);
            if (GL46C.glCheckFramebufferStatus(GL46C.GL_DRAW_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete world transparency depth target");
        } finally {
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, oldTexture);
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, oldDraw);
        }
    }

    private Capture captureDraw() {
        int original = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        TransparencyProgram shader = program(original);
        Capture state = new Capture(original, metaBinding, nodeBinding);
        try {
            GL46C.glMemoryBarrier(GL46C.GL_SHADER_STORAGE_BARRIER_BIT);
            bindBuffers();
            shader.bind();
            GL46C.glUniform4i(GL46C.glGetUniformLocation(shader.program, "MmdViewport"),
                    viewport[0], viewport[1], viewport[2], viewport[3]);
            GL46C.glUniform1ui(GL46C.glGetUniformLocation(shader.program, "MmdCapacity"), capacity);
            GL46C.glDepthMask(false);
            if (GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK))
                throw new IllegalStateException("depthMask(false) was not applied");
            queued = true;
            modelCaptures++;
            return state;
        } catch (RuntimeException e) { state.close(); throw e; }
    }

    private void bindBuffers() {
        GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, metaBinding, meta);
        GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, nodeBinding, nodes);
    }

    /** Restores indexed ranges as well as generic binding; uniforms are copied only at draw time. */
    public static final class Capture implements AutoCloseable {
        private final int program, generic;
        private final boolean depthMask;
        private final Binding first, second;
        private Capture(int program, int firstSlot, int secondSlot) {
            this.program = program;
            generic = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
            depthMask = GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK);
            first = new Binding(firstSlot); second = new Binding(secondSlot);
        }
        @Override public void close() {
            GL46C.glMemoryBarrier(GL46C.GL_SHADER_STORAGE_BARRIER_BIT);
            GL46C.glUseProgram(program);
            GL46C.glDepthMask(depthMask);
            first.restore(); second.restore();
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, generic);
        }
    }

    private record Binding(int slot, int buffer, long start, long size) {
        Binding(int slot) {
            this(slot, GL46C.glGetIntegeri(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, slot),
                    GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_START, slot),
                    GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_SIZE, slot));
        }
        void restore() {
            if (buffer != 0 && size > 0) GL46C.glBindBufferRange(GL46C.GL_SHADER_STORAGE_BUFFER, slot, buffer, start, size);
            else GL46C.glBindBufferBase(GL46C.GL_SHADER_STORAGE_BUFFER, slot, buffer);
        }
    }

    private void initResolve() {
        if (resolveProgram != 0) return;
        int vs = TransparencyProgram.compile(GL46C.GL_VERTEX_SHADER, FULLSCREEN);
        int fs = 0, program = 0;
        try {
            fs = TransparencyProgram.compile(GL46C.GL_FRAGMENT_SHADER, bindings(resource("world_transparency_resolve.frag.glsl")));
            program = GL46C.glCreateProgram();
            GL46C.glAttachShader(program, vs); GL46C.glAttachShader(program, fs); GL46C.glLinkProgram(program);
            if (GL46C.glGetProgrami(program, GL46C.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL46C.glGetProgramInfoLog(program));
            resolveProgram = program;
            vao = GL46C.glGenVertexArrays();
        } catch (RuntimeException e) {
            if (program != 0) GL46C.glDeleteProgram(program);
            throw e;
        } finally {
            GL46C.glDeleteShader(vs);
            if (fs != 0) GL46C.glDeleteShader(fs);
        }
    }

    private void resolve(boolean matchedStage) {
        try (ResolveState state = new ResolveState(metaBinding, nodeBinding,
                sourceFbo, sourceDrawBuffers)) {
            // A shader pack's current depthtex0 can include water, while depthtex1
            // still represents opaque block entities. The earlier snapshot must
            // survive unchanged until the post-particle composite.
            int destination = shaderPack && !matchedStage ? sourceFbo : state.drawFbo;
            if (!shaderPack) {
                if (!copyDepthSnapshot(sourceFbo))
                    throw new IllegalStateException("World transparency depth copy failed");
            } else if (!fallbackDepthCaptured) {
                // Later depthtex0 may already contain water. Never resample it
                // as an opaque cutoff after a shader-pack target change.
                throw new IllegalStateException("Missing shader-pack pre-water depth snapshot");
            }
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, destination);
            // Iris's other colortex material channels must remain untouched.
            if (shaderPack) GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
            bindBuffers();
            GL46C.glMemoryBarrier(GL46C.GL_SHADER_STORAGE_BARRIER_BIT | GL46C.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, meta);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer overflow = stack.mallocInt(1);
                GL46C.glGetBufferSubData(GL46C.GL_SHADER_STORAGE_BUFFER, 4, overflow);
                if (overflow.get(0) != 0 && !overflowWarned) {
                    overflowWarned = true;
                    LogManager.getLogger().warn("World transparency pool exhausted; overflow uses a weighted tail. "
                            + "Increase -Dmmdskin.worldTransparencyMiB (32..512) if needed.");
                }
            }
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, depthTexture);
            GL46C.glBindSampler(0, 0);
            GL46C.glUseProgram(resolveProgram);
            GL46C.glUniform1i(GL46C.glGetUniformLocation(resolveProgram, "MmdOpaqueDepth"), 0);
            GL46C.glUniform4i(GL46C.glGetUniformLocation(resolveProgram, "MmdViewport"),
                    viewport[0], viewport[1], viewport[2], viewport[3]);
            GL46C.glBindVertexArray(vao);
            GL46C.glEnable(GL46C.GL_BLEND);
            GL46C.glBlendEquationSeparate(GL46C.GL_FUNC_ADD, GL46C.GL_FUNC_ADD);
            GL46C.glBlendFuncSeparate(GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA, GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
            GL46C.glDisable(GL46C.GL_CULL_FACE);
            GL46C.glColorMask(true, true, true, true);
            // Fabulous needs a nearest depth for its later layer merge, but main opaque depth stays intact.
            if (fabulous && destination != sourceFbo) {
                GL46C.glEnable(GL46C.GL_DEPTH_TEST); GL46C.glDepthFunc(GL46C.GL_ALWAYS); GL46C.glDepthMask(true);
            } else {
                GL46C.glDisable(GL46C.GL_DEPTH_TEST); GL46C.glDepthMask(false);
            }
            GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        } finally { queued = false; }
    }

    private static final class ResolveState implements AutoCloseable {
        final int drawFbo = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        final int readFbo = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
        final int vertexArray = GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING);
        final int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        final int depthFunc = GL46C.glGetInteger(GL46C.GL_DEPTH_FUNC);
        final int rgbEquation = GL46C.glGetInteger(GL46C.GL_BLEND_EQUATION_RGB);
        final int alphaEquation = GL46C.glGetInteger(GL46C.GL_BLEND_EQUATION_ALPHA);
        final int srcRgb = GL46C.glGetInteger(GL46C.GL_BLEND_SRC_RGB), dstRgb = GL46C.glGetInteger(GL46C.GL_BLEND_DST_RGB);
        final int srcAlpha = GL46C.glGetInteger(GL46C.GL_BLEND_SRC_ALPHA), dstAlpha = GL46C.glGetInteger(GL46C.GL_BLEND_DST_ALPHA);
        final boolean blend = GL46C.glIsEnabled(GL46C.GL_BLEND), depth = GL46C.glIsEnabled(GL46C.GL_DEPTH_TEST);
        final boolean cull = GL46C.glIsEnabled(GL46C.GL_CULL_FACE), scissor = GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST);
        final boolean[] color = new boolean[4];
        final int sourceFbo;
        final int[] sourceDrawBuffers, originalDrawBuffers;
        final int texture, sampler;
        final Capture capture;
        ResolveState(int first, int second, int sourceFbo, int[] sourceDrawBuffers) {
            this.sourceFbo = sourceFbo;
            this.sourceDrawBuffers = sourceDrawBuffers;
            originalDrawBuffers = currentDrawBuffers();
            capture = new Capture(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM), first, second);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var mask = stack.malloc(4); GL46C.glGetBooleanv(GL46C.GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < 4; i++) color[i] = mask.get(i) != 0;
            }
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
            texture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
            sampler = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, 0);
            GL46C.glActiveTexture(active);
        }
        @Override public void close() {
            // glDrawBuffer mutates FBO state; do not pollute Iris's MRT mapping
            // or the original entity FBO if the post-particle stage uses another FBO.
            if (sourceDrawBuffers != null && sourceFbo != drawFbo) {
                GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, sourceFbo);
                GL46C.glDrawBuffers(sourceDrawBuffers);
            }
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL46C.glDrawBuffers(originalDrawBuffers);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, readFbo);
            GL46C.glBindVertexArray(vertexArray);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
            GL46C.glBindSampler(0, sampler); GL46C.glActiveTexture(active);
            GL46C.glDepthFunc(depthFunc);
            GL46C.glBlendEquationSeparate(rgbEquation, alphaEquation);
            GL46C.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL46C.glColorMask(color[0], color[1], color[2], color[3]);
            enabled(GL46C.GL_BLEND, blend); enabled(GL46C.GL_DEPTH_TEST, depth);
            enabled(GL46C.GL_CULL_FACE, cull); enabled(GL46C.GL_SCISSOR_TEST, scissor);
            capture.close();
        }
        private static void enabled(int cap, boolean value) { if (value) GL46C.glEnable(cap); else GL46C.glDisable(cap); }
    }

    private static int[] currentDrawBuffers() {
        int count = GL46C.glGetInteger(GL46C.GL_MAX_DRAW_BUFFERS);
        int[] slots = new int[count];
        for (int i = 0; i < count; i++)
            slots[i] = GL46C.glGetInteger(GL46C.GL_DRAW_BUFFER0 + i);
        return slots;
    }

    private String bindings(String text) {
        return text.replace("MMD_META_BINDING", Integer.toString(metaBinding)).replace("MMD_NODE_BINDING", Integer.toString(nodeBinding));
    }

    private static String resource(String name) {
        try (var stream = WorldTransparencyPipeline.class.getResourceAsStream("/assets/mmdskin/shader/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing shader " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private void releaseStorage() {
        if (meta != 0) GL46C.glDeleteBuffers(meta);
        if (nodes != 0) GL46C.glDeleteBuffers(nodes);
        if (depthTexture != 0) GL46C.glDeleteTextures(depthTexture);
        if (depthFbo != 0) GL46C.glDeleteFramebuffers(depthFbo);
        meta = nodes = depthTexture = depthFbo = 0;
    }

    public static void release() {
        endFrame();
        INSTANCE.programs.values().forEach(TransparencyProgram::close); INSTANCE.programs.clear();
        INSTANCE.releaseStorage(); INSTANCE.viewport = null;
        INSTANCE.previousTranslucentColor = INSTANCE.previousOpaqueColor = INSTANCE.sourceColor = null;
        INSTANCE.opaqueDepthCaptured = false;
        INSTANCE.sourceDrawBuffers = null;
        if (INSTANCE.resolveProgram != 0) GL46C.glDeleteProgram(INSTANCE.resolveProgram);
        if (INSTANCE.vao != 0) GL46C.glDeleteVertexArrays(INSTANCE.vao);
        INSTANCE.resolveProgram = INSTANCE.vao = 0;
    }
}
