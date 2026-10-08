package com.shiroha.mmdskin.ui.selector;

/** 设置内容独立滚动，标题和底部操作固定。 */
final class ModelSettingsLayout {
    private static final int EYE_HEIGHT = 48;
    private static final int CARD_HEIGHT = 35;
    private static final int TAIL_HEIGHT = 42;
    private static final int QUICK_HEIGHT = 47;
    private static final int SECTION_GAP = 4;
    private static final int CONTENT_HEIGHT = EYE_HEIGHT + CARD_HEIGHT * 2 + TAIL_HEIGHT + QUICK_HEIGHT + SECTION_GAP * 4;
    final UiRect panel, header, viewport, scrollTrack, scrollThumb;
    final UiRect eyeCard, eyeToggle, eyeSlider, scaleCard, scaleSlider;
    final UiRect heldBlockCard, heldBlockSlider, tailCard, tailIdleToggle, tailMovementToggle;
    final UiRect quickCard, saveButton, resetButton, animButton, doneButton;
    final UiRect[] quickSlotButtons;
    final double scrollOffset;
    final int maxScroll;

    private ModelSettingsLayout(int width, int height, double requestedOffset) {
        int panelWidth = Math.max(1, Math.min(Math.max(168, Math.min(210, Math.round(width * 0.16f))), width - 20));
        int panelHeight = Math.max(1, height - 20);
        panel = new UiRect(Math.max(0, width - panelWidth - 10), 10, panelWidth, panelHeight);
        header = new UiRect(panel.x + 8, panel.y + 5, Math.max(1, panel.w - 16),
                Math.min(34, Math.max(20, panelHeight / 4)));

        // 小窗口优先留出滚动空间，按钮仍保留文字高度。
        int buttonHeight = Math.max(10, Math.min(16, (panelHeight - header.h - 89) / 4));
        int buttonGap = buttonHeight == 16 ? 4 : 2;
        doneButton = new UiRect(header.x, panel.bottom() - 6 - buttonHeight, header.w, buttonHeight);
        animButton = new UiRect(header.x, doneButton.y - buttonGap - buttonHeight, header.w, buttonHeight);
        resetButton = new UiRect(header.x, animButton.y - buttonGap - buttonHeight, header.w, buttonHeight);
        saveButton = new UiRect(header.x, resetButton.y - buttonGap - buttonHeight, header.w, buttonHeight);
        int contentTop = header.bottom() + 2;
        viewport = new UiRect(header.x, contentTop, header.w, Math.max(1, saveButton.y - 4 - contentTop));
        maxScroll = Math.max(0, CONTENT_HEIGHT - viewport.h);
        scrollOffset = clamp(requestedOffset, maxScroll);

        int cardWidth = Math.max(1, viewport.w - (maxScroll > 0 ? 8 : 0));
        int contentY = viewport.y - (int) Math.round(scrollOffset);
        eyeCard = new UiRect(viewport.x, contentY, cardWidth, EYE_HEIGHT);
        eyeToggle = new UiRect(eyeCard.right() - 34, eyeCard.y + 14, 26, 9);
        eyeSlider = new UiRect(eyeCard.x + 4, eyeCard.y + 36, eyeCard.w - 8, 8);
        scaleCard = new UiRect(viewport.x, eyeCard.bottom() + SECTION_GAP, cardWidth, CARD_HEIGHT);
        scaleSlider = new UiRect(scaleCard.x + 4, scaleCard.y + 23, scaleCard.w - 8, 8);
        heldBlockCard = new UiRect(viewport.x, scaleCard.bottom() + SECTION_GAP, cardWidth, CARD_HEIGHT);
        heldBlockSlider = new UiRect(heldBlockCard.x + 4, heldBlockCard.y + 23, heldBlockCard.w - 8, 8);
        tailCard = new UiRect(viewport.x, heldBlockCard.bottom() + SECTION_GAP, cardWidth, TAIL_HEIGHT);
        tailIdleToggle = new UiRect(tailCard.right() - 34, tailCard.y + 16, 26, 9);
        tailMovementToggle = new UiRect(tailCard.right() - 34, tailCard.y + 30, 26, 9);
        quickCard = new UiRect(viewport.x, tailCard.bottom() + SECTION_GAP, cardWidth, QUICK_HEIGHT);
        int quickWidth = (quickCard.w - 4) / 2;
        quickSlotButtons = new UiRect[4];
        for (int i = 0; i < quickSlotButtons.length; i++) {
            quickSlotButtons[i] = new UiRect(quickCard.x + 4 + (i % 2) * quickWidth,
                    quickCard.y + 12 + (i / 2) * 18, quickWidth - 4, 16);
        }

        if (maxScroll > 0) {
            scrollTrack = new UiRect(viewport.right() - 4, viewport.y, 4, viewport.h);
            int thumbHeight = Math.min(viewport.h, Math.max(12, viewport.h * viewport.h / CONTENT_HEIGHT));
            int thumbY = viewport.y + (int) Math.round((viewport.h - thumbHeight) * scrollOffset / maxScroll);
            scrollThumb = new UiRect(scrollTrack.x, thumbY, scrollTrack.w, thumbHeight);
        } else {
            scrollTrack = scrollThumb = UiRect.empty();
        }
    }

    static ModelSettingsLayout create(int width, int height, double scrollOffset) {
        return new ModelSettingsLayout(width, height, scrollOffset);
    }

    double scrollBy(double wheelAmount) {
        return clamp(scrollOffset - wheelAmount * 20, maxScroll);
    }

    double scrollToThumb(double thumbTop) {
        int travel = scrollTrack.h - scrollThumb.h;
        return travel <= 0 ? scrollOffset : clamp((thumbTop - scrollTrack.y) * maxScroll / travel, maxScroll);
    }

    boolean contentHit(UiRect rect, double x, double y) {
        return viewport.contains(x, y) && rect.contains(x, y);
    }

    private static double clamp(double value, int max) {
        return Double.isFinite(value) ? Math.max(0, Math.min(max, value)) : 0;
    }

    static final class UiRect {
        final int x, y, w, h;

        UiRect(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        static UiRect empty() { return new UiRect(0, 0, 0, 0); }
        int right() { return x + w; }
        int bottom() { return y + h; }
        int centerX() { return x + w / 2; }
        int centerY() { return y + h / 2; }

        boolean contains(double px, double py) {
            return w > 0 && h > 0 && px >= x && py >= y && px < right() && py < bottom();
        }
    }
}
