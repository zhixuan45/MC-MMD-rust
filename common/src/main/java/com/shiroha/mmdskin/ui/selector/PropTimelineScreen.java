package com.shiroha.mmdskin.ui.selector;

import com.shiroha.mmdskin.asset.catalog.HeldPmxMotionFiles;
import com.shiroha.mmdskin.asset.catalog.PropTimelineFiles;
import com.shiroha.mmdskin.asset.catalog.PropTimelineCalibration;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.config.PathConstants;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Attachment;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Offset;
import com.shiroha.mmdskin.ui.chrome.TranslucentTrayChrome;
import com.shiroha.mmdskin.ui.selector.timeline.PropTimelineEditor;
import com.shiroha.mmdskin.ui.selector.timeline.PropTimelineEditor.Track;
import com.shiroha.mmdskin.ui.selector.timeline.PropTimelinePreview;
import com.shiroha.mmdskin.ui.selector.timeline.TimelineMotionChoices;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Native GUI editor for the prop VMD sidecar, with isolated character and prop preview. */
public final class PropTimelineScreen extends Screen {
    private final Screen parent;
    private final String modelName;
    private final ModelConfigData config;
    private final Path modelDir;
    private PropTimelineFiles.Document document;
    private PropTimelineEditor editor;
    private PropTimelinePreview preview;
    private Path motion,referenceMotion;
    private String referenceLabel = "",status = "";
    private Map<String,Path> motionChoices = Map.of();
    private List<String> boneChoices = List.of();
    private boolean failedOpen,failedPreview,syncing,scrubbing,rotating,draftVisible;
    private Track track = Track.VISIBILITY;
    private PropTimelineLayout layout;
    private final List<AbstractWidget> chrome = new ArrayList<>(), properties = new ArrayList<>();
    private final List<Integer> propertyY = new ArrayList<>();
    private EditBox frameBox,boneBox;
    private final EditBox[] offsets = new EditBox[6];
    private Button playButton,saveButton,deleteButton,valueButton,initialButton,writeButton;
    private String pendingBone;
    private String[] pendingOffsets;
    private boolean pendingKeepWorld;
    private int propertyScroll;
    private float yaw = 20,pitch,zoom = 1;
    private long lastRenderNs;
    private boolean keepWorld=true;
    private Button childButton,modeButton;
    private List<String> propBoneChoices=List.of();

    public PropTimelineScreen(Screen parent,String modelName,ModelConfigData config) {
        super(text("title"));
        this.parent = parent;
        this.modelName = modelName;
        this.config = config.normalizedCopy();
        modelDir = PathConstants.getModelDir(modelName).toPath();
    }

    private static Component text(String key,Object... values) {
        return Component.translatable("gui.mmdskin.timeline."+key,values);
    }

    @Override protected void init() {
        clearWidgets();
        chrome.clear(); properties.clear(); propertyY.clear();
        layout = new PropTimelineLayout(width,height);
        if (document == null && !failedOpen) {
            try {
                motion = HeldPmxMotionFiles.resolve(
                        com.shiroha.mmdskin.asset.catalog.HeldPmxAssets.motionDirectory(modelName,config.heldPmxFile),
                        config.heldPmxStageMotion);
                if (motion == null || config.heldPmxFile.isEmpty()) throw new IllegalArgumentException(text("select_assets").getString());
                document = PropTimelineFiles.open(motion,
                        com.shiroha.mmdskin.asset.catalog.HeldPmxAssets.fileName(config.heldPmxFile));
                editor = new PropTimelineEditor(document.timeline());
                editor.motionEnd(300);
                motionChoices = TimelineMotionChoices.list(modelDir,PathConstants.getStageAnimDir().toPath());
                referenceLabel=PropTimelineFiles.referenceMotion(document);
                referenceMotion=motionChoices.get(referenceLabel);
                if(!editor.snapshot().boneKeys().isEmpty()) {
                    if(missingReference() || !java.util.Objects.equals(PropTimelineCalibration.read(document),
                            PropTimelineCalibration.capture(modelName,config,motion,referenceMotion))) editor.invalidateCompensation();
                }
                boneChoices = ModelSelectorServices.modelSettings().listBoneNames(modelName);
            } catch (Exception failure) {
                failedOpen = true;
                report(failure);
            }
        }
        if (editor != null && preview == null && !failedPreview) {
            preview = new PropTimelinePreview(modelName,config,motion,referenceMotion);
        }
        int y = layout.controlsY;
        button(12,y,30,text("previous"),() -> seek(editor.frame()-1));
        playButton = button(46,y,40,text("play"),() -> editor.playing(!editor.playing()));
        button(90,y,30,text("next"),() -> seek(editor.frame()+1));
        frameBox = new EditBox(font,124,y,56,18,text("frame"));
        frameBox.setMaxLength(10);
        frameBox.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        addRenderableWidget(frameBox); chrome.add(frameBox);
        frameBox.setResponder(value -> {
            if (!syncing && editor != null && !value.isEmpty()) {
                try { seek(Integer.parseInt(value)); } catch (NumberFormatException ignored) { }
            }
        });
        button(width-70,y,26,Component.literal("<K"),() -> adjacent(false));
        button(width-40,y,26,Component.literal("K>"),() -> adjacent(true));
        button(12,layout.footerY,Math.max(1,width-184),text("reference"),this::chooseReference);
        saveButton = button(width-164,layout.footerY,72,text("save"),this::save);
        Button close = button(width-84,layout.footerY,72,text("back"),this::onClose);
        close.active = true;
        if (editor == null) {
            for (AbstractWidget widget : chrome) if (widget != close) widget.active = false;
            return;
        }
        button(layout.inspector.x()+4,layout.inspector.y()+3,(layout.inspector.w()-12)/2,text("visibility"),() -> selectTrack(Track.VISIBILITY));
        button(layout.inspector.x()+8+(layout.inspector.w()-12)/2,layout.inspector.y()+3,(layout.inspector.w()-12)/2,text("attachment"),() -> selectTrack(Track.ATTACHMENT));
        buildProperties();
        refreshFields();
        lastRenderNs = System.nanoTime();
    }

