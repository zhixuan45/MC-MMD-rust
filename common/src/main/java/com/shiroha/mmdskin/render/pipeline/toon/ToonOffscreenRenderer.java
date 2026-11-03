package com.shiroha.mmdskin.render.pipeline.toon;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.compat.iris.IrisCompat;
import com.shiroha.mmdskin.compat.iris.ToonIrisHooks;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.render.gl.DepthAttachment;
import com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyPipeline;
import com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyProfiler;
import com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyProfiler.Stage;
import com.shiroha.mmdskin.render.scene.RenderScene;
import com.shiroha.mmdskin.render.shader.ToonRenderStrategy;
import net.minecraft.client.renderer.ShaderInstance;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Bounded opaque prototype. Native entity draws remain the fallback and populate world depth.
 * Replays existing skinned buffers after Iris final, using a verified complete world-depth copy.
 * No custom shader writes an Iris MRT; one private color target and one composite per world frame.
 */
public final class ToonOffscreenRenderer {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final int MAX_MODELS = 64;
    private static final List<Draw> DRAWS = new ArrayList<>();
    private static final Set<String> REPORTED = new HashSet<>();
    private static final Set<String> BACKGROUND_PHASES = Set.of("SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID");
    private static final java.util.Map<ShaderInstance, NativeShader> NATIVE_SHADERS = new java.util.WeakHashMap<>();
    private static final ToonSceneCompositor COMPOSITOR = new ToonSceneCompositor();
    private static Object pipeline;
    private static Object observedPipeline;
    private static Presentation beforePresentation;
    private static boolean frame, drawing, depthReady, lifecycleReady;
    private static String rejection;
    private static NativeScope nativeScope;
    private static DepthAttachment sceneDepth;
    private static int fbo, color, depth, width, height, depthFormat;
    private static int presentationFbo, presentationColor;
    private static int frameNumber;
    private static java.lang.reflect.Method phaseMethod;
    private record Draw(BooleanSupplier valid, Runnable render) {}
    private record NativeShader(int projection, int view, int chunk, boolean supported) {}
    private static final NativeShader UNSUPPORTED_SHADER = new NativeShader(-1, -1, -1, false);
    private record Presentation(int fbo, int color, int width, int height, DepthAttachment depth) {}

    private ToonOffscreenRenderer() {}

    public static boolean isDrawing() { return drawing; }

    /** Only set after whole-model alpha eligibility; avoids needless opaque triangle readbacks. */
    public static boolean isOpaqueSubmission() { return drawing || nativeScope != null; }

    public static boolean canReplayShader(ShaderInstance shader) {
        if (shader != null && NATIVE_SHADERS.computeIfAbsent(shader, ToonOffscreenRenderer::inspectNativeShader).supported()) return true;
        reject("NATIVE_VERTEX_TRANSFORM_UNVERIFIED; jitter/deformation requires integrated rendering");
        return false;
    }

    /** Called by the existing world-frame entry, also when Iris is disabled. */
    public static void worldFrame(boolean shaderPack) {
        if (frame && !DRAWS.isEmpty()) report("MISSED_IRIS_FINAL; native model retained");
        if (frame || !shaderPack || !ConfigManager.isToonRenderingEnabled()
                || ConfigManager.getToonRenderStrategy() == ToonRenderStrategy.SHADER_INTEGRATED) observedPipeline = null;
        clearFrame();
        if ((!shaderPack || !ConfigManager.isToonRenderingEnabled()
                || ConfigManager.getToonRenderStrategy() == ToonRenderStrategy.SHADER_INTEGRATED) && fbo != 0) release();
    }

