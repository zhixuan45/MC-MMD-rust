package com.shiroha.mmdskin.ui.wheel;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.shiroha.mmdskin.ui.chrome.TranslucentTrayChrome;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.util.List;

/** 轮盘界面基类，负责原生 GuiGraphics 轮盘渲染与交互动画。 */
public abstract class AbstractWheelScreen extends Screen {

    public record WheelStyle(
            float screenRatio, float innerRatio,
            int lineColor, int lineColorDim, int highlightColor,
            int centerBg, int centerBorder, int textShadow
    ) {}

    public record WheelEntry(Component primaryText, Component secondaryText) {}

    private static final int TEXT_PRIMARY = TranslucentTrayChrome.TITLE_TEXT;
    private static final int TEXT_SECONDARY = TranslucentTrayChrome.BODY_TEXT;
    private static final int TEXT_MUTED = TranslucentTrayChrome.SUBTITLE_TEXT;
    private static final int OUTLINE_DIM = 0x26FFFFFF;
    private static final int BACKDROP_DIM = TranslucentTrayChrome.OVERLAY;
    private static final float SEGMENT_GAP_DEGREES = 1.8f;
    private static final float CENTER_BUBBLE_SCALE = 0.74f;
    private static final float MIN_TEXT_SCALE = 0.62f;
    private static final float TEXT_PADDING = 2.0f;

    protected final WheelStyle style;
    protected int centerX;
    protected int centerY;
    protected int outerRadius;
    protected int innerRadius;
    protected int selectedSlot = -1;

    private float[] slotPop = new float[0];
    private float[] slotVelocity = new float[0];
    private float centerPop;
    private float centerVelocity;
    private float openProgress;
    private float openVelocity;
    private List<WheelEntry> renderedEntries = List.of();

    protected AbstractWheelScreen(Component title, WheelStyle style) {
        super(title);
        this.style = style;
    }

    protected static WheelStyle createTranslucentWheelStyle(float screenRatio, float innerRatio) {
        return new WheelStyle(
                screenRatio,
                innerRatio,
                0xFFF2F5F8,
                0xB8D5DCE3,
                0x30FFFFFF,
                0xFF20262D,
                0xFFDDE3E9,
                0xD0000000
        );
    }

    protected abstract int getSlotCount();

    protected void initWheelLayout() {
        this.centerX = this.width / 2;
        this.centerY = this.height / 2 + Math.round(this.height * 0.02f);
        int minDim = Math.min(this.width, this.height);
        this.outerRadius = (int) (minDim * style.screenRatio() / 2);
        this.innerRadius = (int) (this.outerRadius * style.innerRatio());
    }

    protected void updateSelectedSlot(int mouseX, int mouseY) {
        int count = getSlotCount();
        if (count <= 0) {
            selectedSlot = -1;
            return;
        }

        int dx = mouseX - centerX;
        int dy = mouseY - centerY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        float minRadius = innerRadius * 0.72f;
        float maxRadius = outerRadius + 50.0f;
        if (distance < minRadius || distance > maxRadius) {
            selectedSlot = -1;
            return;
        }

        double angle = Math.toDegrees(Math.atan2(dy, dx));
        if (angle < 0) {
            angle += 360;
        }
        angle = (angle + 90.0) % 360.0;

        double segmentAngle = 360.0 / count;
        selectedSlot = (int) (angle / segmentAngle) % count;
    }

    protected void renderWheelBase(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, List<WheelEntry> entries) {
        renderedEntries = entries;
        updateSelectedSlot(mouseX, mouseY);
        updateAnimations(entries.size());
        renderBackdrop(guiGraphics);
        if (!entries.isEmpty()) {
            renderSegmentWheel(guiGraphics, entries);
        }
        renderCenterDecor(guiGraphics);
    }

