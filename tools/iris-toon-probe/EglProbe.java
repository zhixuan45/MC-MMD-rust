import org.lwjgl.egl.EGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.egl.EGL10.*;
import static org.lwjgl.egl.EGL14.*;
import static org.lwjgl.egl.EGL15.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/** Linux Mesa 无窗口上下文，不依赖运行中的游戏或显示服务器。 */
public final class EglProbe {
    public static void main(String[] args) throws Exception {
        long display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!eglInitialize(display, stack.mallocInt(1), stack.mallocInt(1))) fail("eglInitialize");
            EGL.createDisplayCapabilities(display);
            if (!eglBindAPI(EGL_OPENGL_API)) fail("eglBindAPI");
            var configs = stack.mallocPointer(1);
            var count = stack.mallocInt(1);
            if (!eglChooseConfig(display, stack.ints(EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE), configs, count) || count.get(0) == 0) {
                fail("eglChooseConfig");
            }
            long surface = eglCreatePbufferSurface(display, configs.get(0),
                    stack.ints(EGL_WIDTH, 64, EGL_HEIGHT, 64, EGL_NONE));
            long context = eglCreateContext(display, configs.get(0), NULL,
                    stack.ints(EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
                            EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT, EGL_NONE));
            if (surface == NULL || context == NULL) fail("eglCreateContext/surface");
            try {
                if (!eglMakeCurrent(display, surface, surface, context)) fail("eglMakeCurrent");
                GL.createCapabilities();
                Probe.run(args);
            } finally {
                eglMakeCurrent(display, NULL, NULL, NULL);
                eglDestroyContext(display, context);
                eglDestroySurface(display, surface);
                eglTerminate(display);
            }
        }
    }

    private static void fail(String operation) {
        throw new IllegalStateException(operation + ": EGL error " + Integer.toHexString(eglGetError()));
    }
}