    public static void begin(Object owner) {
        if (!ConfigManager.isToonRenderingEnabled()
                || ConfigManager.getToonRenderStrategy() == ToonRenderStrategy.SHADER_INTEGRATED) return;
        if (!owner.getClass().getName().equals("net.irisshaders.iris.pipeline.IrisRenderingPipeline")) {
            report("UNVERIFIED_IRIS_PIPELINE_SUBCLASS"); return;
        }
        if (!ToonIrisHooks.complete()) {
            // Force optional shader classes through their loader/mixin transformer once.
            try {
                for (String shader : List.of("ExtendedShader", "FallbackShader", "SodiumShader"))
                    Class.forName("net.irisshaders.iris.pipeline.programs." + shader, false,
                            ToonOffscreenRenderer.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                report("IRIS_OBSERVER_UNAVAILABLE: " + e.getClass().getSimpleName());
            }
        }
        if (!ToonIrisHooks.complete()) { report("IRIS_HOOK_CONTRACT_UNVERIFIED"); return; }
        beginVerifiedFrame(owner);
    }

    // Synthetic probes bypass hook loading, but still exercise the real runtime observation gate.
    static void beginVerifiedFrame(Object owner) {
        clearFrame();
        pipeline = owner;
        frame = true;
        lifecycleReady = observedPipeline == owner;
        observedPipeline = null;
        frameNumber++;
    }

    public static boolean attemptsIndependent(RenderScene scene, boolean integrated) {
        return scene != null && scene.isWorldScene() && !scene.isFirstPerson()
                && ConfigManager.isToonRenderingEnabled() && IrisCompat.isIrisShaderActive()
                && !IrisCompat.isRenderingShadows() && ConfigManager.getToonRenderStrategy().attemptsIndependent(integrated);
    }

    public static boolean available() {
        if (!frame) { report("IRIS_LIFECYCLE_UNAVAILABLE; native/integrated renderer retained"); return false; }
        if (!lifecycleReady) { report("WAITING_FOR_IRIS_FINAL_OBSERVATION; native/integrated renderer retained"); return false; }
        if (rejection != null) return false;
        if (!GL.getCapabilities().OpenGL40 || !GL.getCapabilities().OpenGL45 && !GL.getCapabilities().GL_ARB_direct_state_access) {
            reject("DEPTH_INSPECTION_REQUIRES_DSA"); return false;
        }
        return true;
    }

    public static void reject(String reason) {
        if (!frame) { report(reason); return; }
        if (rejection == null) rejection = reason;
        report(reason + "; native/integrated renderer retained");
    }

    /** Reject blended world submissions, including depth-writing water/glass and translucent entities. */
    public static void observeShader(Object shader) {
        if (!frame || drawing || IrisCompat.isRenderingShadows()) return;
        if (nativeScope != null && shader instanceof ShaderInstance instance) {
            nativeScope.observe(instance);
            return;
        }
        if (rejection != null) return;
        String phase;
        try {
            if (phaseMethod == null || !phaseMethod.getDeclaringClass().isInstance(pipeline))
                phaseMethod = pipeline.getClass().getMethod("getPhase");
            phase = String.valueOf(phaseMethod.invoke(pipeline));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            reject("IRIS_PHASE_UNAVAILABLE: " + e.getClass().getSimpleName()); return;
        }
        // Background layers do not intersect world geometry. Everything else is conservative.
        if (BACKGROUND_PHASES.contains(phase)) return;
        int buffers = GL46C.glGetInteger(GL46C.GL_MAX_DRAW_BUFFERS);
        for (int slot = 0; slot < buffers; slot++) {
            if (GL46C.glGetInteger(GL46C.GL_DRAW_BUFFER0 + slot) == GL46C.GL_NONE) continue;
            if (GL46C.glIsEnabledi(GL46C.GL_BLEND, slot)
                    && (GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_RGB, slot) != GL46C.GL_ONE
                    || GL46C.glGetIntegeri(GL46C.GL_BLEND_DST_RGB, slot) != GL46C.GL_ZERO
                    || GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_RGB, slot) != GL46C.GL_FUNC_ADD)) {
                reject("WORLD_TRANSPARENCY_REQUIRES_INTERLEAVING: phase=" + phase);
                return;
            }
        }
    }

    /** Suppress only this verified opaque native draw, never unrelated world transparency. */
    public static NativeScope nativeOpaqueDraw(java.nio.FloatBuffer expectedView) {
        if (nativeScope != null) throw new IllegalStateException("Nested independent native draw");
        return nativeScope = new NativeScope(expectedView);
    }

    public static final class NativeScope implements AutoCloseable {
        private DepthAttachment attachment;
        private boolean projectionVerified;
        private final float[] expectedView;

        private NativeScope(java.nio.FloatBuffer view) {
            expectedView = view == null ? null : new float[16];
            if (view != null) view.duplicate().position(0).get(expectedView);
        }

        private void observe(ShaderInstance shader) {
            var contract = NATIVE_SHADERS.computeIfAbsent(shader, ToonOffscreenRenderer::inspectNativeShader);
            if (!contract.supported() || expectedView == null) return;
            try (var stack = MemoryStack.stackPush()) {
                var actual = stack.mallocFloat(16);
                var expected = RenderSystem.getProjectionMatrix().get(stack.mallocFloat(16));
                GL46C.glGetUniformfv(shader.getId(), contract.projection(), actual);
                float[] values = new float[16];
                actual.get(values);
                for (int i = 0; i < 16; i++)
                    if (!Float.isFinite(values[i]) || Math.abs(values[i] - expected.get(i)) > 0.000001f) return;
                actual.clear();
                GL46C.glGetUniformfv(shader.getId(), contract.view(), actual);
                for (int i = 0; i < 16; i++)
                    if (!Float.isFinite(actual.get(i)) || Math.abs(actual.get(i) - expectedView[i]) > 0.000001f) return;
                // Iris's synthesized fallback uses Position + ChunkOffset; entity offset must be zero.
                if (contract.chunk() >= 0) {
                    actual.clear();
                    GL46C.glGetUniformfv(shader.getId(), contract.chunk(), actual);
                    if (actual.get(0) != 0 || actual.get(1) != 0 || actual.get(2) != 0) return;
                }
                captureTarget(values);
            }
        }

        void captureTarget(float[] projection) {
            int[] viewport = new int[4];
            GL46C.glGetIntegerv(GL46C.GL_VIEWPORT, viewport);
            var candidate = DepthAttachment.inspect();
            if (candidate == null || candidate.format() == 0 || candidate.samples() != 0
                    || !baseDepthImage(candidate)
                    || viewport[0] != 0 || viewport[1] != 0 || viewport[2] != candidate.width()
                    || viewport[3] != candidate.height() || !standardDepth(projection)
                    || GL46C.glIsEnabled(GL46C.GL_POLYGON_OFFSET_FILL)
                    || GL46C.glIsEnabled(GL46C.GL_STENCIL_TEST) || GL46C.glIsEnabled(GL46C.GL_COLOR_LOGIC_OP)) return;
            attachment = candidate;
            projectionVerified = true;
        }

        @Override public void close() { nativeScope = null; }
    }

    static boolean standardDepth(float[] projection) {
        if (projection.length != 16) return false;
        for (float value : projection) if (!Float.isFinite(value)) return false;
        // Perspective, non-reversed OpenGL window depth. Special/VR projections fail closed.
        if (Math.abs(projection[11] + 1) > 0.000001f || Math.abs(projection[15]) > 0.000001f
                || projection[10] >= 0 || projection[14] >= 0) return false;
        int function = GL46C.glGetInteger(GL46C.GL_DEPTH_FUNC);
        return (function == GL46C.GL_LESS || function == GL46C.GL_LEQUAL) && standardWindowDepth();
    }

    private static boolean standardWindowDepth() {
        try (var stack = MemoryStack.stackPush()) {
            var range = stack.mallocDouble(2);
            GL46C.glGetDoublev(GL46C.GL_DEPTH_RANGE, range);
            return range.get(0) == 0 && range.get(1) == 1
                    && (!(GL.getCapabilities().OpenGL45 || GL.getCapabilities().GL_ARB_clip_control)
                        || GL46C.glGetInteger(GL46C.GL_CLIP_DEPTH_MODE) == GL46C.GL_NEGATIVE_ONE_TO_ONE
                        && GL46C.glGetInteger(GL46C.GL_CLIP_ORIGIN) == GL46C.GL_LOWER_LEFT);
        }
    }

    private static NativeShader inspectNativeShader(ShaderInstance shader) {
        String name = shader.getClass().getName();
        boolean extended = name.equals("net.irisshaders.iris.pipeline.programs.ExtendedShader");
        if (!extended && !name.equals("net.irisshaders.iris.pipeline.programs.FallbackShader")) return UNSUPPORTED_SHADER;
        try {
            if (extended) {
                for (String method : List.of("getGeometry", "getTessControl", "getTessEval"))
                    if (shader.getClass().getMethod(method).invoke(shader) != null) return UNSUPPORTED_SHADER;
                if (!Boolean.FALSE.equals(shader.getClass().getMethod("hasActiveImages").invoke(shader))) return UNSUPPORTED_SHADER;
            }
            try (var stack = MemoryStack.stackPush()) {
                var attached = stack.mallocInt(GL46C.glGetProgrami(shader.getId(), GL46C.GL_ATTACHED_SHADERS));
                GL46C.glGetAttachedShaders(shader.getId(), null, attached);
                boolean vertex = false, fragment = false;
                for (int i = 0; i < attached.capacity(); i++) {
                    int type = GL46C.glGetShaderi(attached.get(i), GL46C.GL_SHADER_TYPE);
                    String source = GL46C.glGetShaderSource(attached.get(i));
                    if (type == GL46C.GL_FRAGMENT_SHADER)
                        fragment = !java.util.regex.Pattern.compile("\\bgl_FragDepth(?:EXT)?\\b").matcher(source).find();
                    if (type == GL46C.GL_VERTEX_SHADER) vertex = ToonVertexContract.canonical(source);
                }
                int projection = matrixUniform(shader.getId(), "ProjMat");
                int view = matrixUniform(shader.getId(), "ModelViewMat");
                int chunk = GL46C.glGetUniformLocation(shader.getId(), "ChunkOffset");
                if (chunk >= 0 && uniform(shader.getId(), "ChunkOffset", GL46C.GL_FLOAT_VEC3) < 0) return UNSUPPORTED_SHADER;
                return vertex && fragment && projection >= 0 && view >= 0
                        ? new NativeShader(projection, view, chunk, true) : UNSUPPORTED_SHADER;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            report("NATIVE_SHADER_GEOMETRY_CONTRACT_UNAVAILABLE: " + e.getClass().getSimpleName());
            return UNSUPPORTED_SHADER;
        }
    }

    private static int matrixUniform(int program, String base) {
        int iris = uniform(program, "iris_" + base, GL46C.GL_FLOAT_MAT4);
        return iris >= 0 ? iris : uniform(program, base, GL46C.GL_FLOAT_MAT4);
    }

    private static int uniform(int program, String name, int type) {
        int index = GL46C.glGetUniformIndices(program, name);
        if (index == GL46C.GL_INVALID_INDEX || GL46C.glGetActiveUniformsi(program, index, GL46C.GL_UNIFORM_TYPE) != type
                || GL46C.glGetActiveUniformsi(program, index, GL46C.GL_UNIFORM_SIZE) != 1) return -1;
        return GL46C.glGetUniformLocation(program, name);
    }

    public static boolean enqueue(NativeScope nativeDraw, BooleanSupplier valid, Runnable render) {
        if (!available()) return false;
        if (!nativeDraw.projectionVerified || nativeDraw.attachment == null) {
            reject("NATIVE_DEPTH_OR_PROJECTION_UNVERIFIED"); return false;
        }
        if (sceneDepth != null && !sceneDepth.equals(nativeDraw.attachment)) {
            reject("MIXED_SCENE_DEPTH"); return false;
        }
        if (DRAWS.size() >= MAX_MODELS) { reject("MODEL_QUEUE_BUDGET"); return false; }
        sceneDepth = nativeDraw.attachment;
        DRAWS.add(new Draw(valid, render));
        return true;
    }

    public static void beforeFinal(Object owner, int target, int colorId, int w, int h) {
        if (!frame || pipeline != owner) return;
        try { beforePresentation = inspectPresentation(target, colorId, w, h); }
        catch (RuntimeException e) { reject("PRE_FINAL_INSPECTION_FAILED: " + e.getMessage()); return; }
        if (beforePresentation == null) { reject("PRE_FINAL_PRESENTATION_UNVERIFIED"); return; }
        if (DRAWS.isEmpty() || rejection != null) return;
        try (var state = new ToonGlState(); var timing = WorldTransparencyProfiler.measure(Stage.TOON_DEPTH_CAPTURE, true)) {
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, target);
            var actualDepth = DepthAttachment.inspect();
            if (!ToonSceneCompositor.verifiedPresentationTarget(w, h, colorId)
                    || !sceneDepth.equals(actualDepth) || w != sceneDepth.width() || h != sceneDepth.height()) {
                reject("PRESENTATION_COLOR_OR_WORLD_DEPTH_CHANGED"); return;
            }
            allocate(w, h, sceneDepth.format());
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, target);
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, fbo);
            if (!DepthAttachment.blit(0, 0, w, h, 0, 0, w, h)) { reject("FINAL_DEPTH_COPY_FAILED"); return; }
            presentationFbo = target;
            presentationColor = colorId;
            depthReady = true;
            trace("before-final", target, colorId, w, h);
        } catch (RuntimeException e) {
            deleteStorage();
            reject("OFFSCREEN_INITIALIZATION_FAILED: " + e.getMessage());
        }
    }

    public static void afterFinal(Object owner, int target, int colorId, int w, int h) {
        if (!frame || pipeline != owner) return;
        try {
            var after = inspectPresentation(target, colorId, w, h);
            if (beforePresentation != null && beforePresentation.equals(after)) observedPipeline = owner;
            else { reject("FINAL_LIFECYCLE_TARGET_UNVERIFIED"); return; }
            if (!DRAWS.isEmpty()) trace("after-final", target, colorId, w, h);
            if (DRAWS.isEmpty() || rejection != null) return;
            if (!depthReady) { reject("MISSING_PRE_FINAL_DEPTH"); return; }
            try (var state = new ToonGlState()) {
                GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, target);
                if (target != presentationFbo || colorId != presentationColor || w != width || h != height
                        || !ToonSceneCompositor.verifiedPresentationTarget(w, h, colorId)
                        || !sceneDepth.equals(DepthAttachment.inspect())
                        || GL46C.glIsEnabled(GL46C.GL_STENCIL_TEST) || GL46C.glIsEnabled(GL46C.GL_COLOR_LOGIC_OP)
                        || GL46C.glIsEnabled(GL46C.GL_RASTERIZER_DISCARD) || !standardWindowDepth()
                        || GL46C.glIsEnabled(GL46C.GL_POLYGON_OFFSET_FILL)) {
                    reject("FINAL_PRESENTATION_CHANGED"); return;
                }
                // Validate every queued model BEFORE drawing any of them. Never publish a partial frame.
                if (DRAWS.stream().anyMatch(draw -> !draw.valid.getAsBoolean())) {
                    reject("MODEL_BUFFERS_CHANGED_BEFORE_FINAL"); return;
                }
                drawing = true;
                try (var scene = WorldTransparencyPipeline.modelScene(null)) {
                    GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, fbo);
                    GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
                    GL46C.glDisable(GL46C.GL_FRAMEBUFFER_SRGB);
                    GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
                    GL46C.glColorMaski(0, true, true, true, true);
                    GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, new float[]{0, 0, 0, 0});
                    GL46C.glViewport(0, 0, w, h);
                    // The native opaque underpaint already occupies equal world depth.
                    RenderSystem.depthFunc(GL46C.GL_LEQUAL);
                    GL46C.glDepthFunc(GL46C.GL_LEQUAL);
                    RenderSystem.enableDepthTest(); GL46C.glEnable(GL46C.GL_DEPTH_TEST);
                    RenderSystem.depthMask(true); GL46C.glDepthMask(true);
                    RenderSystem.enableCull(); GL46C.glEnable(GL46C.GL_CULL_FACE); GL46C.glCullFace(GL46C.GL_BACK);
                    RenderSystem.enableBlend(); GL46C.glEnable(GL46C.GL_BLEND);
                    RenderSystem.blendEquation(GL46C.GL_FUNC_ADD);
                    GL46C.glBlendEquationSeparate(GL46C.GL_FUNC_ADD, GL46C.GL_FUNC_ADD);
                    RenderSystem.blendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                            GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
                    GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                            GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
                    GL46C.glBindSampler(0, 0);
                    RenderSystem.activeTexture(GL46C.GL_TEXTURE0); GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
                    for (var draw : DRAWS) {
                        try (var timing = WorldTransparencyProfiler.measure(Stage.TOON_RENDER, true)) { draw.render.run(); }
                    }
                    int error = GL46C.glGetError();
                    if (error != GL46C.GL_NO_ERROR) throw new IllegalStateException("TOON_DRAW_GL_ERROR_" + error);
                    GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, target);
                    try (var timing = WorldTransparencyProfiler.measure(Stage.TOON_COMPOSITE, true)) {
                        COMPOSITOR.compose(color, w, h);
                    }
                    report("OPAQUE_INDEPENDENT_COMPOSITE_ACTIVE; native underpaint + one frame composite");
                } finally { drawing = false; }
            }
        } catch (RuntimeException e) {
            String reason = "OFFSCREEN_REPLAY_FAILED: " + e.getMessage();
            boolean first = !REPORTED.contains(reason + "; native/integrated renderer retained");
            reject(reason);
            if (first) LogManager.getLogger().warn("[MMD independent Toon] replay failed; native scene retained", e);
        } finally { clearFrame(); }
    }

    private static void allocate(int w, int h, int format) {
        if (fbo != 0 && w == width && h == height && format == depthFormat) return;
        long bytes = (long) w * h * (format == GL46C.GL_DEPTH32F_STENCIL8 ? 16 : 12);
        if (w <= 0 || h <= 0 || bytes > MAX_BYTES) throw new IllegalStateException("OFFSCREEN_MEMORY_BUDGET");
        deleteStorage();
        width = w; height = h; depthFormat = format;
        fbo = GL46C.glGenFramebuffers();
        color = texture(GL46C.GL_RGBA16F);
        depth = texture(format);
        GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, fbo);
        GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, color, 0);
        GL46C.glFramebufferTexture2D(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_TEXTURE_2D, depth, 0);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        if (GL46C.glCheckFramebufferStatus(GL46C.GL_DRAW_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("INCOMPLETE_TOON_FRAMEBUFFER");
    }

    private static int texture(int format) {
        int id = GL46C.glGenTextures();
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
        GL46C.glTexStorage2D(GL46C.GL_TEXTURE_2D, 1, format, width, height);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_MODE, GL46C.GL_NONE);
        return id;
    }

    private static void clearFrame() {
        DRAWS.clear(); pipeline = null; sceneDepth = null; rejection = null;
        frame = drawing = depthReady = lifecycleReady = false; nativeScope = null; beforePresentation = null;
    }

    private static Presentation inspectPresentation(int target, int colorId, int w, int h) {
        int original = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, target);
            if (!ToonSceneCompositor.verifiedPresentationTarget(w, h, colorId)) return null;
            var attachment = DepthAttachment.inspect();
            if (attachment == null || attachment.format() == 0 || attachment.samples() != 0
                    || !baseDepthImage(attachment)
                    || attachment.width() != w || attachment.height() != h) return null;
            return new Presentation(target, colorId, w, h, attachment);
        } finally { GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, original); }
    }

    private static boolean baseDepthImage(DepthAttachment attachment) {
        if (attachment.objectType() != GL46C.GL_TEXTURE) return true;
        return GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                    GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL) == 0
                && GL46C.glGetFramebufferAttachmentParameteri(GL46C.GL_DRAW_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                    GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER) == 0;
    }

    private static void report(String reason) {
        if (REPORTED.add(reason)) LogManager.getLogger().info("[MMD independent Toon] {}", reason);
    }

    private static void trace(String event, int target, int colorId, int w, int h) {
        if (ConfigManager.isDebugTransparencyStagesEnabled() && frameNumber % 120 == 1)
            LogManager.getLogger().info("[MMD independent Toon stage] frame={}, event={}, pipeline={},"
                    + " boundDraw={}, target={}, color={}, size={}x{}, nativeDepth={}, depthReady={}, models={}, rejection={}",
                    frameNumber, event, pipeline.getClass().getName(),
                    GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING), target, colorId, w, h,
                    sceneDepth, depthReady, DRAWS.size(), rejection);
    }

    private static void deleteStorage() {
        if (fbo != 0) GL46C.glDeleteFramebuffers(fbo);
        if (color != 0) GL46C.glDeleteTextures(color);
        if (depth != 0) GL46C.glDeleteTextures(depth);
        fbo = color = depth = width = height = depthFormat = 0;
    }

    public static void release() {
        clearFrame(); deleteStorage(); COMPOSITOR.release(); REPORTED.clear(); NATIVE_SHADERS.clear(); phaseMethod = null;
        observedPipeline = null;
    }
}
