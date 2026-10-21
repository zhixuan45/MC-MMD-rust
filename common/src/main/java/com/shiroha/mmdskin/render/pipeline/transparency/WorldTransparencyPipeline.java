package com.shiroha.mmdskin.render.pipeline.transparency;

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
    /** Disable the deferred model pass for diagnostics only. */
    private static final boolean QUEUE_ENABLED = Boolean.parseBoolean(
            System.getProperty("mmdskin.worldTransparencyQueue", "true"));
    private static final WorldTransparencyPipeline INSTANCE = new WorldTransparencyPipeline();
    private static final String FULLSCREEN = """
            #version 430 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private final Map<Integer, TransparencyProgram> programs = new HashMap<>();
    private final WorldTransparencyStageGate stageGate = new WorldTransparencyStageGate();
    private boolean frame, queueActive, worldScene, queued, fabulous;
    private int modelCaptures;
    private int meta, nodes, capacity, resolveProgram, vao, depthTexture, depthFbo;
    private int metaBinding, nodeBinding, sourceFbo, depthFormat;
    private int[] viewport;
    private boolean overflowWarned;

    private WorldTransparencyPipeline() {}

    /** The public stage must have fired in the preceding frame before we defer any draw. */
    public static void beginFrame(boolean fabulous, boolean supportedShaderMode) {
        INSTANCE.frame = true;
        INSTANCE.queueActive = INSTANCE.queued = INSTANCE.worldScene = false;
        INSTANCE.modelCaptures = 0;
        boolean stageAvailable = INSTANCE.stageGate.beginFrame();
        if (!supportedShaderMode || !stageAvailable || fabulous || !QUEUE_ENABLED || !GL.getCapabilities().OpenGL43)
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

    /** Compose MMD fragments after terrain water/glass, without redirecting any chunk draw. */
    public static void afterWorldTranslucency() {
        if (!INSTANCE.stageGate.afterTranslucent()) return;
        try {
            if (INSTANCE.queueActive && INSTANCE.queued) INSTANCE.resolve();
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

    public static boolean worldFallback() {
        // Immediate translucent blending without main-depth sealing. This must remain
        // active for world scenes even if the external translucent stage has passed:
        // a late block-entity/custom renderer must never re-enable depth sealing.
        return INSTANCE.worldScene && !INSTANCE.queueActive;
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

    private void resolve() {
        try (ResolveState state = new ResolveState(metaBinding, nodeBinding)) {
            // Models were captured before later opaque entities. Test against final opaque depth.
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, sourceFbo);
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, depthFbo);
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            if (!DepthAttachment.blit(viewport[0], viewport[1], viewport[0] + viewport[2], viewport[1] + viewport[3],
                    0, 0, viewport[2], viewport[3])) throw new IllegalStateException("World transparency depth copy failed");
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, state.drawFbo);
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
            if (fabulous && state.drawFbo != sourceFbo) {
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
        final int texture, sampler;
        final Capture capture;
        ResolveState(int first, int second) {
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
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, drawFbo);
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
        if (INSTANCE.resolveProgram != 0) GL46C.glDeleteProgram(INSTANCE.resolveProgram);
        if (INSTANCE.vao != 0) GL46C.glDeleteVertexArrays(INSTANCE.vao);
        INSTANCE.resolveProgram = INSTANCE.vao = 0;
    }
}
