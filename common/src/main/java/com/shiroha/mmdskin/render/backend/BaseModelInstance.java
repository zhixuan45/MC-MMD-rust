package com.shiroha.mmdskin.render.backend;

import com.shiroha.mmdskin.bridge.runtime.NativeRenderBackendPort;
import com.shiroha.mmdskin.compat.iris.IrisCompat;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.debug.client.ModelIssueDiagnostics;
import com.shiroha.mmdskin.config.RuntimeConfigPortHolder;
import com.shiroha.mmdskin.model.runtime.ModelInstance;
import com.shiroha.mmdskin.render.scene.RenderScene;
import com.shiroha.mmdskin.render.pipeline.LivingEntityModelStateHelper;
import com.shiroha.mmdskin.render.pipeline.RenderPerformanceProfiler;
import com.shiroha.mmdskin.render.policy.WorldRenderPolicy;
import com.shiroha.mmdskin.render.shader.EyeEmissionBuffer;
import com.shiroha.mmdskin.render.shader.ToonShaderBase;
import com.shiroha.mmdskin.stage.client.camera.MMDCameraController;
import com.shiroha.mmdskin.texture.runtime.TextureRepository;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MMD 模型抽象基类。
 */
public abstract class BaseModelInstance implements ModelInstance {
    protected static final Logger logger = LogManager.getLogger();

    protected static final float MAX_DELTA_TIME = 0.25f;
    protected static final float MODEL_SCALE = 0.09f;
    protected NativeRenderBackendPort nativeRenderBackendPort;
    protected long model;
    protected String modelDir;
    private EyeEmissionBuffer eyeEmissionBuffer;
    private boolean eyeEmissionInitialized;
    private String cachedModelName;

    protected long lastUpdateTime = -1;
    private final ModelIssueDiagnostics issueDiagnostics = new ModelIssueDiagnostics();

    protected final Quaternionf tempQuat = new Quaternionf();
    private final Matrix4f composedModelViewMatrix = new Matrix4f();

    protected ByteBuffer materialMorphResultsByteBuffer;
    protected int materialMorphResultCount = 0;

    protected List<String> textureKeys;

    private volatile boolean vrActive;
    protected final AtomicLong nativeUpdateRevision = new AtomicLong(0L);
    private boolean physicsStateInitialized = false;
    private boolean physicsEnabled = true;
    private volatile long failedEvaluationFrame = -1;
    private final FirstPersonPoseState firstPersonPoseState = new FirstPersonPoseState();
    private final FrameEvaluationCache evaluationCache = new FrameEvaluationCache();
    private RenderPerformanceProfiler.Scene evaluationScene = RenderPerformanceProfiler.Scene.OTHER;
    private final PaperDollPoseState<LivingEntity> paperDollPose = new PaperDollPoseState<>();
    private ByteBuffer nativeProfile;
    /** 全局 Alpha 调制因子（例如直播防走光虚化透明度，默认 1.0） */
    protected float globalAlpha = 1.0f;

    public void setGlobalAlpha(float alpha) {
        this.globalAlpha = Math.max(0.0f, Math.min(1.0f, alpha));
    }

    public float getGlobalAlpha() {
        return globalAlpha;
    }

    public void setVrActive(boolean active) { this.vrActive = active; }

    public boolean isVrActive() { return vrActive; }

    /**
     * 在 Camera.setup 前计算一次本地第一人称姿态。
     */
    public boolean prepareFirstPersonPose(LivingEntity entity, float entityYaw, float tickDelta) {
        return prepareCameraPose(entity, entityYaw, tickDelta, RenderScene.FIRST_PERSON);
    }

    /** 相机和正式世界绘制复用同帧姿态，避免重复推进动画。 */
    public boolean prepareCameraPose(LivingEntity entity, float entityYaw, float tickDelta, RenderScene scene) {
        evaluationScene = RenderPerformanceProfiler.scene(scene);
        firstPersonPoseState.beginPreparation();
        if (entity == null || model == 0 || !isReady() || scene == null || !scene.isWorldScene()) {
            return false;
        }

        boolean stagePlaying = MMDCameraController.getInstance().isStagePlayingModel(model);
        applyPhysicsState(RuntimeConfigPortHolder.get().isPhysicsEnabled());

        long syncTimer = RenderPerformanceProfiler.get().startTimer();
        LivingEntityModelStateHelper.syncModelState(
                model,
                entity,
                entityYaw,
                tickDelta,
                scene,
                getModelName(),
                stagePlaying,
                vrActive);
        RenderPerformanceProfiler.get().endTimer(RenderPerformanceProfiler.SECTION_LIVING_STATE_SYNC, syncTimer);

        // 即使是模型第一帧，也执行一次零步长求值来生成当前骨骼矩阵。
        update(true);
        firstPersonPoseState.markPrepared();
        return true;
    }