    protected void renderCenterBubble(GuiGraphics guiGraphics, Component text, int textColor) {
        float bubbleScale = 0.98f + centerPop * 0.06f;
        int screenRadius = Math.min(Math.min(centerX, this.width - centerX), Math.min(centerY, this.height - centerY)) - 3;
        int bubbleRadius = Math.max(0, Math.min(Math.max(0, innerRadius - 4), screenRadius));
        bubbleRadius = Math.min(bubbleRadius, Math.max(0, Math.round(innerRadius * CENTER_BUBBLE_SCALE * bubbleScale)));
        if (bubbleRadius > 0) {
            fillCircle(guiGraphics, centerX, centerY, bubbleRadius + 2, 0xEE11161D);
            drawCircleOutline(guiGraphics, centerX, centerY, bubbleRadius + 2, withAlpha(style.centerBorder(), selectedSlot >= 0 ? 255 : 180));
            drawCircleOutline(guiGraphics, centerX, centerY, bubbleRadius + 1, withAlpha(0xFFFFFF, selectedSlot >= 0 ? 80 : 30));
        }
        int textHeight = this.font.lineHeight;
        int availableWidth = bubbleRadius <= textHeight / 2 ? 0
                : (int) (2.0 * Math.sqrt(bubbleRadius * bubbleRadius - Math.pow(textHeight / 2.0, 2))) - 4;
        String display = text == null ? "" : text.getString();
        TextFit fit = fitCenteredText(display, centerX, centerY - textHeight / 2, Math.max(0, availableWidth - 2), bubbleRadius - 3);
        drawFittedText(guiGraphics, fit, textColor, true);
    }

    protected void renderEmptyState(GuiGraphics guiGraphics, Component hint) {
        int width = Math.min(Math.max(0, this.width - 12), Math.max(0, this.font.width(hint) + 48));
        int height = Math.min(36, Math.max(0, this.height - 8));
        if (width < 12 || height < this.font.lineHeight + 8) return;
        int x = Mth.clamp(centerX - width / 2, 4, Math.max(4, this.width - width - 4));
        int y = Mth.clamp(centerY + outerRadius / 2 + 18, 4, Math.max(4, this.height - height - 4));
        fillRoundedRect(guiGraphics, x, y, width, height, 12, 0xEE1E293B);
        fillRoundedRect(guiGraphics, x + 1, y + 1, width - 2, height - 2, 11, 0xF00F172A);
        guiGraphics.drawCenteredString(this.font, fitText(hint.getString(), width - 20), centerX, y + (height - this.font.lineHeight) / 2, TEXT_PRIMARY);
    }

    protected Button createWheelIconButton(Component label, Button.OnPress onPress) {
        int buttonWidth = Math.min(28, Math.max(0, this.width - 8));
        int buttonHeight = Math.min(24, Math.max(0, this.height - 8));
        return Button.builder(label, onPress)
                .bounds(Math.max(4, this.width - buttonWidth - 4), Math.max(4, this.height - buttonHeight - 4), buttonWidth, buttonHeight)
                .build();
    }

    private void renderBackdrop(GuiGraphics guiGraphics) {
        if (this.minecraft == null || this.minecraft.level == null) {
            this.renderBackground(guiGraphics);
        }
        guiGraphics.fill(0, 0, this.width, this.height, BACKDROP_DIM);
    }

