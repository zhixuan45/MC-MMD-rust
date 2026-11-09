"""Run the production timeline Screen with lightweight Minecraft UI shims.

This checks UI event routing and renders layout PNGs; native 3D rendering is explicitly stubbed.
"""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]

SHIMS = {
    "net/minecraft/network/chat/Component.java": r'''
package net.minecraft.network.chat;
import com.google.gson.*;
import java.nio.file.*;
public record Component(String value) {
    private static final JsonObject LANG;
    static {
        try { LANG = JsonParser.parseString(Files.readString(Path.of("common/src/main/resources/assets/mmdskin/lang/zh_cn.json"))).getAsJsonObject(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    public String getString() { return value; }
    public static Component literal(String value) { return new Component(value); }
    public static Component translatable(String key,Object... args) {
        return new Component(String.format(LANG.has(key)?LANG.get(key).getAsString():key,args));
    }
}
''',
    "net/minecraft/client/gui/Font.java": r'''
package net.minecraft.client.gui;
public class Font {
    public final int lineHeight=10;
    public final java.awt.Font awt=new java.awt.Font("Microsoft YaHei",java.awt.Font.PLAIN,10);
    public String plainSubstrByWidth(String text,int width) {
        var image=new java.awt.image.BufferedImage(1,1,2);
        var g=image.createGraphics(); g.setFont(awt);
        int n=text.length();
        while (n>0 && g.getFontMetrics().stringWidth(text.substring(0,n))>width) n--;
        g.dispose(); return text.substring(0,n);
    }
}
''',
    "net/minecraft/client/gui/GuiGraphics.java": r'''
package net.minecraft.client.gui;
import net.minecraft.network.chat.Component;
public final class GuiGraphics implements AutoCloseable {
    public final java.awt.image.BufferedImage image;
    private final java.awt.Graphics2D g;
    private final java.util.Deque<java.awt.Shape> clips=new java.util.LinkedList<>();
    public GuiGraphics(int w,int h) {
        image=new java.awt.image.BufferedImage(w,h,2); g=image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
    public void fill(int x,int y,int right,int bottom,int argb) {
        g.setColor(new java.awt.Color(argb,true)); g.fillRect(x,y,Math.max(0,right-x),Math.max(0,bottom-y));
    }
    public int drawString(Font font,String text,int x,int y,int argb,boolean shadow) {
        g.setFont(font.awt); g.setColor(new java.awt.Color(argb,true));
        g.drawString(text,x,y+9); return x+g.getFontMetrics().stringWidth(text);
    }
    public int drawString(Font font,Component text,int x,int y,int argb,boolean shadow) { return drawString(font,text.getString(),x,y,argb,shadow); }
    public void drawCenteredString(Font font,String text,int x,int y,int argb) {
        g.setFont(font.awt); drawString(font,text,x-g.getFontMetrics().stringWidth(text)/2,y,argb,false);
    }
    public void drawCenteredString(Font font,Component text,int x,int y,int argb) { drawCenteredString(font,text.getString(),x,y,argb); }
    public void enableScissor(int x,int y,int right,int bottom) { clips.push(g.getClip()); g.clipRect(x,y,right-x,bottom-y); }
    public void disableScissor() { g.setClip(clips.pop()); }
    public void flush() {}
    public void close() { g.dispose(); }
}
''',
    "net/minecraft/client/gui/components/AbstractWidget.java": r'''
package net.minecraft.client.gui.components;
import net.minecraft.client.gui.*;
import net.minecraft.network.chat.Component;
public class AbstractWidget {
    protected int x,y,w,h;
    protected Component message;
    public boolean visible=true,active=true;
    private boolean focused;
    public AbstractWidget(int x,int y,int w,int h,Component message) { this.x=x; this.y=y; this.w=w; this.h=h; this.message=message; }
    public int getX(){return x;} public int getY(){return y;} public int getHeight(){return h;} public int getWidth(){return w;}
    public void setY(int y){this.y=y;} public void setMessage(Component value){message=value;} public Component getMessage(){return message;}
    public boolean isFocused(){return focused;} public void setFocused(boolean value){focused=value;}
    public boolean mouseClicked(double px,double py,int button) {
        if (!visible || !active || px<x || px>=x+w || py<y || py>=y+h || button!=0) return false;
        press(); return true;
    }
    protected void press() {}
    public void render(GuiGraphics g,int mx,int my,float delta) {
        if (!visible) return;
        g.fill(x,y,x+w,y+h,active?0xff3a4b60:0xff263243);
        var font=new Font();
        g.drawCenteredString(font,font.plainSubstrByWidth(message.getString(),w-6),x+w/2,y+4,active?0xffe9f0fa:0xff7f92a9);
    }
}
''',
    "net/minecraft/client/gui/components/Button.java": r'''
package net.minecraft.client.gui.components;
import net.minecraft.network.chat.Component;
public final class Button extends AbstractWidget {
    private final java.util.function.Consumer<Button> action;
    private Button(int x,int y,int w,int h,Component text,java.util.function.Consumer<Button> action) { super(x,y,w,h,text); this.action=action; }
    protected void press(){action.accept(this);}
    public static Builder builder(Component text,java.util.function.Consumer<Button> action){return new Builder(text,action);}
    public static final class Builder {
        final Component text; final java.util.function.Consumer<Button> action; int x,y,w,h;
        Builder(Component text,java.util.function.Consumer<Button> action){this.text=text;this.action=action;}
        public Builder bounds(int x,int y,int w,int h){this.x=x;this.y=y;this.w=w;this.h=h;return this;}
        public Button build(){return new Button(x,y,w,h,text,action);}
    }
}
''',
    "net/minecraft/client/gui/components/EditBox.java": r'''
package net.minecraft.client.gui.components;
import net.minecraft.client.gui.*;
import net.minecraft.network.chat.Component;
public final class EditBox extends AbstractWidget {
    private String value="";
    private int max=256;
    private java.util.function.Consumer<String> responder=v->{};
    private java.util.function.Predicate<String> filter=v->true;
    public EditBox(Font f,int x,int y,int w,int h,Component label){super(x,y,w,h,label);}
    public void setMaxLength(int max){this.max=max;}
    public void setFilter(java.util.function.Predicate<String> filter){this.filter=filter;}
    public void setResponder(java.util.function.Consumer<String> responder){this.responder=responder;}
    public String getValue(){return value;}
    public void setValue(String value){if(filter.test(value)){this.value=value.substring(0,Math.min(max,value.length()));responder.accept(this.value);}}
    public void render(GuiGraphics g,int mx,int my,float delta){
        if(!visible)return;
        g.fill(x,y,x+w,y+h,isFocused()?0xff89b7e5:0xff5c6b7f);
        g.fill(x+1,y+1,x+w-1,y+h-1,0xff101823);
        var font=new Font();g.drawString(font,font.plainSubstrByWidth(value,w-6),x+3,y+4,0xffe3ebf7,false);
    }
}
''',
    "net/minecraft/client/gui/screens/Screen.java": r'''
package net.minecraft.client.gui.screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
public class Screen {
    public int width,height;
    protected Font font=new Font();
    protected Minecraft minecraft=Minecraft.getInstance();
    protected final Component title;
    private final java.util.List<AbstractWidget> children=new java.util.ArrayList<>(),renderables=new java.util.ArrayList<>();
    private AbstractWidget focused;
    public static boolean control,shift;
    public Screen(Component title){this.title=title;}
    public final void initialize(int w,int h){width=w;height=h;init();}
    protected void init(){}
    protected void clearWidgets(){children.clear();renderables.clear();setFocused(null);}
    protected <T extends AbstractWidget> T addWidget(T w){children.add(w);return w;}
    protected <T extends AbstractWidget> T addRenderableWidget(T w){addWidget(w);renderables.add(w);return w;}
    public java.util.List<AbstractWidget> children(){return children;}
    public AbstractWidget getFocused(){return focused;}
    public void setFocused(AbstractWidget w){if(focused!=null)focused.setFocused(false);focused=w;if(w!=null)w.setFocused(true);}
    public void setInitialFocus(AbstractWidget w){setFocused(w);}
    public void setDragging(boolean value){}
    public void render(GuiGraphics g,int x,int y,float d){for(var w:renderables)w.render(g,x,y,d);}
    public boolean mouseClicked(double x,double y,int b){for(var w:children)if(w.mouseClicked(x,y,b)){setFocused(w);return true;}return false;}
    public boolean mouseDragged(double x,double y,int b,double dx,double dy){return false;}
    public boolean mouseReleased(double x,double y,int b){return false;}
    public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return false;}
    public boolean keyPressed(int key,int scan,int modifiers){if(key==256){onClose();return true;}return false;}
    public void onClose(){minecraft.setScreen(null);}
    public void removed(){}
    public boolean isPauseScreen(){return false;}
    public static boolean hasControlDown(){return control;}public static boolean hasShiftDown(){return shift;}
}
''',
    "net/minecraft/client/gui/screens/ConfirmScreen.java": r'''
package net.minecraft.client.gui.screens;
import net.minecraft.network.chat.Component;
public final class ConfirmScreen extends Screen {
    public final java.util.function.Consumer<Boolean> callback;
    public ConfirmScreen(java.util.function.Consumer<Boolean> callback,Component title,Component body,Component yes,Component no){
        super(title);this.callback=callback;
    }
}
''',
    "net/minecraft/client/Minecraft.java": r'''
package net.minecraft.client;
import net.minecraft.client.gui.screens.Screen;
public final class Minecraft {
    private static final Minecraft INSTANCE=new Minecraft();
    public Screen screen;
    public int width=854,height=480;
    public static Minecraft getInstance(){return INSTANCE;}
    public void setScreen(Screen value){if(screen!=null)screen.removed();screen=value;if(value!=null)value.initialize(width,height);}
}
''',
    "org/lwjgl/glfw/GLFW.java": r'''
package org.lwjgl.glfw;
public final class GLFW {
    public static final int GLFW_KEY_ESCAPE=256,GLFW_KEY_S=83,GLFW_KEY_LEFT=263,GLFW_KEY_RIGHT=262,
        GLFW_KEY_HOME=268,GLFW_KEY_END=269,GLFW_KEY_SPACE=32,GLFW_KEY_DELETE=261;
}
''',
    "com/shiroha/mmdskin/config/PathConstants.java": r'''
package com.shiroha.mmdskin.config;
public final class PathConstants {
    public static java.io.File getModelDir(String name){return new java.io.File(System.getProperty("probe.assets"));}
    public static java.io.File getStageAnimDir(){return new java.io.File(System.getProperty("probe.assets"),"StageAnim");}
    public static boolean ensureDirectoryExists(java.io.File dir){return dir.isDirectory() || dir.mkdirs();}
}
''',
    "com/shiroha/mmdskin/ui/selector/ModelSelectorServices.java": r'''
package com.shiroha.mmdskin.ui.selector;
public final class ModelSelectorServices {
    public static ModelSelectorServices modelSettings(){return new ModelSelectorServices();}
    public java.util.List<String> listBoneNames(String model){return java.util.List.of("右ダミー","左ダミー","頭");}
}
''',
    "com/shiroha/mmdskin/asset/catalog/HeldPmxAssets.java": r'''
package com.shiroha.mmdskin.asset.catalog;
public final class HeldPmxAssets {
    public static ModelCatalogEntry resolve(String owner,String reference) {
        return new ModelCatalogEntry(owner,motionDirectory(owner,reference).resolve(fileName(reference)).toString());
    }
    public static java.nio.file.Path motionDirectory(String owner,String reference) {
        return com.shiroha.mmdskin.config.PathConstants.getModelDir(owner).toPath();
    }
    public static String fileName(String reference){return reference.substring(reference.indexOf('/')+1);}
}
''',
    "com/shiroha/mmdskin/asset/catalog/ModelCatalogEntry.java": r'''
package com.shiroha.mmdskin.asset.catalog;
public record ModelCatalogEntry(String folder,String path) {
    public String getFolderName(){return folder;}
    public String getModelFilePath(){return path;}
    public static ModelCatalogEntry resolveModelFile(String owner,String variant) {
        return new ModelCatalogEntry(owner,com.shiroha.mmdskin.config.PathConstants.getModelDir(owner).toPath().resolve("actor.pmx").toString());
    }
}
''',
    "com/shiroha/mmdskin/config/ModelConfigManager.java": r'''
package com.shiroha.mmdskin.config;
public final class ModelConfigManager {
    public static ModelConfigData getConfig(String name){return new ModelConfigData();}
}
''',
    "com/shiroha/mmdskin/ui/selector/timeline/PropTimelinePreview.java": r'''
package com.shiroha.mmdskin.ui.selector.timeline;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import net.minecraft.client.gui.*;
public final class PropTimelinePreview implements AutoCloseable {
    public static int active;
    private boolean closed;
    public PropTimelinePreview(String name,ModelConfigData c,java.nio.file.Path prop,java.nio.file.Path actor){active++;}
    public boolean prepare(int frame){return true;}
    public float endFrame(){return 900;}
    public java.util.List<String> bones(){return java.util.List.of("右ダミー","左ダミー","頭");}
    public java.util.List<String> propBones(){return java.util.List.of("Root","Center","FingerPivot");}
    public static PropTimeline lastBaseline;
    public static PropTimeline.State lastState;
    public static int registrations;
    public PropTimeline.BoneBinding register(int frame,PropTimeline baseline,String child,String target,PropTimeline.Offset offset,boolean keepWorld){
        return register(frame,baseline,child,target,offset,keepWorld,null);
    }
    public PropTimeline.BoneBinding register(int frame,PropTimeline baseline,String child,String target,PropTimeline.Offset offset,boolean keepWorld,PropTimeline.Offset placement){
        lastBaseline=baseline;
        registrations++;
        return new PropTimeline.BoneBinding(child,target,offset,PropTimeline.identityCompensation(),keepWorld,false,placement);
    }
    public String missingBone(){return "";}
    public void render(GuiGraphics g,int x,int y,int w,int h,int frame,long revision,PropTimeline.State state,float yaw,float pitch,float zoom,PropTimeline timeline){
        lastState=state;
        var font=new Font();
        g.drawCenteredString(font,"UI probe: 3D preview stub",x+w/2,y+h/2-12,0xff91a6bd);
        g.drawCenteredString(font,"Frame "+frame+" / "+(state.visible()?"Shown":"Hidden"),x+w/2,y+h/2+2,0xffc6d9ef);
    }
    public void close(){if(!closed){active--;closed=true;}}
}
''',
}