    private Button button(int x,int y,int w,Component label,Runnable action) {
        Button button = Button.builder(label,b -> action.run()).bounds(x,y,w,18).build();
        addRenderableWidget(button); chrome.add(button);
        return button;
    }

    private Button propertyButton(int y,Component label,Runnable action) {
        Button button = Button.builder(label,b -> action.run()).bounds(layout.properties.x(),0,layout.properties.w(),18).build();
        addProperty(button,y);
        return button;
    }

    private void addProperty(AbstractWidget widget,int y) {
        addWidget(widget); properties.add(widget); propertyY.add(y);
    }

    private void buildProperties() {
        if (track == Track.VISIBILITY) {
            valueButton = propertyButton(0,text("visible"),() -> {
                draftVisible = !draftVisible;
                updateLabels();
            });
            writeButton = propertyButton(24,text("write_visibility"),() -> {
                editor.visibility(draftVisible);
                changed();
            });
            deleteButton = propertyButton(48,text("delete"),this::delete);
            initialButton = propertyButton(80,text("initial_visible"),() -> {
                editor.initialVisible(!editor.initialVisible());
                changed();
            });
        } else {
            childButton=propertyButton(0,text("child",editor.selectedBone().isEmpty()?text("whole_prop").getString():editor.selectedBone()),this::chooseChild);
            modeButton=propertyButton(24,text(keepWorld?"keep_world":"snap"),() -> {
                keepWorld=!keepWorld; modeButton.setMessage(text(keepWorld?"keep_world":"snap"));
            });
            boneBox = new EditBox(font,layout.properties.x(),0,layout.properties.w()-40,18,text("bone"));
            boneBox.setMaxLength(256);
            addProperty(boneBox,60);
            Button choose = Button.builder(Component.literal("…"),b -> chooseBone())
                    .bounds(layout.properties.right()-36,0,36,18).build();
            addProperty(choose,60);
            int cell = (layout.properties.w()-8)/3;
            for (int i = 0; i < offsets.length; i++) {
                offsets[i] = new EditBox(font,layout.properties.x()+(i%3)*(cell+4),0,cell,18,
                        text(i<3?"offset":"rotation"));
                offsets[i].setMaxLength(20);
                addProperty(offsets[i],i<3?98:134);
            }
            writeButton = propertyButton(160,text("write_attachment"),this::writeAttachment);
            deleteButton = propertyButton(184,text("delete"),this::delete);
            propertyButton(208,text("unbind"),() -> {
                if(!editor.selectedBone().isEmpty()) {
                    editor.boneBinding(com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.BoneBinding.detached(editor.selectedBone()));
                    changed();
                }
            });
            propertyButton(232,text("recalculate"),() -> {
                try { recalculate(); status=text("recalculated").getString(); refreshFields(); }
                catch(Exception failure) { report(failure); }
            });
        }
        positionProperties();
    }