    private void renderSegmentWheel(GuiGraphics guiGraphics, List<WheelEntry> entries) {
        int count = entries.size();
        float segmentAngle = 360.0f / count;
        int ringInner = innerRadius;
        int ringOuter = outerRadius;

        drawCircleArcOutline(guiGraphics, centerX, centerY, ringOuter + 3, OUTLINE_DIM, 2.1f);
        drawCircleArcOutline(guiGraphics, centerX, centerY, ringInner - 2, withAlpha(style.lineColorDim(), 190), 1.8f);

        for (int i = 0; i < count; i++) {
            WheelEntry entry = entries.get(i);
            float pop = slotPop[i];
            float start = i * segmentAngle - 90.0f + SEGMENT_GAP_DEGREES * 0.5f;
            float sweep = Math.max(8.0f, segmentAngle - SEGMENT_GAP_DEGREES);
            float expandedOuter = ringOuter + pop * 5.0f + openProgress * 2.5f;
            float expandedInner = Math.max(12.0f, ringInner - pop * 2.0f);

            int fillColor = i == selectedSlot
                    ? blendColors(withAlpha(style.centerBg(), 106), withAlpha(0xFFFFFF, 24), 0.12f + pop * 0.16f)
                    : blendColors(withAlpha(style.centerBg(), 92), withAlpha(style.lineColorDim(), 28), 0.06f + openProgress * 0.08f);
            int edgeColor = i == selectedSlot
                    ? blendColors(withAlpha(style.centerBorder(), 138), withAlpha(0xFFFFFF, 92), pop * 0.20f)
                    : withAlpha(style.lineColorDim(), 88);

            drawAnnularSegment(guiGraphics, centerX, centerY, expandedInner, expandedOuter, start, sweep, fillColor);
            drawAnnularSegmentOutline(guiGraphics, centerX, centerY, expandedInner, expandedOuter, start, sweep, edgeColor, 1.8f);
            drawSeparator(guiGraphics, centerX, centerY, expandedInner + 4.0f, expandedOuter - 4.0f, start, withAlpha(0xFFFFFF, 34));

            double angle = Math.toRadians(i * segmentAngle + segmentAngle / 2.0 - 90.0);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float textRadius = expandedInner + (expandedOuter - expandedInner) * 0.54f + pop * 2.0f;
            int cx = Math.round(centerX + cos * textRadius);
            int cy = Math.round(centerY + sin * textRadius);
            float halfSweepRad = (float) Math.toRadians(sweep * 0.5f);
            // 弦长仅给省略逻辑提供候选宽度，矩形边界仍会对实际扇区逐角校验。
            int maxTextWidth = count == 1
                    ? Math.min(210, Math.round(2.0f * (float) Math.sqrt(Math.max(0.0f, textRadius * textRadius - expandedInner * expandedInner)) * 0.90f))
                    : Math.max(0, Math.min(210,
                    Math.round(2.0f * textRadius * (float) Math.sin(halfSweepRad) * 0.90f)));

            String primary = entry.primaryText() == null ? "" : entry.primaryText().getString();
            String secondary = entry.secondaryText() == null ? "" : entry.secondaryText().getString();
            if (secondary.isEmpty()) {
                renderSingleEntry(guiGraphics, primary, cx, cy, pop, maxTextWidth, start, sweep, expandedInner, expandedOuter, count);
            } else {
                renderDualEntry(guiGraphics, primary, secondary, cx, cy, pop, maxTextWidth, start, sweep, expandedInner, expandedOuter, count);
            }
        }
    }

    private void renderCenterDecor(GuiGraphics guiGraphics) {
        int haloRadius = Math.max(18, Math.round(innerRadius * 0.86f + openProgress * 4.0f));
        drawCircleOutline(guiGraphics, centerX, centerY, haloRadius, withAlpha(style.lineColorDim(), 42));
        drawCircleOutline(guiGraphics, centerX, centerY, Math.max(12, haloRadius - 6), withAlpha(0xFFFFFF, 10));
    }

    private void renderSingleEntry(GuiGraphics guiGraphics, String text, int centerTextX, int centerTextY, float pop,
                                   int maxWidth, float start, float sweep, float inner, float outer, int count) {
        float scale = 1.0f + pop * 0.035f;
        TextFit fit = fitSectorText(text, centerTextX, centerTextY - this.font.lineHeight / 2,
                scale, maxWidth, start, sweep, inner, outer, count);
        int color = blendColors(TEXT_SECONDARY, TEXT_PRIMARY, 0.40f + pop * 0.60f);
        drawFittedText(guiGraphics, fit, color, true);
    }

