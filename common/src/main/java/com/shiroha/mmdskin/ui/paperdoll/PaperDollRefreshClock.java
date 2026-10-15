package com.shiroha.mmdskin.ui.paperdoll;

/** Successful renders alone advance this clock. Hidden views and changed keys invalidate it. */
final class PaperDollRefreshClock {
    private Object key;
    private long lastRender;
    private int rate;
    boolean due(Object next, int fps, long now) {
        return key == null || !key.equals(next) || rate != fps || fps <= 0
                || now < lastRender || now - lastRender >= 1_000_000_000L / fps;
    }
    void rendered(Object next, int fps, long now) { key = next; rate = fps; lastRender = now; }
    void invalidate() { key = null; }
}
