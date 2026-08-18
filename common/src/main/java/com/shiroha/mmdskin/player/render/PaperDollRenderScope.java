package com.shiroha.mmdskin.player.render;

/**
 * 文件职责：标记当前线程是否正在执行屏幕纸娃娃（Paperdoll）渲染。
 */
public final class PaperDollRenderScope {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private PaperDollRenderScope() {
    }

    /**
     * 进入纸娃娃渲染作用域。
     */
    public static void enter() {
        DEPTH.set(DEPTH.get() + 1);
    }

    /**
     * 退出纸娃娃渲染作用域。
     */
    public static void exit() {
        int depth = DEPTH.get();
        if (depth <= 1) {
            DEPTH.remove();
            return;
        }
        DEPTH.set(depth - 1);
    }

    /**
     * 判断当前线程是否正处于纸娃娃渲染上下文中。
     *
     * @return 若在纸娃娃渲染中则返回 true
     */
    public static boolean isActive() {
        return DEPTH.get() > 0;
    }
}
