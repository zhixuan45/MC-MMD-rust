package com.shiroha.mmdskin.ui.selector;

import com.shiroha.mmdskin.render.effectpack.EffectPackRuntime;
import com.shiroha.mmdskin.render.effectpack.FxPackLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** 效果库选择、异步资源导入和逐目标配置。 */
public final class EffectPackScreen extends Screen {
    private final Screen parent;
    private final EffectPackRuntime runtime = EffectPackRuntime.get();
    private EditBox importPath, characters, scenes;
    private int page;
    private boolean importing;
    private String status = "导入 ZIP、FX 或资源目录；点击目标按钮启用/关闭。";

    public EffectPackScreen(Screen parent) { super(Component.literal("MMD 效果包")); this.parent = parent; }

    @Override protected void init() {
        int left = Math.max(8, (width - 420) / 2), panel = Math.min(420, width - 16);
        importPath = new EditBox(font, left, 30, panel - 62, 20, Component.literal("ZIP / FX / 资源目录路径"));
        importPath.setMaxLength(2048);
        addRenderableWidget(importPath);
        addRenderableWidget(Button.builder(Component.literal("导入"), b -> importPack()).bounds(left + panel - 58, 30, 58, 20).build());
        characters = new EditBox(font, left + 78, 56, (panel - 88) / 2, 18, Component.literal("人物模型目录名，* 表示全部"));
        characters.setValue(runtime.settings().characterModel);
        addRenderableWidget(characters);
        scenes = new EditBox(font, left + 88 + (panel - 88) / 2, 56, (panel - 88) / 2, 18, Component.literal("场景模型目录名，* 表示全部"));
        scenes.setValue(runtime.settings().sceneModel);
        addRenderableWidget(scenes);
        int perPage = rows(), from = page * perPage;
        var effects = runtime.scan().effects();
        if (from >= effects.size() && page > 0) { page = 0; rebuildWidgets(); return; }
        for (int i = from; i < Math.min(from + perPage, effects.size()); i++) {
            var effect = effects.get(i);
            int y = 94 + (i - from) * 24;
            for (int target = 0; target < 3; target++) {
                final int slot = target;
                String selected = target == 0 ? runtime.settings().characters : target == 1 ? runtime.settings().scenes : runtime.settings().world;
                String label = (target == 0 ? "人物" : target == 1 ? "场景" : "世界") + (selected.equals(effect.id()) ? " ✓" : "");
                Button button = Button.builder(Component.literal(label), b -> {
                    applySelectors();
                    String id = selected.equals(effect.id()) ? "" : effect.id();
                    if (slot == 0) runtime.settings().characters = id;
                    else if (slot == 1) runtime.settings().scenes = id;
                    else runtime.settings().world = id;
                    runtime.save();
                    status = String.join(" ", effect.notes());
                    rebuildWidgets();
                }).bounds(left + panel - 174 + target * 58, y, 56, 20)
                        .tooltip(Tooltip.create(Component.literal(effect.id() + "\n" + String.join("\n", effect.notes())))).build();
                button.active = target != 2 || effect.supportsWorld();
                addRenderableWidget(button);
            }
        }
        int bottom = height - 29;
        int cell = (panel - 20) / 6;
        addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { applySelectors(); page = Math.max(0, page - 1); rebuildWidgets(); }).bounds(left, bottom, cell, 20).build());
        addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { applySelectors(); if ((page + 1) * rows() < effects.size()) page++; rebuildWidgets(); }).bounds(left + cell + 4, bottom, cell, 20).build());
        addRenderableWidget(Button.builder(Component.literal("重载"), b -> { applySelectors(); runtime.reload(); com.shiroha.mmdskin.render.effectpack.WorldColorEffect.release(); status = "已重载；兼容性报告位于效果包内 import-report.json"; rebuildWidgets(); }).bounds(left + (cell + 4) * 2, bottom, cell, 20).build());
        addRenderableWidget(Button.builder(Component.literal("诊断"), b -> {
            var lines = new java.util.ArrayList<>(runtime.problems());
            for (var failure : runtime.scan().failures()) lines.add(failure.file() + ": " + failure.reason());
            status = lines.isEmpty() ? "没有导入或运行错误。实际显示仍需匹配模型 UV。" : String.join(" | ", lines);
        }).bounds(left + (cell + 4) * 3, bottom, cell, 20).build());
        addRenderableWidget(Button.builder(Component.literal("打开目录"), b -> {
            try { java.nio.file.Files.createDirectories(runtime.library()); net.minecraft.Util.getPlatform().openFile(runtime.library().toFile()); }
            catch (java.io.IOException e) { status = e.getMessage(); }
        }).bounds(left + (cell + 4) * 4, bottom, cell, 20).build());
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose()).bounds(left + (cell + 4) * 5, bottom, cell, 20).build());
    }

    private int rows() { return Math.max(1, (height - 178) / 24); }
    private void applySelectors() {
        if (characters != null) runtime.settings().characterModel = characters.getValue().trim();
        if (scenes != null) runtime.settings().sceneModel = scenes.getValue().trim();
    }

    private void importPack() {
        if (importing || importPath.getValue().isBlank()) return;
        String path = importPath.getValue().trim();
        if (path.startsWith("\"") && path.endsWith("\"")) path = path.substring(1, path.length() - 1);
        final String input = path;
        importing = true;
        status = "正在导入…";
        CompletableFuture.supplyAsync(() -> {
            try { return "已导入 " + FxPackLibrary.importPack(Path.of(input), runtime.library()).getFileName(); }
            catch (Exception e) { return "导入失败: " + e.getMessage(); }
        }).thenAccept(message -> Minecraft.getInstance().execute(() -> {
            importing = false;
            if (Minecraft.getInstance().screen == this) applySelectors();
            runtime.reload();
            status = message + "；支持 " + runtime.scan().effects().size() + " 个效果，未适配 " + runtime.scan().failures().size() + " 个。";
            if (Minecraft.getInstance().screen == this) rebuildWidgets();
        }));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = Math.max(8, (width - 420) / 2), panel = Math.min(420, width - 16);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xffffff);
        graphics.drawString(font, "模型范围：", left, 61, 0xdddddd);
        graphics.drawString(font, "人物目录名                     场景目录名（* = 全部）", left + 78, 80, 0xaaaaaa);
        var effects = runtime.scan().effects();
        for (int i = page * rows(); i < Math.min((page + 1) * rows(), effects.size()); i++) {
            String label = effects.get(i).name();
            graphics.drawString(font, font.plainSubstrByWidth(label, Math.max(16, panel - 180)), left, 100 + (i - page * rows()) * 24, 0xffffff);
        }
        var lines = font.split(Component.literal(status), panel);
        for (int i = 0; i < Math.min(2, lines.size()); i++) graphics.drawString(font, lines.get(i), left, height - 55 + i * 10, 0xcccccc);
    }
    @Override public void onClose() { applySelectors(); runtime.save(); Minecraft.getInstance().setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