    private void positionProperties() {
        int content = track == Track.VISIBILITY ? 102 : 254;
        propertyScroll = Math.max(0,Math.min(Math.max(0,content-layout.properties.h()),propertyScroll));
        for (int i = 0; i < properties.size(); i++) {
            AbstractWidget widget = properties.get(i);
            widget.setY(layout.properties.y()+propertyY.get(i)-propertyScroll);
            widget.visible = widget.getY()+widget.getHeight()>layout.properties.y() && widget.getY()<layout.properties.bottom();
        }
    }

    private void refreshFields() {
        if (editor == null) return;
        syncing = true;
        try {
            frameBox.setValue(Integer.toString(editor.frame()));
            var state = editor.state();
            draftVisible = state.visible();
            if (track == Track.ATTACHMENT) {
                Attachment attachment = state.attachment();
                var boneBinding=state.boneBindings().get(editor.selectedBone());
                if(!editor.selectedBone().isEmpty()) {
                    attachment=boneBinding==null?null:new Attachment(boneBinding.bone(),boneBinding.offset());
                    if(boneBinding!=null) keepWorld=boneBinding.keepWorld();
                    childButton.setMessage(text("child",editor.selectedBone()));
                    modeButton.setMessage(text(keepWorld?"keep_world":"snap"));
                }
                boneBox.setValue(attachment == null ? config.heldPmxBone : attachment.bone());
                Offset offset = attachment == null ? Offset.IDENTITY : attachment.offset();
                float[] values = {offset.x(),offset.y(),offset.z(),offset.rotationX(),offset.rotationY(),offset.rotationZ()};
                for (int i = 0; i < values.length; i++) offsets[i].setValue(Float.toString(values[i]));
                if (pendingBone != null) {
                    boneBox.setValue(pendingBone);
                    for (int i = 0; i < offsets.length; i++) offsets[i].setValue(pendingOffsets[i]);
                    pendingBone = null;
                    pendingOffsets = null;
                    keepWorld=pendingKeepWorld;
                    modeButton.setMessage(text(keepWorld?"keep_world":"snap"));
                }
            }
            updateLabels();
        } finally { syncing = false; }
    }

    private void updateLabels() {
        if (editor == null) return;
        if (track == Track.VISIBILITY) {
            valueButton.setMessage(text(draftVisible?"visible":"hidden"));
            initialButton.setMessage(text(editor.initialVisible()?"initial_visible":"initial_hidden"));
        }
        writeButton.setMessage(text(editor.hasKey(track)?"update_key":track==Track.VISIBILITY?"write_visibility":"write_attachment"));
        deleteButton.active = editor.hasKey(track);
    }

    private void selectTrack(Track value) {
        track = value; propertyScroll = 0;
        init();
    }

    private void seek(int frame) {
        editor.playing(false);
        editor.seek(frame);
        refreshFields();
    }

    private void adjacent(boolean next) {
        editor.playing(false);
        editor.adjacent(track,next);
        refreshFields();
    }

    private void changed() {
        status = "";
        refreshFields();
    }

    private void delete() { editor.delete(track); changed(); }

    private void writeAttachment() {
        try {
            float[] values = new float[6];
            for (int i = 0; i < values.length; i++) values[i] = Float.parseFloat(offsets[i].getValue());
            var offset=new Offset(values[0],values[1],values[2],values[3],values[4],values[5]);
            if(editor.selectedBone().isEmpty()) editor.attachment(new Attachment(boneBox.getValue().trim(),offset));
            else {
                requireRegistrationPreview();
                if(editor.compensationStale()) recalculate();
                String child=editor.selectedBone();
                editor.boneBinding(preview.register(editor.frame(),editor.beforeRegistration(editor.frame(),child),
                        child,boneBox.getValue().trim(),offset,keepWorld));
            }
            changed();
        } catch (Exception invalid) { status = text("register_failed",invalid.getMessage()).getString(); }
    }

    private void chooseChild() {
        editor.playing(false);
        var choices=new java.util.LinkedHashSet<>(propBoneChoices);
        choices.addAll(editor.bindingBones());
        minecraft.setScreen(new HeldPmxSelectionScreen(this,text("choose_child"),text("child_hint"),
                text("whole_prop"),List.copyOf(choices),editor.selectedBone(),true,editor::selectedBone));
    }