    private void renderDualEntry(GuiGraphics guiGraphics, String icon, String label, int centerTextX, int centerTextY,
                                 float pop, int maxWidth, float start, float sweep, float inner, float outer, int count) {
        String displayIcon = icon == null ? "" : icon;
        int iconColor = blendColors(TEXT_SECONDARY, TEXT_PRIMARY, 0.45f + pop * 0.55f);
        String displayLabel = label == null ? "" : label;
        int labelColor = blendColors(TEXT_MUTED, TEXT_SECONDARY, 0.52f + pop * 0.38f);
        float groupScale = 1.0f + pop * 0.06f;
        int iconY = Math.round(centerTextY - 10 * groupScale);
        int labelY = Math.round(centerTextY + 4 * groupScale);
        TextFit iconFit = fitSectorText(displayIcon, centerTextX, iconY, 1.0f + pop * 0.08f,
                maxWidth, start, sweep, inner, outer, count);
        TextFit labelFit = fitSectorText(displayLabel, centerTextX, labelY, 0.90f + pop * 0.02f,
                Math.max(0, Math.round(maxWidth * 0.76f)), start, sweep, inner, outer, count);
        drawFittedText(guiGraphics, iconFit, iconColor, true);
        drawFittedText(guiGraphics, labelFit, labelColor, true);
    }

    private record TextFit(String text, float x, float y, float scale, boolean visible) {}

    private TextFit fitSectorText(String text, int cx, int y, float requestedScale, int widthLimit,
                                  float start, float sweep, float inner, float outer, int count) {
        String candidate = text == null ? "" : text;
        float scale = requestedScale;
        while (true) {
            TextFit fit = new TextFit(candidate, cx - this.font.width(candidate) * scale / 2.0f, y, scale, true);
            if (fitsSector(fit, start, sweep, inner, outer, count)) return fit;
            if (scale > MIN_TEXT_SCALE) {
                scale = Math.max(MIN_TEXT_SCALE, scale - 0.04f);
                continue;
            }
            // 几何检查优先；弦长估值只用于寻找第一次省略的候选字符串。
            if (candidate.equals(text) && widthLimit < this.font.width(candidate)) {
                int rawTextBudget = Math.max(0, (int) Math.floor(widthLimit / scale));
                String estimate = fitText(candidate, rawTextBudget);
                if (!estimate.isEmpty() && !estimate.equals(candidate)) {
                    candidate = estimate;
                    continue;
                }
            }
            // 候选宽度已经是原字体像素，必须严格递减，不能再次除以缩放比例。
            int reducedWidth = Math.max(0, this.font.width(candidate) - 1);
            String shorter = fitText(candidate, reducedWidth);
            if (shorter.equals(candidate)) shorter = removeLastCodePoint(candidate);
            if (shorter.isEmpty() || shorter.equals(candidate) || this.font.width(shorter) >= this.font.width(candidate)) {
                return new TextFit("", cx, y, scale, false);
            }
            candidate = shorter;
        }
    }

    private TextFit fitCenteredText(String text, int cx, int y, int widthLimit, int radiusLimit) {
        float scale = 1.0f;
        String candidate = text;
        while (true) {
            TextFit fit = new TextFit(candidate, cx - this.font.width(candidate) * scale / 2.0f, y, scale, true);
            if (fitsCircle(fit, cx, radiusLimit)) return fit;
            // 精确尝试可读性下限，避免浮点递减跳过 0.62 后提前省略。
            if (scale <= MIN_TEXT_SCALE) break;
            scale = Math.max(MIN_TEXT_SCALE, scale - 0.04f);
        }
        scale = MIN_TEXT_SCALE;
        candidate = fitText(candidate, Math.max(0, (int) Math.floor(widthLimit / scale)));
        while (!candidate.isEmpty()) {
            TextFit fit = new TextFit(candidate, cx - this.font.width(candidate) * scale / 2.0f, y, scale, true);
            if (fitsCircle(fit, cx, radiusLimit)) return fit;
            // 严格缩小原字体预算，避免小比例下预算反而增大而使渲染循环无法结束。
            int reducedBudget = Math.max(0, this.font.width(candidate) - 1);
            String shorter = fitText(candidate, reducedBudget);
            if (shorter.equals(candidate)) shorter = removeLastCodePoint(candidate);
            candidate = shorter;
        }
        return new TextFit("", cx, y, 1, false);
    }