SOURCES = [
    "config/ModelConfigData.java",
    "asset/catalog/HeldPmxMotionFiles.java",
    "asset/catalog/PropTimelineFiles.java",
    "asset/catalog/PropTimelineCalibration.java",
    "stage/domain/timeline/PropTimeline.java",
    "ui/chrome/TranslucentTrayChrome.java",
    "ui/selector/HeldPmxSelectionScreen.java",
    "ui/selector/PropTimelineLayout.java",
    "ui/selector/PropTimelineScreen.java",
    "ui/selector/timeline/PropTimelineEditor.java",
    "ui/selector/timeline/TimelineMotionChoices.java",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, default=Path(r"C:\Program Files\Zulu\zulu-21"))
    args = parser.parse_args()
    output = ROOT / "build/timeline-editor-probe"
    source = output / "src"
    classes = output / "classes"
    source.mkdir(parents=True, exist_ok=True)
    classes.mkdir(exist_ok=True)
    shims = []
    for name, text in SHIMS.items():
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        shims.append(str(path))
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    jars = []
    for group, name in [("com.google.code.gson", "gson"), ("org.apache.logging.log4j", "log4j-api"),
                        ("org.apache.logging.log4j", "log4j-core")]:
        candidates = [p for p in (cache / group / name).rglob("*.jar") if not any(s in p.name for s in ("sources", "javadoc"))]
        if not candidates:
            raise SystemExit(f"Missing cached dependency: {name}")
        jars.append(str(max(candidates, key=lambda p: tuple(int(v) for v in p.parents[1].name.split(".") if v.isdecimal()))))
    classpath = ";".join(jars)
    production = ROOT / "common/src/main/java/com/shiroha/mmdskin"
    subprocess.run([str(args.jdk / "bin/javac.exe"), "-proc:none", "-encoding", "UTF-8", "-cp", classpath,
                    "-d", str(classes), *shims, *[str(production / p) for p in SOURCES],
                    str(Path(__file__).with_name("Probe.java"))], cwd=ROOT, check=True)
    subprocess.run([str(args.jdk / "bin/java.exe"), "-Djava.awt.headless=true", "-cp", f"{classes};{classpath}",
                    "com.shiroha.mmdskin.ui.selector.Probe", str(output)], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
