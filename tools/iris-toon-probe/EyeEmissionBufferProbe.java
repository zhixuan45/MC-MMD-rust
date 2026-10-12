import com.shiroha.mmdskin.bridge.runtime.NativeRenderBackendPort;
import com.shiroha.mmdskin.render.shader.EyeEmissionBuffer;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

/** 验证生产眼瞳缓冲的 native 初始化顺序、GL 状态和生命周期。 */
public final class EyeEmissionBufferProbe {
    public static void main(String[] args) {
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(16, 16, "Eye buffer probe", 0, 0);
        if (window == 0) throw new AssertionError("隐藏窗口");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        int vao = GL46C.glGenVertexArrays(), sentinel = GL46C.glGenBuffers();
        GL46C.glBindVertexArray(vao);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, sentinel);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, 4, GL46C.GL_STATIC_DRAW);
        ToonShaderCpu shader = new ToonShaderCpu();
        try {
            check(shader.init(), "主体编译");
            int location = GL46C.glGetAttribLocation(shader.getMainProgram(), "EyeMask");
            check(location == 3, "遮罩位置契约");
            for (int cycle = 0; cycle < 20; cycle++) {
                AtomicInteger initialized = new AtomicInteger();
                EyeEmissionBuffer buffer = EyeEmissionBuffer.create(port(initialized, false), 1, 3);
                check(buffer != null && initialized.get() == 1, "CPU 首次必须先初始化骨数组");
                check(GL46C.glGetInteger(GL46C.GL_ARRAY_BUFFER_BINDING) == sentinel, "创建恢复 ARRAY_BUFFER");
                check(buffer.byteSize() == 12, "显存计数");
                // 模拟 Iris 在 UV1 相同位置留下的实例属性。
                GL46C.glVertexAttribDivisor(location, 1);
                buffer.bind(shader.getMainProgram());
                int id = GL46C.glGetVertexAttribi(location, GL46C.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING);
                check(GL46C.glGetVertexAttribi(location, GL46C.GL_VERTEX_ATTRIB_ARRAY_DIVISOR) == 0,
                        "遮罩必须逐顶点读取");
                check(GL46C.glGetInteger(GL46C.GL_ARRAY_BUFFER_BINDING) == sentinel, "绑定恢复 ARRAY_BUFFER");
                buffer.clearBinding(shader.getMainProgram());
                check(GL46C.glGetVertexAttribi(location, GL46C.GL_VERTEX_ATTRIB_ARRAY_ENABLED) == 0, "绘制后禁用遮罩");
                check(GL46C.glGetVertexAttribi(location, GL46C.GL_VERTEX_ATTRIB_ARRAY_DIVISOR) == 1, "恢复 Iris divisor");
                buffer.close();
                buffer.close();
                check(buffer.byteSize() == 0 && !GL46C.glIsBuffer(id), "关闭归还遮罩缓冲");
            }
            check(EyeEmissionBuffer.create(port(new AtomicInteger(), true), 1, 3) == null,
                    "复制失败不得使用半初始化遮罩");
            check(GL46C.glGetInteger(GL46C.GL_ARRAY_BUFFER_BINDING) == sentinel, "异常恢复 ARRAY_BUFFER");
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL 错误");
            System.out.println("PASS eye buffer: 20 cycles, native init order, attribute divisor reset/restore, exception cleanup, VRAM accounting, release");
        } finally {
            shader.cleanup();
            GL46C.glDeleteVertexArrays(vao);
            GL46C.glDeleteBuffers(sentinel);
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static NativeRenderBackendPort port(AtomicInteger initialized, boolean failCopy) {
        return (NativeRenderBackendPort) Proxy.newProxyInstance(EyeEmissionBufferProbe.class.getClassLoader(),
                new Class[]{NativeRenderBackendPort.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getBoneNames": return "[\"頭\",\"左目\",\"右目戻\"]";
                        case "initGpuSkinningData": initialized.incrementAndGet(); return null;
                        case "copyBoneIndicesToBuffer": {
                            check(initialized.get() == 1, "禁止初始化前复制");
                            if (failCopy) return 0;
                            ByteBuffer out = ((ByteBuffer) args[1]).duplicate().order(ByteOrder.LITTLE_ENDIAN);
                            for (int bone : new int[]{0,1,2}) {
                                out.putInt(bone).putInt(-1).putInt(-1).putInt(-1);
                            }
                            return 3;
                        }
                        case "copyBoneWeightsToBuffer": {
                            ByteBuffer out = ((ByteBuffer) args[1]).duplicate().order(ByteOrder.LITTLE_ENDIAN);
                            for (int i = 0; i < 3; i++) out.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
                            return 3;
                        }
                        default: throw new AssertionError("意外调用: " + method.getName());
                    }
                });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