    private boolean fitsSector(TextFit fit, float start, float sweep, float inner, float outer, int count) {
        // 把变换后的文字与偏移阴影合并成实际像素矩形，再交给纯几何层检查。
        float left = fit.x() - TEXT_PADDING;
        float top = fit.y() - TEXT_PADDING;
        float right = fit.x() + this.font.width(fit.text()) * fit.scale() + 1 + TEXT_PADDING;
        float bottom = fit.y() + this.font.lineHeight * fit.scale() + 1 + TEXT_PADDING;
        return WheelTextGeometry.fitsSector(new WheelTextGeometry.Rect(left, top, right, bottom),
                this.width, this.height, centerX, centerY, inner, outer, start, sweep, count);
    }

    private boolean fitsCircle(TextFit fit, int cx, int radius) {
        float left = fit.x() - TEXT_PADDING;
        float right = fit.x() + (this.font.width(fit.text()) + 1) * fit.scale() + TEXT_PADDING;
        float top = fit.y() - TEXT_PADDING;
        float bottom = fit.y() + (this.font.lineHeight + 1) * fit.scale() + TEXT_PADDING;
        float cy = centerY;
        for (float x : new float[]{left, right}) for (float y : new float[]{top, bottom}) {
            if (Math.hypot(x - cx, y - cy) > radius) return false;
        }
        return left >= 0 && top >= 0 && right <= this.width && bottom <= this.height;
    }

    private void drawFittedText(GuiGraphics guiGraphics, TextFit fit, int color, boolean shadow) {
        if (!fit.visible() || fit.text().isEmpty()) return;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(fit.x(), fit.y(), 0.0f);
        guiGraphics.pose().scale(fit.scale(), fit.scale(), 1.0f);
        if (shadow) guiGraphics.drawString(this.font, fit.text(), 1, 1, 0xFF000000, false);
        guiGraphics.drawString(this.font, fit.text(), 0, 0, color, false);
        guiGraphics.pose().popPose();
    }