    private void recalculate() {
        if(editor.snapshot().boneKeys().isEmpty()) return;
        requireRegistrationPreview();
        var source=editor.snapshot();
        var keys=new ArrayList<com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Key<com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.BoneBinding>>();
        for(var key:source.boneKeys()) {
            var binding=key.value();
            if(binding.unbind()) keys.add(key);
            else {
                var baseline=new com.shiroha.mmdskin.stage.domain.timeline.PropTimeline(source.initial(),source.visibility(),source.attachments(),keys);
                keys.add(new com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Key<>(key.frame(),
                        preview.register(key.frame(),baseline,binding.childBone(),binding.bone(),binding.offset(),binding.keepWorld())));
            }
        }
        editor.replaceCalibrated(keys);
    }

    private boolean missingReference() {
        return !referenceLabel.isEmpty() && (referenceMotion==null || !java.nio.file.Files.isRegularFile(referenceMotion));
    }

    private void requireRegistrationPreview() {
        if(missingReference()) throw new IllegalStateException(text("reference_missing",referenceLabel).getString());
        if(preview==null) throw new IllegalStateException(text("wait_preview").getString());
    }
    private void chooseBone() {
        editor.playing(false);
        String current = boneBox.getValue();
        boolean currentMode=keepWorld;
        String[] currentOffsets = java.util.Arrays.stream(offsets).map(EditBox::getValue).toArray(String[]::new);
        minecraft.setScreen(new HeldPmxSelectionScreen(this,text("choose_bone"),text("bone_hint"),
                text("choose_bone"),boneChoices,current,true,value -> {
                    pendingBone = value;
                    pendingOffsets = currentOffsets;
                    pendingKeepWorld=currentMode;
                }));
    }

    private void chooseReference() {
        editor.playing(false);
        minecraft.setScreen(new HeldPmxSelectionScreen(this,text("reference"),text("reference_hint"),
                text("reference_none"),List.copyOf(motionChoices.keySet()),referenceLabel,false,value -> {
                    if(!referenceLabel.equals(value)) editor.invalidateCompensation();
                    referenceLabel = value;
                    referenceMotion = motionChoices.get(value);
                    failedPreview = false;
                    status = "";
                }));
    }

    private void save() {
        try {
            PropTimelineCalibration.Signature calibration=null;
            if(!editor.snapshot().boneKeys().isEmpty()) {
                requireRegistrationPreview();
                calibration=PropTimelineCalibration.capture(modelName,config,motion,referenceMotion);
                if(!calibration.equals(PropTimelineCalibration.read(document))) editor.invalidateCompensation();
            }
            if(editor.compensationStale()) recalculate();
            document = PropTimelineFiles.save(document,editor.snapshot(),referenceLabel,calibration);
            editor.markSaved();
            status = text("saved").getString();
        } catch (Exception failure) { report(failure); }
    }

