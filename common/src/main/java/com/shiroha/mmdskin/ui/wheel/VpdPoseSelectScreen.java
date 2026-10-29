package com.shiroha.mmdskin.ui.wheel;

import com.shiroha.mmdskin.asset.catalog.MorphInfo;
import com.shiroha.mmdskin.expression.ExpressionSelectionCodec;
import com.shiroha.mmdskin.ui.chrome.TranslucentTrayChrome;
import com.shiroha.mmdskin.ui.config.ModelSelectorConfig;
import com.shiroha.mmdskin.ui.config.MorphWheelConfigScreen;
import com.shiroha.mmdskin.ui.wheel.service.MorphOption;
import com.shiroha.mmdskin.ui.wheel.service.MorphWheelService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Direct VPD selection, including bone-only poses; existing wheel configuration stays accessible. */
public final class VpdPoseSelectScreen extends Screen {
    private final Screen parent;
    private final MorphWheelService service;
    private final List<MorphInfo> files;
    private int scroll;

    public VpdPoseSelectScreen(Screen parent, MorphWheelService service) {
        super(Component.translatable("gui.mmdskin.load_vpd_pose"));
        this.parent = parent;
        this.service = service;
        files = MorphInfo.scanMorphsForModel(ModelSelectorConfig.getInstance().getSelectedModel());
    }
    @Override protected void init() {
        int x=width/2-120;
        addRenderableWidget(Button.builder(Component.translatable("gui.mmdskin.reset_pose"), b -> {
            service.selectMorph(new MorphOption("", "", null,ExpressionSelectionCodec.RESET_POSE_TOKEN,true));
            onClose();
        }).bounds(x,height-52,116,20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.mmdskin.morph_config"), b -> minecraft.setScreen(new MorphWheelConfigScreen(this)))
                .bounds(x+124,height-52,116,20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x,height-28,240,20).build());
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float tick) {
        TranslucentTrayChrome.drawOverlay(graphics,width,height);
        int x=width/2-120;
        graphics.drawCenteredString(font,title,width/2,12,0xffffffff);
        if (files.isEmpty()) graphics.drawCenteredString(font,Component.translatable("gui.mmdskin.vpd_empty"),width/2,40,0xffcbd5e1);
        int rows=Math.max(0,(height-94)/20);
        for (int row=0;row<rows && scroll+row<files.size();row++) {
            int y=34+row*20;
            boolean hover=mouseX>=x && mouseX<x+240 && mouseY>=y && mouseY<y+18;
            graphics.fill(x,y,x+240,y+18,hover?0x9065a9d8:0x50334252);
            graphics.drawString(font,font.plainSubstrByWidth(files.get(scroll+row).getFileName(),228),x+6,y+5,0xffeeeeee,false);
        }
        super.render(graphics,mouseX,mouseY,tick);
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if (super.mouseClicked(x,y,button)) return true;
        int row=(int)((y-34)/20), rows=Math.max(0,(height-94)/20);
        if (button==0 && x>=width/2-120 && x<width/2+120 && y>=34 && row<rows && scroll+row<files.size()) {
            MorphInfo file=files.get(scroll+row);
            service.selectMorph(new MorphOption(file.getDisplayName(),file.getMorphName(),file.getFilePath(),"vpd:"+file.getMorphName(),false));
            onClose();
            return true;
        }
        return false;
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        scroll=Math.max(0,Math.min(Math.max(0,files.size()-Math.max(0,(height-94)/20)),scroll-(int)Math.signum(vertical)));
        return true;
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