    private void renderSelectedEntryTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY, List<WheelEntry> entries) {
        if (selectedSlot < 0 || selectedSlot >= entries.size()) return;
        WheelEntry entry = entries.get(selectedSlot);
        if (entry.primaryText() == null && entry.secondaryText() == null) return;
        int maxTextWidth = Math.max(0, this.width - 24); // 为工具提示边距及屏幕留出空间。
        if (maxTextWidth == 0) return;
        List<FormattedCharSequence> lines = new java.util.ArrayList<>();
        if (entry.primaryText() != null) lines.addAll(this.font.split(entry.primaryText(), maxTextWidth));
        if (entry.secondaryText() != null) lines.addAll(this.font.split(entry.secondaryText(), maxTextWidth));
        int maxLines = Math.max(1, (this.height - 24) / 10);
        if (lines.size() > maxLines) {
            lines = new java.util.ArrayList<>(lines.subList(0, maxLines));
            lines.set(maxLines - 1, this.font.split(Component.literal("…"), maxTextWidth).get(0));
        }
        if (!lines.isEmpty()) guiGraphics.renderTooltip(this.font, lines, mouseX, mouseY);
    }

    protected void renderWheelTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderSelectedEntryTooltip(guiGraphics, mouseX, mouseY, renderedEntries);
    }

    private void updateAnimations(int count) {
        ensureAnimationCapacity(count);
        float openTarget = 1.03f;
        openVelocity += (openTarget - openProgress) * 0.24f;
        openVelocity *= 0.74f;
        openProgress = Mth.clamp(openProgress + openVelocity, 0.0f, 1.22f);

        for (int i = 0; i < count; i++) {
            float target = i == selectedSlot ? 1.08f : -0.05f;
            slotVelocity[i] += (target - slotPop[i]) * 0.40f;
            slotVelocity[i] *= 0.80f;
            slotPop[i] = Mth.clamp(slotPop[i] + slotVelocity[i], -0.22f, 1.42f);
        }

        float centerTarget = selectedSlot >= 0 ? 1.06f : -0.04f;
        centerVelocity += (centerTarget - centerPop) * 0.34f;
        centerVelocity *= 0.78f;
        centerPop = Mth.clamp(centerPop + centerVelocity, -0.20f, 1.30f);
    }

    private void ensureAnimationCapacity(int count) {
        if (slotPop.length == count) {
            return;
        }
        slotPop = new float[count];
        slotVelocity = new float[count];
        centerPop = 0.0f;
        centerVelocity = 0.0f;
        openProgress = 0.0f;
        openVelocity = 0.0f;
    }

    protected String fitText(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (this.font.width(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String ellipsis = "…";
        if (this.font.width(ellipsis) > maxWidth) return "";
        String trimmed = text;
        while (!trimmed.isEmpty() && this.font.width(trimmed + ellipsis) > maxWidth) {
            trimmed = removeLastCodePoint(trimmed);
        }
        return trimmed + ellipsis;
    }

    private String removeLastCodePoint(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.substring(0, text.offsetByCodePoints(text.length(), -1));
    }

    protected int blendColors(int from, int to, float progress) {
        float clamped = Mth.clamp(progress, 0.0f, 1.0f);
        int a = Mth.floor(Mth.lerp(clamped, alpha(from), alpha(to)));
        int r = Mth.floor(Mth.lerp(clamped, red(from), red(to)));
        int g = Mth.floor(Mth.lerp(clamped, green(from), green(to)));
        int b = Mth.floor(Mth.lerp(clamped, blue(from), blue(to)));
        return a << 24 | r << 16 | g << 8 | b;
    }

    protected int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private void fillRoundedRect(GuiGraphics guiGraphics, int x, int y, int width, int height, int radius, int color) {
        int clampedRadius = Math.min(radius, Math.min(width, height) / 2);
        for (int row = 0; row < height; row++) {
            int inset = row < clampedRadius
                    ? roundedInset(clampedRadius, row)
                    : row >= height - clampedRadius
                    ? roundedInset(clampedRadius, height - 1 - row)
                    : 0;
            guiGraphics.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
        }
    }

    private void fillCircle(GuiGraphics guiGraphics, int cx, int cy, int radius, int color) {
        if (radius <= 0) {
            return;
        }
        for (int dy = -radius; dy <= radius; dy++) {
            int span = (int) Math.sqrt(radius * radius - dy * dy);
            guiGraphics.fill(cx - span, cy + dy, cx + span + 1, cy + dy + 1, color);
        }
    }

    private void drawCircleOutline(GuiGraphics guiGraphics, int cx, int cy, int radius, int color) {
        if (radius <= 1) {
            return;
        }
        for (int dy = -radius; dy <= radius; dy++) {
            int outer = (int) Math.sqrt(radius * radius - dy * dy);
            int innerRadius = Math.max(0, radius - 2);
            int inner = innerRadius == 0 ? 0 : (int) Math.sqrt(Math.max(0, innerRadius * innerRadius - dy * dy));
            guiGraphics.fill(cx - outer, cy + dy, cx - inner, cy + dy + 1, color);
            guiGraphics.fill(cx + inner + 1, cy + dy, cx + outer + 1, cy + dy + 1, color);
        }
    }

    private void drawCircleArcOutline(GuiGraphics guiGraphics, float cx, float cy, float radius, int color, float width) {
        drawRing(guiGraphics, cx, cy, Math.max(0.0f, radius - width), radius, 0.0f, 360.0f, color);
    }

    private void drawSeparator(GuiGraphics guiGraphics, float cx, float cy, float inner, float outer, float angleDeg, int color) {
        float radians = (float) Math.toRadians(angleDeg);
        float dx = Mth.cos(radians);
        float dy = Mth.sin(radians);
        float nx = -dy * 0.9f;
        float ny = dx * 0.9f;
        drawQuad(guiGraphics,
                cx + dx * inner - nx, cy + dy * inner - ny,
                cx + dx * inner + nx, cy + dy * inner + ny,
                cx + dx * outer + nx, cy + dy * outer + ny,
                cx + dx * outer - nx, cy + dy * outer - ny,
                color);
    }

    private void drawAnnularSegment(GuiGraphics guiGraphics, float cx, float cy,
                                    float inner, float outer, float startDeg, float sweepDeg, int color) {
        drawRing(guiGraphics, cx, cy, inner, outer, startDeg, sweepDeg, color);
    }

    private void drawAnnularSegmentOutline(GuiGraphics guiGraphics, float cx, float cy,
                                           float inner, float outer, float startDeg, float sweepDeg,
                                           int color, float width) {
        drawRing(guiGraphics, cx, cy, outer - width, outer, startDeg, sweepDeg, color);
        drawRing(guiGraphics, cx, cy, inner, inner + width, startDeg, sweepDeg, color);
        drawSeparator(guiGraphics, cx, cy, inner, outer, startDeg, color);
        drawSeparator(guiGraphics, cx, cy, inner, outer, startDeg + sweepDeg, color);
    }

    private void drawRing(GuiGraphics guiGraphics, float cx, float cy,
                          float inner, float outer, float startDeg, float sweepDeg, int color) {
        int segments = Math.max(12, Math.round(Math.abs(sweepDeg) / 5.5f));
        float startRad = (float) Math.toRadians(startDeg);
        float stepRad = (float) Math.toRadians(sweepDeg / segments);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        PoseStack.Pose pose = guiGraphics.pose().last();
        Matrix4f matrix = pose.pose();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= segments; i++) {
            float angle = startRad + stepRad * i;
            float cos = Mth.cos(angle);
            float sin = Mth.sin(angle);
            addVertex(builder, matrix, cx + cos * outer, cy + sin * outer, color);
            addVertex(builder, matrix, cx + cos * inner, cy + sin * inner, color);
        }
        BufferUploader.drawWithShader(builder.end());
    }

    private void drawQuad(GuiGraphics guiGraphics,
                          float ax, float ay,
                          float bx, float by,
                          float cx, float cy,
                          float dx, float dy,
                          int color) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        PoseStack.Pose pose = guiGraphics.pose().last();
        Matrix4f matrix = pose.pose();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        addVertex(builder, matrix, ax, ay, color);
        addVertex(builder, matrix, bx, by, color);
        addVertex(builder, matrix, cx, cy, color);
        addVertex(builder, matrix, dx, dy, color);
        BufferUploader.drawWithShader(builder.end());
    }

    private void addVertex(BufferBuilder builder, Matrix4f matrix, float x, float y, int color) {
        builder.vertex(matrix, x, y, 0.0f)
                .color(red(color), green(color), blue(color), alpha(color))
                .endVertex();
    }

    private int roundedInset(int radius, int row) {
        if (radius <= 0) {
            return 0;
        }
        double dy = radius - row - 0.5;
        double inside = Math.max(0.0, radius * radius - dy * dy);
        return Math.max(0, radius - (int) Math.floor(Math.sqrt(inside)) - 1);
    }

    private int alpha(int color) {
        return color >>> 24;
    }

    private int red(int color) {
        return color >> 16 & 0xFF;
    }

    private int green(int color) {
        return color >> 8 & 0xFF;
    }

    private int blue(int color) {
        return color & 0xFF;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