    public boolean hasPreparedFirstPersonPose() {
        return firstPersonPoseState.isPrepared();
    }

    public void discardPreparedFirstPersonPose() {
        firstPersonPoseState.discard();
    }

    /** 在不重复同步实体状态的前提下，让刚提交的一次性手臂目标进入当前 prepared pose。 */
    public boolean refreshPreparedFirstPersonPose() {
        return firstPersonPoseState.isPrepared() && update(true);
    }

    protected boolean supportsPaperDollPoseReuse(boolean firstPerson) { return false; }

    /** Reuse the world head, eyes and physics; only the GUI root transform changes facing. */
    public final boolean canReuseForPaperDoll(LivingEntity entity) {
        if (model == 0 || !isReady() || vrActive || globalAlpha != 1.0f
                || "false".equalsIgnoreCase(System.getProperty("mmdskin.paperDollReuse"))) return false;
        long frame = com.shiroha.mmdskin.render.pipeline.RenderFrameClock.current();
        long revision = backendPort().poseInputRevision(model);
        return paperDollPose.matches(entity, frame, revision)
                && evaluationCache.matches(frame, revision)
                && supportsPaperDollPoseReuse(paperDollPose.isFirstPerson())
                && !backendPort().hasExternalPoseInput(model)
                && !MMDCameraController.getInstance().isStagePlayingModel(model);
    }

    public final void renderPaperDollPose(LivingEntity entity, PoseStack stack, int packedLight) {
        RenderPerformanceProfiler.get().recordModel(RenderPerformanceProfiler.Scene.PAPERDOLL, model);
        RenderPerformanceProfiler.get().recordEvaluationReuse(RenderPerformanceProfiler.Scene.PAPERDOLL);
        doRenderModel(entity, 0.0f, 0.0f, new Vector3f(), stack, packedLight, RenderScene.PAPERDOLL);
    }

    /** 应用所有渲染后端共享的模型根变换，供 TaCZ 坐标换算复用。 */
    public void applyModelRootTransform(PoseStack stack, float entityYaw, float entityPitch,
                                        Vector3f entityTranslation) {
        float degreesToRadians = (float) Math.PI / 180.0f;
        // MMD 根坐标系与 Minecraft 实体 yaw 的旋转方向相反。
        stack.mulPose(new Quaternionf().rotateY(-entityYaw * degreesToRadians));
        stack.mulPose(new Quaternionf().rotateX(entityPitch * degreesToRadians));
        stack.translate(entityTranslation.x, entityTranslation.y, entityTranslation.z);
        float scale = getModelScale();
        stack.scale(scale, scale, scale);
    }

    protected final NativeRenderBackendPort backendPort() {
        if (nativeRenderBackendPort == null) {
            throw new IllegalStateException("nativeRenderBackendPort has not been initialized");
        }
        return nativeRenderBackendPort;
    }

    public final void bindEyeEmission(ToonShaderBase shader, int vertexCount, float strength) {
        if (strength > 0.0f && !eyeEmissionInitialized) {
            eyeEmissionInitialized = true;
            eyeEmissionBuffer = EyeEmissionBuffer.create(backendPort(), model, vertexCount);
        }
        shader.setEyeEmission(strength);
        if (eyeEmissionBuffer != null) {
            eyeEmissionBuffer.bind(shader.getMainProgram());
        } else {
            EyeEmissionBuffer.unbind(shader.getMainProgram());
        }
    }

    public final void clearEyeEmission(ToonShaderBase shader) {
        try {
            if (eyeEmissionBuffer != null) eyeEmissionBuffer.clearBinding(shader.getMainProgram());
            else EyeEmissionBuffer.unbind(shader.getMainProgram());
        } finally {
            shader.setEyeMaterialMask(0.0f);
        }
    }

