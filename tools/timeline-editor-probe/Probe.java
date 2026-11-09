package com.shiroha.mmdskin.ui.selector;

import com.shiroha.mmdskin.asset.catalog.PropTimelineFiles;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.ui.selector.timeline.PropTimelineEditor;
import com.shiroha.mmdskin.ui.selector.timeline.PropTimelinePreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exercises production event handlers; Minecraft widgets and native preview are isolated shims. */
public final class Probe {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]), assets = Files.createTempDirectory(output,"assets-");
        System.setProperty("probe.assets",assets.toString());
        Path motion = assets.resolve("扇子动作.vmd");
        Files.write(motion,new byte[]{1});
        Files.writeString(assets.resolve("actor.pmx"),"synthetic actor");
        Files.writeString(assets.resolve("团扇.pmx"),"synthetic prop");
        Files.copy(Path.of("doc/examples/fan.timeline.json"),PropTimelineFiles.sidecar(motion));
        var config = new ModelConfigData();
        config.heldPmxFile="团扇.pmx"; config.heldPmxStageMotion="扇子动作.vmd";
        var parent = new Screen(Component.literal("parent"));
        var client = Minecraft.getInstance();
        var screen = new PropTimelineScreen(parent,"probe",config);
        client.setScreen(screen);
        render(screen,output.resolve("visibility-854.png"));
        var editor = (PropTimelineEditor)field(screen,"editor");
        var layout = (PropTimelineLayout)field(screen,"layout");
        screen.mouseClicked(layout.xAt(60,900),layout.visibility.y()-6,0);
        screen.mouseReleased(0,0,0);
        check(Math.abs(editor.frame()-60)<=1,"The ruler must seek without changing the selected track");

        screen.mouseClicked(layout.xAt(120,900),layout.visibility.y()+8,0);
        screen.mouseDragged(layout.xAt(300,900),layout.visibility.y()+8,0,100,0);
        screen.mouseReleased(0,0,0);
        check(Math.abs(editor.frame()-300)<=1,"Dragging the production rail must seek the selected frame");
        click(screen,widget(screen,"valueButton"));
        click(screen,widget(screen,"writeButton"));
        check(!editor.state().visible(),"Visibility button must write the selected frame");
        click(screen,widget(screen,"saveButton"));
        check(!PropTimelineFiles.load(motion,config.heldPmxFile).evaluate(editor.frame()).visible(),"Save must round-trip the edited visibility");

        screen.mouseClicked(layout.xAt(480,900),layout.attachment.y()+8,0);
        screen.mouseReleased(0,0,0);
        EditBox[] offsets = (EditBox[])field(screen,"offsets");
        offsets[0].setValue("2.5");
        @SuppressWarnings("unchecked")
        var properties = (List<AbstractWidget>)field(screen,"properties");
        click(screen,properties.stream().filter(w -> w.getMessage().getString().equals("…")).findFirst().orElseThrow());
        check(client.screen instanceof HeldPmxSelectionScreen,"Bone chooser must open");
        check(PropTimelinePreview.active==0,"Switching screens must release preview instances");
        // Actual HeldPmxSelectionScreen: empty option, right bone, left bone, head.
        client.screen.mouseClicked(client.width/2,72+2*20+8,0);
        check(client.screen==screen,"Bone chooser must return to the existing draft");
        check(((EditBox)field(screen,"boneBox")).getValue().equals("左ダミー"),"Selected bone must survive screen initialization");
        check(((EditBox[])field(screen,"offsets"))[0].getValue().equals("2.5"),"Bone chooser must preserve other draft fields");
        click(screen,widget(screen,"writeButton"));
        click(screen,widget(screen,"saveButton"));
        var saved = PropTimelineFiles.load(motion,config.heldPmxFile).evaluate(editor.frame()).attachment();
        check(saved.bone().equals("左ダミー") && saved.offset().x()==2.5f,"Attachment editor must save its bone and offset");
        render(screen,output.resolve("attachment-854.png"));

        click(screen,widget(screen,"childButton"));
        client.screen.mouseClicked(client.width/2,72+20+8,0);
        check(editor.selectedBone().equals("Root"),"Prop bone picker must select an independent child track");
        ((EditBox)field(screen,"boneBox")).setValue("右ダミー");
        click(screen,widget(screen,"writeButton"));
        click(screen,widget(screen,"childButton"));
        client.screen.mouseClicked(client.width/2,72+2*20+8,0);
        ((EditBox)field(screen,"boneBox")).setValue("左ダミー");
        click(screen,widget(screen,"writeButton"));
        check(editor.state().boneBindings().size()==2,"Two child bone registrations must coexist at one frame");
        check(PropTimelinePreview.lastBaseline.evaluate(editor.frame()).boneBindings().containsKey("Root"),
                "Second registration must sample a baseline containing the earlier same-frame registration");
        click(screen,widget(screen,"saveButton"));
        check(PropTimelineFiles.load(motion,config.heldPmxFile).boneKeys().size()==2,"Save must retain both bone tracks");
        check(com.shiroha.mmdskin.asset.catalog.PropTimelineCalibration.read(PropTimelineFiles.open(motion,config.heldPmxFile))!=null,
                "Bone compensation must save its actual asset calibration");
        render(screen,output.resolve("multi-bone-854.png"));

        var beforePlacement=editor.snapshot().boneKeys();
        click(screen,widget(screen,"placeButton"));
        check(field(screen,"placement")!=null,"Align must start a placement draft");
        check(editor.snapshot().boneKeys().equals(beforePlacement) && !editor.dirty(),"Placement must not register or dirty saved keys");
        offsets=(EditBox[])field(screen,"offsets");
        offsets[0].setValue(".75"); offsets[1].setValue("-.4"); offsets[2].setValue("1.2");
        offsets[3].setValue("25"); offsets[4].setValue("-35"); offsets[5].setValue("80");
        render(screen,output.resolve("placement-854.png"));
        var staged=PropTimelinePreview.lastState;
        var stagedBinding=staged.boneBindings().get("Center");
        check(stagedBinding.placement().x()==.75f && stagedBinding.placement().rotationZ()==80,"Placement fields must update the preview live");
        check(stagedBinding.offset().equals(com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Offset.IDENTITY),
                "Fine adjustment must be baked once, not also applied as a second offset");
        offsets[0].setValue("-");
        check(!widget(screen,"writeButton").active,"An incomplete numeric draft must not be registered");
        screen.initialize(854,480);
        check(((EditBox[])field(screen,"offsets"))[0].getValue().equals("-") && !widget(screen,"writeButton").active,
                "Reinitialization must preserve incomplete placement input and validation state");
        offsets=(EditBox[])field(screen,"offsets");
        offsets[0].setValue(".75");
        screen.onClose();
        check(client.screen instanceof ConfirmScreen,"An unregistered placement must receive discard protection");
        ((ConfirmScreen)client.screen).callback.accept(false);
        check(client.screen==screen && field(screen,"placement")!=null,"Returning must retain the placement and its controls");
        client.width=640; client.height=360;
        screen.initialize(640,360);
        check(((EditBox[])field(screen,"offsets"))[0].getValue().equals(".75"),"Resize must retain the placement adjustment");
        render(screen,output.resolve("placement-640.png"));
        client.width=854; client.height=480; screen.initialize(854,480);
        render(screen,output.resolve("placement-confirm-854.png"));
        int samplesBeforeConfirm=PropTimelinePreview.registrations;
        staged=PropTimelinePreview.lastState;
        click(screen,widget(screen,"writeButton"));
        check(PropTimelinePreview.registrations==samplesBeforeConfirm,"Confirmation must commit the already-previewed binding without resampling");
        check(editor.state().boneBindings().equals(staged.boneBindings()),"Registered bones must equal the exact pre-confirmation preview");
        check(staged.visible() && !editor.state().visible(),"Placement must temporarily show a hidden prop without changing saved visibility");
        check(field(screen,"placement")==null,"Confirmation must finish the placement draft");
        click(screen,widget(screen,"saveButton"));
        var placedKeys=PropTimelineFiles.load(motion,config.heldPmxFile).boneKeys();
        check(placedKeys.get(1).value().placement().rotationZ()==80,"Saved timeline must retain the placement recipe");
        click(screen,widget(screen,"recalculateButton"));
        check(editor.snapshot().boneKeys().get(1).value().placement().x()==.75f,"Recalculation must preserve placement");
        click(screen,widget(screen,"placeButton"));
        ((EditBox[])field(screen,"offsets"))[0].setValue("9");
        reveal(screen,widget(screen,"cancelPlaceButton"));
        click(screen,widget(screen,"cancelPlaceButton"));
        check(editor.state().boneBindings().equals(staged.boneBindings()) && !editor.state().visible() && !editor.dirty(),
                "Cancelling must restore the original saved binding and visibility");

        client.width=320; client.height=240;
        screen.initialize(320,240);
        layout=(PropTimelineLayout)field(screen,"layout");
        screen.mouseScrolled(layout.inspector.x()+10,layout.inspector.y()+30,0,1000);
        reveal(screen,widget(screen,"placeButton"));
        click(screen,widget(screen,"placeButton"));
        ((EditBox[])field(screen,"offsets"))[0].setValue(".2");
        reveal(screen,widget(screen,"writeButton"));
        render(screen,output.resolve("placement-320.png"));
        click(screen,widget(screen,"writeButton"));
        check(editor.state().boneBindings().get("Center").placement().x()==.2f,"Small GUI must support placement confirmation");
        screen.mouseScrolled(layout.inspector.x()+10,layout.inspector.y()+30,0,1000);
        int neededScroll=208+18-layout.properties.h();
        screen.mouseScrolled(layout.inspector.x()+10,layout.inspector.y()+30,0,-neededScroll/18.0);
        render(screen,output.resolve("attachment-320-scrolled.png"));
        var write = widget(screen,"writeButton");
        check(write.visible && write.getY()>=layout.properties.y() && write.getY()+write.getHeight()<=layout.properties.bottom(),
                "Small-window scrolling must expose the full key action");
        click(screen,widget(screen,"deleteButton"));
        check(!editor.hasKey(PropTimelineEditor.Track.ATTACHMENT),"Scrolled delete action must affect the selected key");
        screen.onClose();
        check(client.screen instanceof ConfirmScreen,"Unsaved draft must offer to discard or return");
        ((ConfirmScreen)client.screen).callback.accept(false);
        check(client.screen==screen && editor.dirty(),"Returning from discard prompt must keep the draft");
        screen.onClose();
        ((ConfirmScreen)client.screen).callback.accept(true);
        check(client.screen==parent && PropTimelinePreview.active==0,"Discarding must close preview resources");
        check(PropTimelineFiles.load(motion,config.heldPmxFile).attachments().size()==2,"Discarding must retain the saved file");
        check(PropTimelineFiles.load(motion,config.heldPmxFile).boneKeys().size()==2,"Discarding must retain both saved child-bone keys");
        Files.writeString(assets.resolve("actor.pmx"),"changed actor");
        client.width=854; client.height=480;
        var reopened=new PropTimelineScreen(parent,"probe",config);
        client.setScreen(reopened);
        var reopenedEditor=(PropTimelineEditor)field(reopened,"editor");
        check(reopenedEditor.compensationStale(),"Changed actor asset must invalidate saved compensation on reopen");
        click(reopened,widget(reopened,"saveButton"));
        check(!reopenedEditor.dirty(),"Save must recalculate stale compensation and update calibration");
        var calibration=com.shiroha.mmdskin.asset.catalog.PropTimelineCalibration.read(PropTimelineFiles.open(motion,config.heldPmxFile));
        check(calibration.actor().equals(com.shiroha.mmdskin.asset.catalog.PropTimelineCalibration.hash(assets.resolve("actor.pmx"))),
                "Recalibration must describe the new actor asset");
        reopened.onClose();
        System.out.println("PASS production timeline UI: scrub, bone picker, multi-bone registration, live placement, numeric validation, exact confirmation, resize/discard retention, placement cancel/save/recalculation, small-window placement/delete, lifecycle");
        System.out.println("PNG layout evidence: "+output+" (Minecraft widgets and native 3D preview are shimmed)");
    }

    private static Object field(Object target,String name) throws Exception {
        var field=target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static AbstractWidget widget(Object target,String name) throws Exception { return (AbstractWidget)field(target,name); }
    private static void reveal(PropTimelineScreen screen,AbstractWidget widget) throws Exception {
        var layout=(PropTimelineLayout)field(screen,"layout");
        int delta=widget.getY()<layout.properties.y()?widget.getY()-layout.properties.y()
                :Math.max(0,widget.getY()+widget.getHeight()-layout.properties.bottom());
        screen.mouseScrolled(layout.inspector.x()+10,layout.inspector.y()+30,0,-delta/18.0);
    }
    private static void click(Screen screen,AbstractWidget widget) {
        check(screen.mouseClicked(widget.getX()+widget.getWidth()/2.0,widget.getY()+widget.getHeight()/2.0,0),"Expected active widget: "+widget.getMessage());
        screen.mouseReleased(0,0,0);
        if (Minecraft.getInstance().screen==screen) {
            try (var graphics=new GuiGraphics(screen.width,screen.height)) { screen.render(graphics,0,0,0); }
        }
    }
    private static void render(Screen screen,Path path) throws Exception {
        try (var graphics=new GuiGraphics(screen.width,screen.height)) {
            screen.render(graphics,0,0,0);
            ImageIO.write(graphics.image,"png",path.toFile());
        }
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
