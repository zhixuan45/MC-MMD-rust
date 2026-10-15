import com.shiroha.mmdskin.render.backend.gpu.ImmutableGpuBufferPool;
import java.nio.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Content identity, immutable CPU views, refcounts, budget fallback and host GL state. */
public class StaticBufferGlProbe {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static ByteBuffer data(int value) { return ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).putInt(0, value); }
    public static void main(String[] args) {
        System.setProperty("mmdskin.staticBufferBudgetMiB", args.length == 0 ? "256" : args[0]);
        check(GLFW.glfwInit(), "GLFW"); GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(32, 32, "static buffer probe", 0, 0); check(window != 0, "window");
        try { GLFW.glfwMakeContextCurrent(window); GL.createCapabilities(); run(args.length == 0); }
        finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }
    static void run(boolean pooled) {
        int host = GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER, host);
        ByteBuffer input = data(5);
        var first = ImmutableGpuBufferPool.acquire("mesh", input);
        var second = ImmutableGpuBufferPool.acquire("mesh", data(5));
        int id = first.buffer(), secondId = second.buffer();
        check((id == secondId) == pooled, "budget determines sharing");
        check(first.accountedBytes() == 16 && second.accountedBytes() == (pooled ? 0 : 16), "memory counted once");
        check(first.data().isReadOnly() && first.data().getInt(0) == 5, "read-only canonical view");
        input.putInt(0, 9);
        check(first.data().getInt(0) == 5, "caller cannot mutate canonical data");
        check(GL46C.glGetInteger(GL46C.GL_COPY_WRITE_BUFFER_BINDING) == host, "copy-write restored");
        try (var reloaded = ImmutableGpuBufferPool.acquire("mesh", input);
             var differentKind = ImmutableGpuBufferPool.acquire("weights", data(5))) {
            check(reloaded.buffer() != id && differentKind.buffer() != id, "content and semantics identify storage");
        }
        first.close(); first.close();
        check(GL46C.glIsBuffer(secondId), "one owner survives another owner releasing");
        check(second.accountedBytes() == 16, "accounting transfers to remaining owner");
        second.close();
        check(!GL46C.glIsBuffer(id) && !GL46C.glIsBuffer(secondId), "last release deletes storage");
        System.setProperty("mmdskin.shareStaticBuffers", "false");
        try (var a = ImmutableGpuBufferPool.acquire("mesh", data(5)); var b = ImmutableGpuBufferPool.acquire("mesh", data(5))) {
            check(a.buffer() != b.buffer(), "compatibility switch disables sharing");
        }
        System.clearProperty("mmdskin.shareStaticBuffers");
        GL46C.glDeleteBuffers(host);
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL error");
        System.out.println("StaticBufferGlProbe PASS: pooled=" + pooled + "; content, ownership, release, reload, switch, GL state");
    }
}