    public final long getEyeEmissionVramUsage() {
        return eyeEmissionBuffer == null ? 0L : eyeEmissionBuffer.byteSize();
    }

    @Override
    public void render(Entity entityIn, float entityYaw, float entityPitch,
                       Vector3f entityTrans, float tickDelta, PoseStack mat,
                       int packedLight, RenderScene context) {
        if (model == 0 || !isReady()) return;
        evaluationScene = RenderPerformanceProfiler.scene(context);

        WorldRenderPolicy.Decision worldDecision = nonWorldDecision();
        if (context != null && context.isWorldScene()) {
            worldDecision = WorldRenderPolicy.get().resolve(model, entityIn);
            if (!worldDecision.shouldRender()) {
                return;
            }
        } else {
            applyPhysicsState(RuntimeConfigPortHolder.get().isPhysicsEnabled());
        }
        RenderPerformanceProfiler.get().recordModel(evaluationScene, model);

        if (entityIn instanceof LivingEntity living) {
            handleLivingEntity(living, entityYaw, entityPitch, entityTrans,
                    tickDelta, mat, packedLight, context, worldDecision);
            return;
        }

        applyPhysicsState(worldDecision.physicsEnabled());
        if (worldDecision.shouldUpdate()) {
            update();
        }
        doRenderModel(entityIn, entityYaw, entityPitch, entityTrans, mat, packedLight, context);
    }

    @Override
    public void changeAnim(long anim, long layer) {
        if (model != 0) backendPort().changeModelAnimation(model, anim, layer);
    }

    @Override
    public void transitionAnim(long anim, long layer, float transitionTime) {
        if (model != 0) backendPort().transitionLayerTo(model, layer, anim, transitionTime);
    }

    @Override
    public void setLayerLoop(long layer, boolean loop) {
        if (model != 0) backendPort().setLayerLoop(model, layer, loop);
    }

    @Override
    public void setLayerWeight(long layer, float weight) {
        if (model != 0) backendPort().setLayerWeight(model, layer, weight);
    }

    @Override
    public void resetPhysics() {
        if (model != 0) backendPort().resetModelPhysics(model);
    }

    @Override
    public long getModelHandle() { return model; }

    @Override
    public String getModelDir() { return modelDir; }

    @Override
    public boolean setLayerBoneMask(int layer, String rootBoneName) {
        return backendPort().setLayerBoneMask(model, layer, rootBoneName);
    }

    @Override
    public boolean setLayerBoneExclude(int layer, String rootBoneName) {
        return backendPort().setLayerBoneExclude(model, layer, rootBoneName);
    }

    @Override
    public String getModelName() {
        if (cachedModelName == null) {
            cachedModelName = ModelInstance.super.getModelName();
        }
        return cachedModelName;
    }

    @Override
    public long getRamUsage() {
        try {
            return backendPort().getModelMemoryUsage(model);
        } catch (Exception e) {
            return 0;
        }
    }

    private void handleLivingEntity(LivingEntity entityIn, float entityYaw, float entityPitch,
                                     Vector3f entityTrans, float tickDelta, PoseStack mat,
                                     int packedLight, RenderScene context, WorldRenderPolicy.Decision worldDecision) {
        boolean stagePlaying = MMDCameraController.getInstance().isStagePlayingModel(model);
        applyPhysicsState(worldDecision.physicsEnabled());
        long frame = com.shiroha.mmdskin.render.pipeline.RenderFrameClock.current();
        boolean failedThisFrame = failedEvaluationFrame == frame;
        boolean reusePreparedPose = context != null && context.isWorldScene()
                && evaluationCache.matchesWorld(frame, backendPort().poseInputRevision(model),
                        firstPersonPoseState.isPrepared(), vrActive);

        if (worldDecision.shouldUpdate() && !reusePreparedPose && !failedThisFrame) {
            long syncTimer = RenderPerformanceProfiler.get().startTimer();
            LivingEntityModelStateHelper.syncModelState(
                    model,
                    entityIn,
                    entityYaw,
                    tickDelta,
                    context,
                    getModelName(),
                    stagePlaying,
                    vrActive);
            RenderPerformanceProfiler.get().endTimer(RenderPerformanceProfiler.SECTION_LIVING_STATE_SYNC, syncTimer);

            update();
        } else if (worldDecision.shouldUpdate() && reusePreparedPose) {
            RenderPerformanceProfiler.get().recordEvaluationReuse(evaluationScene);
        }
        try {
            doRenderModel(entityIn, entityYaw, entityPitch, entityTrans, mat, packedLight, context);
            if (context != null && context.isWorldScene() && !IrisCompat.isRenderingShadows()) {
                paperDollPose.capture(entityIn, com.shiroha.mmdskin.render.pipeline.RenderFrameClock.current(),
                        backendPort().poseInputRevision(model), context.isFirstPerson());
            }
        } finally {
            firstPersonPoseState.finishRender(context != null && context.isFirstPerson());
        }
    }

