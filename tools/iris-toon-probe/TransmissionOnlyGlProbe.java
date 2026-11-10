import com.shiroha.mmdskin.compat.iris.TransmissionGlProbe;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** Runs only the real-GL terrain transmission probe. */
public final class TransmissionOnlyGlProbe {
    private TransmissionOnlyGlProbe() {}

    public static void main(String[] args) {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(16, 16, "Transmission validation", 0, 0);
        if (window == 0) throw new IllegalStateException("Hidden GL context");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("GL: " + GL46C.glGetString(GL46C.GL_VERSION)
                    + " / " + GL46C.glGetString(GL46C.GL_RENDERER));
            TransmissionGlProbe.run();
            if (GL46C.glGetError() != GL46C.GL_NO_ERROR) throw new AssertionError("Final GL error");
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }
}