    private void report(Exception failure) {
        status = text("error",failure.getMessage()).getString();
        LogManager.getLogger().warn("[PropTimelineEditor] {}",failure.getMessage(),failure);
    }

    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float delta) {
        graphics.fill(0,0,width,height,0xe518202c);
        graphics.drawString(font,title.getString()+(editor!=null && editor.dirty()?" *":""),12,8,0xffeef4fc,false);
        graphics.drawString(font,font.plainSubstrByWidth(config.heldPmxStageMotion+" · "+config.heldPmxFile,width-24),12,22,0xffa9bdd3,false);
        String notice=status;
        if(notice.isEmpty() && missingReference()) notice=text("reference_missing",referenceLabel).getString();
        if(notice.isEmpty() && editor!=null && editor.compensationStale()) notice=text("compensation_stale").getString();
        if (!notice.isEmpty()) graphics.drawString(font,font.plainSubstrByWidth(notice,width-24),12,35,0xffffc77a,false);
        graphics.fill(layout.preview.x(),layout.preview.y(),layout.preview.right(),layout.preview.bottom(),0xff111923);
        graphics.fill(layout.inspector.x(),layout.inspector.y(),layout.inspector.right(),layout.inspector.bottom(),0xff253143);
        if (editor != null) {
            long now = System.nanoTime();
            int oldFrame = editor.frame();
            editor.advance((now-lastRenderNs)/1_000_000_000.0);
            lastRenderNs = now;
            if (editor.frame()!=oldFrame) refreshFields();
            renderPreview(graphics);
            drawTimeline(graphics,mouseX,mouseY);
            playButton.setMessage(text(editor.playing()?"pause":"play"));
            saveButton.active = editor.dirty() || document.originalJson()==null;
            graphics.drawString(font,font.plainSubstrByWidth("/ "+editor.endFrame(),Math.max(1,width-258)),184,layout.controlsY+5,0xffbccce0,false);
        }
        super.render(graphics,mouseX,mouseY,delta);
        if (editor != null) {
            graphics.enableScissor(layout.properties.x(),layout.properties.y(),layout.properties.right(),layout.properties.bottom());
            if (track==Track.ATTACHMENT) {
                int y = layout.properties.y()-propertyScroll;
                graphics.drawString(font,text("bone"),layout.properties.x(),y+48,0xffd7e4f6,false);
                graphics.drawString(font,text("offset"),layout.properties.x(),y+86,0xffb7c9de,false);
                graphics.drawString(font,text("rotation"),layout.properties.x(),y+122,0xffb7c9de,false);
            }
            for (AbstractWidget widget : properties) widget.render(graphics,mouseX,mouseY,delta);
            graphics.disableScissor();
        }
    }

    private void renderPreview(GuiGraphics graphics) {
        if (preview != null) {
            try {
                if (preview.prepare(editor.frame())) {
                    int oldFrame = editor.frame();
                    editor.motionEnd(preview.endFrame());
                    if (editor.frame()!=oldFrame) refreshFields();
                    boneChoices = preview.bones();
                    propBoneChoices=preview.propBones();
                    preview.render(graphics,layout.preview.x(),layout.preview.y(),layout.preview.w(),layout.preview.h(),
                            editor.frame(),editor.revision(),editor.state(),yaw,pitch,zoom);
                    if (!preview.missingBone().isEmpty()) {
                        graphics.drawString(font,text("missing_bone",preview.missingBone()),layout.preview.x()+4,layout.preview.y()+4,0xffff9a80,false);
                    }
                    graphics.drawString(font,font.plainSubstrByWidth(text("preview_hint").getString(),layout.preview.w()-8),
                            layout.preview.x()+4,layout.preview.bottom()-12,0xff98abc4,false);
                    return;
                }
            } catch (Exception failure) {
                report(failure);
                closePreview();
                failedPreview = true;
            }
        }
        graphics.drawCenteredString(font,text(failedPreview?"preview_failed":"loading"),
                layout.preview.x()+layout.preview.w()/2,layout.preview.y()+layout.preview.h()/2,0xffa4b9d2);
    }

    private void drawTimeline(GuiGraphics graphics,int mouseX,int mouseY) {
        int end = editor.endFrame();
        int top = layout.visibility.y()-12;
        for (int i = 0; i <= 5; i++) {
            int frame = (int)Math.round(end*i/5d),x = layout.xAt(frame,end);
            graphics.drawCenteredString(font,Integer.toString(frame),x,top,0xff8fa6c0);
        }
        for (Track value : Track.values()) {
            var row = value==Track.VISIBILITY?layout.visibility:layout.attachment;
            graphics.fill(row.x(),row.y(),row.right(),row.bottom(),track==value?0xff30455d:0xff233246);
            graphics.drawString(font,text(value==Track.VISIBILITY?"visibility":"attachment"),row.x()+4,row.y()+5,0xffdce8f7,false);
            int lastX = -10;
            for (int frame : editor.frames(value)) {
                int x = layout.xAt(frame,end);
                if (x==lastX) continue;
                lastX = x;
                int color = frame==editor.frame()?0xffffd47d:value==Track.VISIBILITY?0xff64d6bc:0xff8cb5ff;
                graphics.fill(x-2,row.y()+4,x+3,row.bottom()-4,color);
            }
            if(value==Track.ATTACHMENT) {
                var childBones=editor.bindingBones();
                for(var key:editor.snapshot().boneKeys()) {
                    int x=layout.xAt(key.frame(),end);
                    int lane=childBones.indexOf(key.value().childBone())%3;
                    graphics.fill(x-2,row.y()+2+lane*4,x+3,row.y()+5+lane*4,
                            key.value().childBone().equals(editor.selectedBone())?0xffffd47d:0xff8cb5ff);
                }
                int count=editor.state().boneBindings().size();
                if(count>0) graphics.drawString(font,Integer.toString(count),row.x()+56,row.y()+5,0xffe7bd76,false);
            }
        }
        int cursor = layout.xAt(editor.frame(),end);
        graphics.fill(cursor,top+9,cursor+1,layout.attachment.bottom(),0xffffdb95);
    }

    @Override public boolean mouseClicked(double x,double y,int button) {
        if (button!=0) return super.mouseClicked(x,y,button);
        if (editor != null) {
            if (x>=layout.railX && x<layout.railX+layout.railWidth
                    && y>=layout.visibility.y()-12 && y<layout.visibility.y()) {
                setFocused(null);
                seek(layout.frameAt(x,editor.endFrame()));
                scrubbing=true;
                return true;
            }
            Track clicked = layout.visibility.contains(x,y)?Track.VISIBILITY:layout.attachment.contains(x,y)?Track.ATTACHMENT:null;
            if (clicked != null) {
                if (track!=clicked) selectTrack(clicked);
                setFocused(null);
                if (x>=layout.railX) {
                    int frame = layout.frameAt(x,editor.endFrame());
                    int distance = 5;
                    if(track==Track.ATTACHMENT) {
                        var childBones=editor.bindingBones();
                        for(var key:editor.snapshot().boneKeys()) {
                            int lane=childBones.indexOf(key.value().childBone())%3;
                            if(Math.abs(layout.xAt(key.frame(),editor.endFrame())-x)<5
                                    && Math.abs(layout.attachment.y()+3+lane*4-y)<=3) {
                                editor.selectedBone(key.value().childBone()); frame=key.frame(); distance=0;
                                init();
                                break;
                            }
                        }
                    }
                    for (int key : editor.frames(track)) {
                        int current = Math.abs(layout.xAt(key,editor.endFrame())-(int)x);
                        if (current<distance) { frame=key; distance=current; }
                    }
                    seek(frame); scrubbing=true;
                }
                return true;
            }
            if (layout.preview.contains(x,y)) { rotating=true; setFocused(null); return true; }
            if (layout.properties.contains(x,y)) return click(properties,x,y,button);
        }
        return click(chrome,x,y,button);
    }

    private boolean click(List<AbstractWidget> widgets,double x,double y,int button) {
        for (AbstractWidget widget : widgets) {
            if (widget.mouseClicked(x,y,button)) {
                if (children().contains(widget)) setFocused(widget);
                setDragging(true);
                return true;
            }
        }
        setFocused(null);
        return false;
    }

    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if (button==0 && scrubbing) { seek(layout.frameAt(x,editor.endFrame())); return true; }
        if (button==0 && rotating) {
            yaw += (float)dx*.7f; pitch = Math.max(-80,Math.min(80,pitch+(float)dy*.5f));
            return true;
        }
        return super.mouseDragged(x,y,button,dx,dy);
    }

    @Override public boolean mouseReleased(double x,double y,int button) {
        scrubbing=false; rotating=false;
        return super.mouseReleased(x,y,button);
    }

    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if (layout.inspector.contains(x,y)) {
            propertyScroll -= (int)Math.round(vertical*18);
            positionProperties();
            return true;
        }
        if (layout.preview.contains(x,y)) {
            zoom = Math.max(.15f,Math.min(5,zoom*(float)Math.pow(1.1,vertical)));
            return true;
        }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }

    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if (editor != null) {
            if (hasControlDown() && key==GLFW.GLFW_KEY_S) { save(); return true; }
            if (!(getFocused() instanceof EditBox box && box.isFocused())) {
                switch (key) {
                    case GLFW.GLFW_KEY_LEFT -> seek(editor.frame()-(hasShiftDown()?10:1));
                    case GLFW.GLFW_KEY_RIGHT -> seek(editor.frame()+(hasShiftDown()?10:1));
                    case GLFW.GLFW_KEY_HOME -> seek(0);
                    case GLFW.GLFW_KEY_END -> seek(editor.endFrame());
                    case GLFW.GLFW_KEY_SPACE -> editor.playing(!editor.playing());
                    case GLFW.GLFW_KEY_DELETE -> delete();
                    default -> { return super.keyPressed(key,scan,modifiers); }
                }
                return true;
            }
        }
        return super.keyPressed(key,scan,modifiers);
    }

    @Override public void onClose() {
        if (editor != null && editor.dirty()) {
            editor.playing(false);
            minecraft.setScreen(new ConfirmScreen(discard -> minecraft.setScreen(discard?parent:this),
                    text("unsaved_title"),text("unsaved_body"),text("discard"),text("continue")));
        } else minecraft.setScreen(parent);
    }

    private void closePreview() {
        if (preview == null) return;
        try { preview.close(); }
        catch (RuntimeException failure) { LogManager.getLogger().warn("[PropTimelineEditor] Could not release preview",failure); }
        finally { preview=null; }
    }

    @Override public void removed() { closePreview(); }
    @Override public boolean isPauseScreen() { return false; }
}