    protected boolean update() {
        return update(false);
    }

    private boolean update(boolean forceEvaluation) {
        long frame = com.shiroha.mmdskin.render.pipeline.RenderFrameClock.current();
        if (failedEvaluationFrame == frame) return false;
        if (!forceEvaluation && !vrActive && !"false".equalsIgnoreCase(System.getProperty("mmdskin.reuseEvaluation"))
                && evaluationCache.matches(frame, backendPort().poseInputRevision(model))) {
            RenderPerformanceProfiler.get().recordEvaluationReuse(evaluationScene);
            return true;
        }
        long currentTime = System.currentTimeMillis();
        if (lastUpdateTime < 0) {
            lastUpdateTime = currentTime;
            if (!forceEvaluation) {
                return false;
            }
        }

        float deltaTime = (currentTime - lastUpdateTime) / 1000.0f;
        lastUpdateTime = currentTime;

        if (deltaTime <= 0.0f && !forceEvaluation) return false;
        if (deltaTime < 0.0f) deltaTime = 0.0f;
        if (deltaTime > MAX_DELTA_TIME) deltaTime = MAX_DELTA_TIME;

        var profiler = RenderPerformanceProfiler.get();
        long updateTimer = profiler.startTimer();
        boolean profiled = false;
        try {
            if (updateTimer != 0) {
                if (nativeProfile == null) nativeProfile = ByteBuffer.allocateDirect(
                        com.shiroha.mmdskin.render.pipeline.NativeEvaluationScope.PROFILE_BYTES)
                        .order(java.nio.ByteOrder.nativeOrder());
                profiled = backendPort().updateModelProfiled(model, deltaTime, usesCpuSkinning(), nativeProfile);
            }
            if (!profiled) onUpdate(deltaTime);
        } catch (RuntimeException | Error failure) {
            failedEvaluationFrame = frame;
            throw failure;
        }
        if (profiled) profiler.recordNativeProfile(evaluationScene, nativeProfile);
        RenderPerformanceProfiler.get().endTimer(RenderPerformanceProfiler.SECTION_NATIVE_MODEL_UPDATE, updateTimer);
        RenderPerformanceProfiler.get().endTimer(evaluationScene.nativeSection, updateTimer);
        RenderPerformanceProfiler.get().recordEvaluation(evaluationScene, physicsEnabled);
        evaluationCache.capture(frame, backendPort().poseInputRevision(model), evaluationScene);
        logRustDiagnostics();
        logPhysicsDiagnostic();
        issueDiagnostics.sample(backendPort(), model, getModelName(), getClass().getSimpleName());
        nativeUpdateRevision.incrementAndGet();
        return true;
    }

    protected boolean usesCpuSkinning() { return true; }
    protected boolean supportsParallelEvaluation() { return false; }

    public final boolean canPrepareWorldEvaluation() {
        return !"false".equalsIgnoreCase(System.getProperty("mmdskin.reuseEvaluation"))
                && supportsParallelEvaluation() && model != 0 && isReady() && !vrActive
                && !firstPersonPoseState.isPrepared() && !backendPort().hasExternalPoseInput(model)
                && !MMDCameraController.getInstance().isStagePlayingModel(model);
    }

    /** Render-thread input preparation. Only already-loaded, independent world instances qualify. */
    public final com.shiroha.mmdskin.render.pipeline.NativeEvaluationBatch.Work prepareWorldEvaluation(
            LivingEntity entity, float yaw, float tickDelta) {
        return prepareWorldEvaluation(entity, yaw, tickDelta, RenderPerformanceProfiler.Scene.WORLD);
    }

