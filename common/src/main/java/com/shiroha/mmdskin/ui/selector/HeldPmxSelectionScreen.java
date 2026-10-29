package com.shiroha.mmdskin.ui.selector;

import com.shiroha.mmdskin.ui.chrome.TranslucentTrayChrome;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Filterable choices, with optional manual bone names for a model that is not yet loaded. */
final class HeldPmxSelectionScreen extends Screen {
    private final Screen parent;
    private final List<String> choices;
    private final Component defaultLabel;
    private final Component hint;
    private final Consumer<String> selected;
    private final boolean allowCustom;
    private final String current;
    private EditBox filter;
    private List<String> visible = List.of();
    private int scroll, left, boxWidth, listTop, listBottom;

    HeldPmxSelectionScreen(Screen parent, Component title, Component hint, Component defaultLabel,
                           List<String> choices, String current, boolean allowCustom, Consumer<String> selected) {
        super(title);
        this.parent=parent; this.hint=hint; this.defaultLabel=defaultLabel;
        this.choices=List.copyOf(choices); this.current=current; this.allowCustom=allowCustom; this.selected=selected;
    }

    @Override protected void init() {
        boxWidth=Math.max(120,Math.min(320,width-24)); left=(width-boxWidth)/2;
        filter=new EditBox(font,left,30,boxWidth,20,Component.translatable("gui.mmdskin.prop_selection.filter"));
        filter.setMaxLength(256);
        filter.setResponder(value -> rebuild());
        addRenderableWidget(filter);
        listTop=72; listBottom=Math.max(listTop,height-58);
        if (allowCustom) {
            addRenderableWidget(Button.builder(Component.translatable("gui.mmdskin.prop_selection.use_name"),b->choose(filter.getValue().trim()))
                    .bounds(left,height-50,boxWidth,20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),b->onClose()).bounds(left,height-26,boxWidth,20).build());
        rebuild();
        setInitialFocus(filter);
    }

    private void rebuild() {
        String search=filter.getValue().toLowerCase(java.util.Locale.ROOT);
        var next=new ArrayList<String>(); next.add("");
        for (String choice:choices) if (!choice.isEmpty() && choice.toLowerCase(java.util.Locale.ROOT).contains(search)) next.add(choice);
        visible=List.copyOf(next); scroll=0;
    }
    private int rows() { return Math.max(0,(listBottom-listTop)/20); }
    private void choose(String value) { selected.accept(value); onClose(); }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float delta) {
        TranslucentTrayChrome.drawOverlay(graphics,width,height);
        graphics.drawCenteredString(font,title,width/2,12,0xffffffff);
        graphics.drawString(font,font.plainSubstrByWidth(hint.getString(),boxWidth),left,56,0xffaebdca,false);
        graphics.enableScissor(left,listTop,left+boxWidth,listBottom);
        for (int row=0;row<rows() && row+scroll<visible.size();row++) {
            String value=visible.get(row+scroll); int y=listTop+row*20;
            boolean hover=mouseX>=left && mouseX<left+boxWidth && mouseY>=y && mouseY<y+18;
            graphics.fill(left,y,left+boxWidth,y+18,hover?0x9065a9d8:value.equals(current)?0x80527896:0x50334252);
            String text=value.isEmpty()?defaultLabel.getString():value;
            graphics.drawString(font,font.plainSubstrByWidth(text,boxWidth-12),left+6,y+5,0xffeeeeee,false);
        }
        graphics.disableScissor();
        super.render(graphics,mouseX,mouseY,delta);
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if (super.mouseClicked(x,y,button)) return true;
        int row=(int)((y-listTop)/20);
        if (button==0 && x>=left && x<left+boxWidth && y>=listTop && y<listBottom
                && row<rows() && row+scroll<visible.size()) { choose(visible.get(row+scroll)); return true; }
        return false;
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        scroll=Math.max(0,Math.min(Math.max(0,visible.size()-rows()),scroll-(int)Math.signum(vertical)));
        return true;
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
