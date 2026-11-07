package com.shiroha.mmdskin.ui.selector.timeline;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.bridge.runtime.NativeAnimationBridgeHolder;
import com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridgeHolder;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.model.runtime.ModelRequestKey;
import com.shiroha.mmdskin.model.runtime.ModelSubjectKind;
import com.shiroha.mmdskin.player.render.ItemRenderHelper;
import com.shiroha.mmdskin.player.render.PaperDollRenderScope;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.render.scene.RenderScene;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import com.shiroha.mmdskin.ui.paperdoll.PaperDollFrameCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL46C;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Isolated models, animation handles and GUI depth; closing never restores or rewinds live models. */
public final class PropTimelinePreview implements AutoCloseable {
    private static final RenderScene SCENE = new RenderScene.Builder()
            .sceneType(RenderScene.SceneType.GUI).physics(false).build();
    private final ModelRequestKey actorKey, propKey;
    private final ModelConfigData config;
    private final Path propMotion, actorMotion;
    private final PaperDollFrameCache image = new PaperDollFrameCache();
    private ManagedModel actor, prop;
    private PreviewMotionBinding actorBinding, propBinding;
    private List<String> bones = List.of();
    private String missingBone = "";
    private boolean closed;

    public PropTimelinePreview(String modelName,ModelConfigData config,Path propMotion,Path actorMotion) {
        this.config = config.normalizedCopy();
        this.propMotion = propMotion;
        this.actorMotion = actorMotion;
        String id = UUID.randomUUID().toString();
        actorKey = new ModelRequestKey(ModelSubjectKind.OTHER,"timeline-preview/" + id,modelName,this.config.mainPmxFile);
        propKey = new ModelRequestKey(ModelSubjectKind.OTHER,"held-item/timeline-preview/" + id,modelName,this.config.heldPmxFile);
    }

    public boolean prepare(int frame) {
        if (closed) return false;
        var repository = ClientRenderRuntime.get().modelRepository();
        if (actor == null) actor = repository.acquire(actorKey);
        if (prop == null) prop = repository.acquire(propKey);
        if (actor == null || prop == null) return false;
        var animations = NativeAnimationBridgeHolder.get();
        var runtime = NativeRuntimeBridgeHolder.get();
        if (actorBinding == null) {
            actorBinding = new PreviewMotionBinding(animations,actor.modelInstance(),actorMotion);
            runtime.setAutoBlinkEnabled(actor.modelInstance().getModelHandle(),false);
            runtime.setEyeTrackingEnabled(actor.modelInstance().getModelHandle(),false);
            String[] names = new Gson().fromJson(runtime.getBoneNames(actor.modelInstance().getModelHandle()),String[].class);
            bones = names == null ? List.of() : Arrays.stream(names).filter(Objects::nonNull).distinct().toList();
        }
        if (propBinding == null) propBinding = new PreviewMotionBinding(animations,prop.modelInstance(),propMotion);
        actorBinding.seek(frame);
        propBinding.seek(frame);
        return true;
    }

    public float endFrame() {
        return Math.max(actorBinding == null ? 0 : actorBinding.endFrame(),propBinding == null ? 0 : propBinding.endFrame());
    }

    public List<String> bones() { return bones; }
    public String missingBone() { return missingBone; }

    public void render(GuiGraphics graphics,int x,int y,int width,int height,int frame,long revision,
                       PropTimeline.State state,float yaw,float pitch,float zoom) {
        graphics.flush();
        graphics.enableScissor(x,y,x+width,y+height);
        try {
            var key = List.of(frame,revision,state,yaw,pitch,zoom,width,height);
            if (!image.render(key,60,System.nanoTime(),() -> drawModels(x,y,width,height,state,yaw,pitch,zoom))) {
                // The fallback keeps depth clearing inside the preview box.
                RenderSystem.depthMask(true);
                GL46C.glClearBufferfv(GL46C.GL_DEPTH,0,new float[]{1});
                drawModels(x,y,width,height,state,yaw,pitch,zoom);
            }
        } finally {
            graphics.disableScissor();
        }
    }

    private void drawModels(int x,int y,int width,int height,PropTimeline.State state,float yaw,float pitch,float zoom) {
        Minecraft client = Minecraft.getInstance();
        PaperDollRenderScope.enter();
        try {
            Lighting.setupForEntityInInventory();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL46C.GL_LEQUAL);
            RenderSystem.setShaderColor(1,1,1,1);
            RenderSystem.setShader(GameRenderer::getRendertypeEntityTranslucentShader);
            client.gameRenderer.lightTexture().turnOnLightLayer();
            PoseStack stack = new PoseStack();
            stack.translate(x+width*.5,y+height-8,150);
            float scale = Math.max(1,(height-18)/2.0f) * zoom / config.modelScale;
            stack.scale(scale,scale,-scale);
            stack.mulPose(new Quaternionf().rotateZ((float)Math.PI).rotateY((float)Math.toRadians(180+yaw))
                    .rotateX((float)Math.toRadians(pitch)));
            // The renderer applies the actor model scale to this stack, exactly as in the held-prop path.
            actor.modelInstance().render(null,0,0,new Vector3f(),0,stack,0xF000F0,SCENE);
            missingBone = "";
            if (!state.visible()) return;
            var attachment = state.attachment();
            var matrix = attachment == null ? ItemRenderHelper.getPropAttachment(actor,config.heldPmxBone)
                    : ItemRenderHelper.getTimelinePropAttachment(actor,attachment.bone());
            if (matrix == null) {
                missingBone = attachment.bone();
                return;
            }
            stack.last().pose().mul(ItemRenderHelper.propLocalTransform(matrix,config,
                    attachment == null ? PropTimeline.Offset.IDENTITY : attachment.offset()));
            float propScale = 10 * config.heldItemScale * config.heldPmxScale;
            stack.scale(propScale,propScale,propScale);
            prop.modelInstance().render(null,0,0,new Vector3f(),0,stack,0xF000F0,SCENE);
        } finally {
            client.gameRenderer.lightTexture().turnOffLightLayer();
            Lighting.setupFor3DItems();
            RenderSystem.setShaderColor(1,1,1,1);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(true);
            PaperDollRenderScope.exit();
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            if (actorBinding != null) actorBinding.close();
        } finally {
            try {
                if (propBinding != null) propBinding.close();
            } finally {
                try {
                    image.close();
                } finally {
                    var repository = ClientRenderRuntime.get().modelRepository();
                    try { repository.reloadSubject(actorKey); }
                    finally { repository.reloadSubject(propKey); }
                }
            }
        }
    }
}