    /** 阴影和世界共用输入同步，但分别记录实际求值场景。 */
    public final com.shiroha.mmdskin.render.pipeline.NativeEvaluationBatch.Work prepareWorldEvaluation(
            LivingEntity entity, float yaw, float tickDelta, RenderPerformanceProfiler.Scene scene) {
        if (!canPrepareWorldEvaluation()) return null;
        if (scene != RenderPerformanceProfiler.Scene.WORLD && scene != RenderPerformanceProfiler.Scene.SHADOW) return null;
        evaluationScene = scene;
        long frame = com.shiroha.mmdskin.render.pipeline.RenderFrameClock.current();
        if (failedEvaluationFrame == frame
                || evaluationCache.matchesWorld(frame, backendPort().poseInputRevision(model),
                        firstPersonPoseState.isPrepared(), vrActive)) return null;
        var decision = WorldRenderPolicy.get().resolve(model, entity);
        if (!decision.shouldUpdate()) return null;
        applyPhysicsState(decision.physicsEnabled());
        var profiler = RenderPerformanceProfiler.get();
        long syncTimer = profiler.startTimer();
        LivingEntityModelStateHelper.syncModelState(model, entity, yaw, tickDelta,
                RenderScene.WORLD, getModelName(), false, false);
        profiler.endTimer(RenderPerformanceProfiler.SECTION_LIVING_STATE_SYNC, syncTimer);
        long now = System.currentTimeMillis();
        if (lastUpdateTime < 0) { lastUpdateTime = now; return null; }
        float delta = Math.min(MAX_DELTA_TIME, (now - lastUpdateTime) / 1000.0f);
        if (delta <= 0) return null;
        lastUpdateTime = now;
        boolean sample = profiler.isSamplingEnabled();
        long generation = profiler.samplingGeneration();
        if (sample && nativeProfile == null) nativeProfile = ByteBuffer.allocateDirect(
                com.shiroha.mmdskin.render.pipeline.NativeEvaluationScope.PROFILE_BYTES)
                .order(java.nio.ByteOrder.nativeOrder());
        return new PreparedEvaluation(this, backendPort(), model, frame, scene, delta, physicsEnabled,
                usesCpuSkinning(), sample ? nativeProfile : null, generation);
    }

    /** Contains no entity, GL resource or renderer callback; execute touches JNI and its own result only. */
    private static final class PreparedEvaluation implements com.shiroha.mmdskin.render.pipeline.NativeEvaluationBatch.Work {
        private final BaseModelInstance owner;
        private final NativeRenderBackendPort port;
        private final long handle, frame, generation;
        private final RenderPerformanceProfiler.Scene scene;
        private final float delta;
        private final boolean physics, cpu;
        private final ByteBuffer profile;
        private long elapsed;
        private boolean profiled;
        PreparedEvaluation(BaseModelInstance owner, NativeRenderBackendPort port, long handle, long frame,
                           RenderPerformanceProfiler.Scene scene,
                           float delta, boolean physics, boolean cpu, ByteBuffer profile, long generation) {
            this.owner = owner; this.port = port; this.handle = handle; this.frame = frame;
            this.scene = scene;
            this.delta = delta; this.physics = physics; this.cpu = cpu; this.profile = profile; this.generation = generation;
        }
        public long handle() { return handle; }
        public void execute() {
            long start = profile != null ? System.nanoTime() : 0;
            try {
                profiled = profile != null && port.updateModelProfiled(handle, delta, cpu, profile);
                if (!profiled) {
                    if (cpu) port.updateModel(handle, delta); else port.updateAnimationOnly(handle, delta);
                }
            } catch (RuntimeException | Error failure) {
                owner.failedEvaluationFrame = frame;
                throw failure;
            }
            if (start != 0) elapsed = System.nanoTime() - start;
        }
        public void complete() {
            var profiler = RenderPerformanceProfiler.get();
            if (profile != null && profiler.samplingGeneration() == generation && profiler.isSamplingEnabled()) {
                profiler.recordElapsed(RenderPerformanceProfiler.SECTION_NATIVE_MODEL_UPDATE, elapsed);
                profiler.recordElapsed(scene.nativeSection, elapsed);
                if (profiled) profiler.recordNativeProfile(scene, profile);
            }
            profiler.recordEvaluation(scene, physics);
            owner.evaluationCache.capture(frame, port.poseInputRevision(handle), scene);
            owner.logRustDiagnostics();
            owner.logPhysicsDiagnostic();
            owner.issueDiagnostics.sample(port, handle, owner.getModelName(), owner.getClass().getSimpleName());
            owner.nativeUpdateRevision.incrementAndGet();
        }
    }

