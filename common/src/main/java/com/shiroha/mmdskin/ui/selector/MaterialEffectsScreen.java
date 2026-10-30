package com.shiroha.mmdskin.ui.selector;

import com.shiroha.mmdskin.render.effectpack.EffectPackRuntime;
import com.shiroha.mmdskin.model.runtime.ModelRequestKey;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.scene.client.SceneModelManager;
import com.shiroha.mmdskin.ui.selector.application.MaterialVisibilityApplicationService;
import com.shiroha.mmdskin.ui.selector.application.MaterialVisibilityApplicationService.MaterialEntryState;
import com.shiroha.mmdskin.ui.selector.application.MaterialVisibilityApplicationService.MaterialScreenContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** 从已加载的模型读取真实材质列表，只编辑效果绑定，不改变材质可见性。 */
public final class MaterialEffectsScreen extends Screen {
    private static final MaterialVisibilityApplicationService MATERIALS = ModelSelectorServices.materialVisibility();
    private final Screen parent;
    private final EffectPackRuntime runtime = EffectPackRuntime.get();
    private boolean placed;
    private int page;
    private MaterialScreenContext context;
    private List<MaterialEntryState> materials = List.of();
    private String status = "每个材质可选择任意已导入包的 FX；继承、关闭和独立效果分别保存。";

    public MaterialEffectsScreen(Screen parent) { super(Component.literal("材质效果组合")); this.parent = parent; loadModel(); }

    private void loadModel() {
        context = null; materials = List.of();
        if (placed) {
            String name = SceneModelManager.getInstance().getSceneModelName();
            if (name != null) {
                var model = ClientRenderRuntime.get().modelRepository().acquire(ModelRequestKey.scene(name, name));
                if (model != null && model.modelInstance() != null)
                    context = new MaterialScreenContext(model.modelInstance().getModelHandle(), name, name);
            }
        } else context = MATERIALS.createPlayerContext().orElse(null);
        if (context != null) materials = MATERIALS.loadMaterials(context);
        status = context == null ? (placed ? "请先放置并加载一个场景。" : "请先选择并加载一个角色模型。")
                : "每个材质可选择任意已导入包的 FX；继承、关闭和独立效果分别保存。";
    }

