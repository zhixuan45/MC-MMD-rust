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
        render(screen,output.resolve("multi-bone-854.png"));

        client.width=320; client.height=240;
        screen.initialize(320,240);
        layout=(PropTimelineLayout)field(screen,"layout");
        int neededScroll=184+18-layout.properties.h();
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
        System.out.println("PASS production timeline UI: scrub, visibility, bone picker return, same-frame child registrations, compensation baseline order, v2 save, small-window delete, discard, preview lifecycle");
        System.out.println("PNG layout evidence: "+output+" (Minecraft widgets and native 3D preview are shimmed)");
    }

    private static Object field(Object target,String name) throws Exception {
        var field=target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static AbstractWidget widget(Object target,String name) throws Exception { return (AbstractWidget)field(target,name); }
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