    /** 将 Rust 全局日志队列按原级别转交给 Log4j。 */
    private void logRustDiagnostics() {
        String records = backendPort().takeRustLogs();
        if (records == null || records.isBlank()) {
            return;
        }

        records.lines().filter(line -> !line.isBlank()).forEach(line -> {
            String[] fields = line.split("\\t", 3);
            String level = fields.length > 0 ? fields[0] : "INFO";
            String target = fields.length > 1 ? fields[1] : "rust";
            String message = fields.length > 2 ? fields[2] : line;
            String formatted = "[Rust][" + target + "] " + message;
            switch (level) {
                case "ERROR" -> logger.error("{}", formatted);
                case "WARN" -> logger.warn("{}", formatted);
                case "DEBUG", "TRACE" -> logger.debug("{}", formatted);
                default -> {
                    if (ConfigManager.isPhysicsDebugLog()) {
                        logger.info("{}", formatted);
                    } else {
                        logger.debug("{}", formatted);
                    }
                }
            }
        });
    }

    /** 将 native 聚合结果显式交给 Log4j，确保 Forge/Fabric latest.log 可见。 */
    private void logPhysicsDiagnostic() {
        if (!ConfigManager.isPhysicsDebugLog()) {
            return;
        }
        String diagnostic = backendPort().takePhysicsDebugDiagnostic(model);
        if (diagnostic != null && !diagnostic.isBlank()) {
            // 逐行交给 Log4j，保证每段诊断都有完整前缀且不会挤成一条超长记录。
            diagnostic.lines()
                    .filter(line -> !line.isBlank())
                    .forEach(line -> logger.info("{}", line));
        }
    }

    protected long getNativeUpdateRevision() {
        return nativeUpdateRevision.get();
    }

    protected void fetchMaterialMorphResults() {
        if (materialMorphResultCount <= 0 || materialMorphResultsByteBuffer == null) return;
        materialMorphResultsByteBuffer.clear();
        backendPort().copyMaterialMorphResultsToBuffer(model, materialMorphResultsByteBuffer);
        materialMorphResultsByteBuffer.rewind();
    }

    public final ByteBuffer materialMorphData() {
        return materialMorphResultsByteBuffer;
    }

    private static final int MATERIAL_MORPH_STRIDE_FLOATS = 56;
    private static final int MATERIAL_MORPH_MUL_ALPHA_OFFSET = 3;
    private static final int MATERIAL_MORPH_ADD_ALPHA_OFFSET = 28 + 3;

    protected float getEffectiveMaterialAlpha(int materialIndex, float baseAlpha) {
        if (materialMorphResultsByteBuffer == null || materialIndex < 0 || materialIndex >= materialMorphResultCount)
            return baseAlpha * globalAlpha;
        int mulOffset = materialIndex * MATERIAL_MORPH_STRIDE_FLOATS + MATERIAL_MORPH_MUL_ALPHA_OFFSET;
        int addOffset = materialIndex * MATERIAL_MORPH_STRIDE_FLOATS + MATERIAL_MORPH_ADD_ALPHA_OFFSET;
        int capacity = materialMorphResultsByteBuffer.capacity() / 4;
        float mulAlpha = (mulOffset < capacity) ? materialMorphResultsByteBuffer.getFloat(mulOffset * 4) : 1.0f;
        float addAlpha = (addOffset < capacity) ? materialMorphResultsByteBuffer.getFloat(addOffset * 4) : 0.0f;
        return (baseAlpha * mulAlpha + addAlpha) * globalAlpha;
    }

    public float getModelScale() {
        return MODEL_SCALE * com.shiroha.mmdskin.config.ModelConfigManager.getConfig(getModelName()).modelScale;
    }

    protected void disposeModelHandle() {
        EyeEmissionBuffer buffer = eyeEmissionBuffer;
        eyeEmissionBuffer = null;
        eyeEmissionInitialized = false;
        try {
            if (buffer != null) buffer.close();
        } finally {
            if (model != 0) {
                com.shiroha.mmdskin.render.policy.RenderPriorityService.get().forgetModel(model);
                backendPort().deleteModel(model);
                model = 0;
            }
        }
    }