    @Override protected void init() {
        int left = left(), panel = panel();
        addRenderableWidget(Button.builder(Component.literal((!placed ? "✓ " : "") + "角色"), b -> switchTarget(false)).bounds(left, 30, 72, 20).build());
        addRenderableWidget(Button.builder(Component.literal((placed ? "✓ " : "") + "场景"), b -> switchTarget(true)).bounds(left + 76, 30, 72, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新材质"), b -> { loadModel(); page = 0; rebuildWidgets(); }).bounds(left + panel - 80, 30, 80, 20).build());
        if (page * rows() >= materials.size()) page = 0;
        for (int i = page * rows(); i < Math.min((page + 1) * rows(), materials.size()); i++) {
            var material = materials.get(i);
            var rule = runtime.settings().rule(placed, context.modelName(), material.index(), material.name());
            String label = rule == null ? "继承整模默认" : rule.effect().isEmpty() ? "关闭效果" : effectLabel(rule.effect());
            int buttonWidth = panel / 2;
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(label, buttonWidth - 12)), b -> {
                Minecraft.getInstance().setScreen(new MaterialFxPickerScreen(this, material));
            }).bounds(left + panel - buttonWidth, 82 + (i - page * rows()) * 24, buttonWidth, 20)
                    .tooltip(Tooltip.create(Component.literal(label + (rule == null ? "" : "\n" + rule.effect())))).build());
        }
        int cell = (panel - 12) / 4, y = height - 28;
        addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { page = Math.max(0, page - 1); rebuildWidgets(); }).bounds(left, y, cell, 20).build());
        addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { if ((page + 1) * rows() < materials.size()) page++; rebuildWidgets(); }).bounds(left + cell + 4, y, cell, 20).build());
        addRenderableWidget(Button.builder(Component.literal("全部继承"), b -> {
            if (context != null) {
                (placed ? runtime.settings().sceneMaterials : runtime.settings().characterMaterials).remove(context.modelName());
                runtime.save(); rebuildWidgets();
            }
        }).bounds(left + (cell + 4) * 2, y, cell, 20).build());
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose()).bounds(left + (cell + 4) * 3, y, cell, 20).build());
    }

    private void switchTarget(boolean scene) { placed = scene; page = 0; loadModel(); rebuildWidgets(); }
    private int panel() { return Math.min(500, width - 20); }
    private int left() { return (width - panel()) / 2; }
    private int rows() { return Math.max(1, (height - 146) / 24); }
    private String effectLabel(String id) {
        var effect = runtime.effect(id);
        return effect == null ? "未加载：" + id : effect.name() + " · " + id.substring(0, id.indexOf('/'));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick); super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xffffff);
        String name = context == null ? "没有已加载的模型" : context.modelName() + "（" + materials.size() + " 个材质）";
        graphics.drawString(font, font.plainSubstrByWidth(name, panel()), left(), 59, 0xdddddd);
        for (int i = page * rows(); i < Math.min((page + 1) * rows(), materials.size()); i++) {
            var material = materials.get(i);
            graphics.drawString(font, font.plainSubstrByWidth("#" + material.index() + " " + material.name(), panel() / 2 - 8),
                    left(), 88 + (i - page * rows()) * 24, 0xffffff);
        }
        graphics.drawString(font, font.plainSubstrByWidth(status, panel()), left(), height - 46, 0xaaaaaa);
    }
    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private static final class MaterialFxPickerScreen extends Screen {
        private final MaterialEffectsScreen owner;
        private final MaterialEntryState material;
        private int page;
        MaterialFxPickerScreen(MaterialEffectsScreen owner, MaterialEntryState material) {
            super(Component.literal("#" + material.index() + " " + material.name())); this.owner = owner; this.material = material;
        }
        private int rows() { return Math.max(1, (height - 120) / 24); }
        @Override protected void init() {
            int panel = Math.min(500, width - 20), left = (width - panel) / 2, half = (panel - 4) / 2;
            addRenderableWidget(Button.builder(Component.literal("继承整模默认"), b -> choose(null)).bounds(left, 32, half, 20).build());
            addRenderableWidget(Button.builder(Component.literal("关闭这个材质的效果"), b -> choose("")).bounds(left + half + 4, 32, half, 20).build());
            var effects = owner.runtime.scan().effects();
            for (int i = page * rows(); i < Math.min((page + 1) * rows(), effects.size()); i++) {
                var effect = effects.get(i);
                String label = owner.effectLabel(effect.id());
                addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(label, panel - 12)), b -> choose(effect.id()))
                        .bounds(left, 62 + (i - page * rows()) * 24, panel, 20)
                        .tooltip(Tooltip.create(Component.literal(effect.id() + "\n" + String.join("\n", effect.notes())))).build());
            }
            int cell = (panel - 8) / 3;
            addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { page = Math.max(0, page - 1); rebuildWidgets(); }).bounds(left, height - 28, cell, 20).build());
            addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { if ((page + 1) * rows() < effects.size()) page++; rebuildWidgets(); }).bounds(left + cell + 4, height - 28, cell, 20).build());
            addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose()).bounds(left + (cell + 4) * 2, height - 28, cell, 20).build());
        }
        private void choose(String effect) {
            owner.runtime.settings().bind(owner.placed, owner.context.modelName(), material.index(), material.name(), effect);
            owner.runtime.save(); onClose();
        }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderBackground(graphics, mouseX, mouseY, partialTick); super.render(graphics, mouseX, mouseY, partialTick);
            graphics.drawCenteredString(font, font.plainSubstrByWidth(title.getString(), width - 20), width / 2, 12, 0xffffff);
        }
        @Override public void onClose() { Minecraft.getInstance().setScreen(owner); }
        @Override public boolean isPauseScreen() { return false; }
    }
}