    protected void releaseTextures() {
        if (textureKeys != null) {
            TextureRepository.releaseAll(textureKeys);
            textureKeys = null;
        }
    }

    protected void disposeMaterialMorphBuffers() {
        if (materialMorphResultsByteBuffer != null) {
            MemoryUtil.memFree(materialMorphResultsByteBuffer);
            materialMorphResultsByteBuffer = null;
        }
    }

    /** 1.21.1 将镜头视图与实体局部 PoseStack 分开维护，上传前必须重新组合。 */
    public final Matrix4f composeModelViewMatrix(PoseStack deliverStack) {
        return composeModelViewMatrix(RenderSystem.getModelViewMatrix(), deliverStack.last().pose(),
                IrisCompat.isRenderingShadows(), composedModelViewMatrix);
    }

    static Matrix4f composeModelViewMatrix(Matrix4f cameraView, Matrix4f entityPose,
                                           boolean shadowPass, Matrix4f destination) {
        // Iris 阴影 PoseStack 已包含光源视图，不能再乘主相机视图。
        return shadowPass ? destination.set(entityPose) : cameraView.mul(entityPose, destination);
    }

    protected void setupShaderUniforms(ShaderInstance shader, PoseStack deliverStack,
                                       Vector3f light0Dir, Vector3f light1Dir, int lightMapTex) {
        if (shader.MODEL_VIEW_MATRIX != null)
            shader.MODEL_VIEW_MATRIX.set(composeModelViewMatrix(deliverStack));
        if (shader.PROJECTION_MATRIX != null)
            shader.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
        if (shader.COLOR_MODULATOR != null)
            shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        if (shader.LIGHT0_DIRECTION != null)
            shader.LIGHT0_DIRECTION.set(light0Dir);
        if (shader.LIGHT1_DIRECTION != null)
            shader.LIGHT1_DIRECTION.set(light1Dir);
        if (shader.FOG_START != null)
            shader.FOG_START.set(RenderSystem.getShaderFogStart());
        if (shader.FOG_END != null)
            shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        if (shader.FOG_COLOR != null)
            shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        if (shader.FOG_SHAPE != null)
            shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        if (shader.TEXTURE_MATRIX != null)
            shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        if (shader.GAME_TIME != null)
            shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        if (shader.SCREEN_SIZE != null) {
            Window window = Minecraft.getInstance().getWindow();
            shader.SCREEN_SIZE.set((float) window.getScreenWidth(), (float) window.getScreenHeight());
        }
        if (shader.LINE_WIDTH != null)
            shader.LINE_WIDTH.set(RenderSystem.getShaderLineWidth());

        // 原版和 Iris 实体接口一致：1 是覆盖色，2 是光照，不能混用模型贴图。
        var renderer = Minecraft.getInstance().gameRenderer;
        renderer.overlayTexture().setupOverlayColor();
        renderer.lightTexture().turnOnLightLayer();
        if (!IrisCompat.isIrisProgram(shader)) {
            shader.setSampler("Sampler1", RenderSystem.getShaderTexture(1));
            shader.setSampler("Sampler2", RenderSystem.getShaderTexture(2));
        }
    }

    protected abstract void doRenderModel(Entity entityIn, float entityYaw, float entityPitch,
                                           Vector3f entityTrans, PoseStack mat, int packedLight,
                                           RenderScene context);

    protected abstract void onUpdate(float deltaTime);

    protected boolean isReady() {
        return true;
    }

    private void applyPhysicsState(boolean enabled) {
        if (model == 0) {
            return;
        }

        if (!physicsStateInitialized || physicsEnabled != enabled) {
            backendPort().setPhysicsEnabled(model, enabled);
            physicsEnabled = enabled;
            physicsStateInitialized = true;
        }
    }

    private static final WorldRenderPolicy.Decision NON_WORLD_DECISION =
            new WorldRenderPolicy.Decision(true, true, true);

    private WorldRenderPolicy.Decision nonWorldDecision() {
        boolean physics = RuntimeConfigPortHolder.get().isPhysicsEnabled();
        if (physics) {
            return NON_WORLD_DECISION;
        }
        return new WorldRenderPolicy.Decision(true, true, false);
    }
}
