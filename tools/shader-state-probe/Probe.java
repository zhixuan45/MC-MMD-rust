import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.shiroha.mmdskin.compat.iris.IrisCompat;
import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** Real ShaderInstance and production CPU/GPU teardown on a hidden OpenGL context. */
public final class Probe {
    private static final String VERTEX = """
            #version 330 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 330 core
            uniform float ProbeValue;
            out vec4 color;
            void main() { color = vec4(ProbeValue, 0.0, 0.0, 1.0); }
            """;
    private static int checks;

    public static void main(String[] args) throws Exception {
        check(GLFW.glfwInit(), "GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(16, 16, "shader-state-probe", 0, 0);
        check(window != 0, "hidden context");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            System.out.println("GL=" + GL46C.glGetString(GL46C.GL_VERSION));
            try (ShaderInstance host = new ShaderInstance(resources(), "mmd_state_probe", DefaultVertexFormat.POSITION)) {
                int custom = ShaderCompiler.compileRenderProgram(VERTEX, FRAGMENT, "shader-state-probe");
                check(custom != 0, "custom program");
                try {
                    reproduceStaleCache(host);
                    var failures = new java.util.ArrayList<AssertionError>();
                    for (String backend : new String[]{"opengl.OpenGl", "gpu.GpuSkinning"}) {
                        try {
                            checkBackendTeardown(backend, host, custom);
                        } catch (AssertionError failure) {
                            failures.add(failure);
                            System.out.println("FAIL " + failure.getMessage());
                        } finally {
                            host.clear();
                        }
                    }
                    checkSharedCleanup(host, custom);
                    if (!failures.isEmpty()) {
                        AssertionError failure = new AssertionError("Backend shader cleanup failures: " + failures.size());
                        failures.forEach(failure::addSuppressed);
                        throw failure;
                    }
                } finally {
                    RenderSystem.setShader(() -> null);
                    host.clear();
                    GL46C.glDeleteProgram(custom);
                }
            }
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "final GL error");
            System.out.println("PASS shader state: " + checks + " checks");
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void reproduceStaleCache(ShaderInstance host) {
        host.clear();
        host.apply();
        GL46C.glUseProgram(0);
        host.apply();
        check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM) == 0, "stale ShaderInstance cache reproduced");
        GL46C.glUniform1f(GL46C.glGetUniformLocation(host.getId(), "ProbeValue"), 0.25f);
        check(GL46C.glGetError() == GL46C.GL_INVALID_OPERATION, "unbound uniform upload reproduces GL 1282");
        host.clear();
        System.out.println("PASS reproduced stale cache and GL_INVALID_OPERATION before cleanup");
    }

    private static void checkBackendTeardown(String backend, ShaderInstance host, int custom) throws Exception {
        String root = "com.shiroha.mmdskin.render.backend.";
        Class<?> instanceClass = Class.forName(root + backend + "ModelInstance");
        var constructor = instanceClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();
        var vaoField = instanceClass.getDeclaredField("vertexArrayObject");
        vaoField.setAccessible(true);
        int vao = GL46C.glGenVertexArrays();
        vaoField.setInt(instance, vao);
        Class<?> renderer = Class.forName(root + backend + "ModelRenderer");
        var render = java.util.Arrays.stream(renderer.getDeclaredMethods())
                .filter(method -> method.getName().equals("renderToon")).findFirst().orElseThrow();
        render.setAccessible(true);
        Object[] arguments = new Object[render.getParameterCount()];
        arguments[0] = instance;
        for (int i = 1; i < arguments.length; i++) {
            if (render.getParameterTypes()[i] == float.class) arguments[i] = 1.0f;
            if (render.getParameterTypes()[i] == boolean.class) arguments[i] = false;
        }

        RenderSystem.setShader(() -> host);
        host.apply();
        GL46C.glUseProgram(custom);
        try {
            // The incomplete model aborts inside the real Toon pass, exercising its finally block.
            render.invoke(null, arguments);
            throw new AssertionError("Expected incomplete-model failure: " + backend);
        } catch (InvocationTargetException failure) {
            boolean expected = backend.startsWith("opengl.")
                    ? failure.getCause() instanceof IllegalStateException
                            && failure.getCause().getMessage().equals("nativeRenderBackendPort has not been initialized")
                    : failure.getCause() instanceof NullPointerException;
            if (!expected) {
                throw new AssertionError(backend + " unexpected failure", failure.getCause());
            }
            check(java.util.Arrays.stream(failure.getCause().getStackTrace())
                    .anyMatch(frame -> frame.getClassName().equals(renderer.getName())
                            && frame.getMethodName().equals("renderToon")), backend + " entered Toon pass");
        } finally {
            GL46C.glDeleteVertexArrays(vao);
        }
        check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM) == 0, backend + " releases custom program");
        assertHostRebinds(host, backend);
        System.out.println("PASS " + backend + " production Toon teardown and host rebind");
        host.clear();
    }

    private static void checkSharedCleanup(ShaderInstance host, int custom) {
        for (int i = 0; i < 3; i++) {
            host.apply();
            GL46C.glUseProgram(custom);
            IrisCompat.clearProgram(host, -1, -1);
            assertHostRebinds(host, "repeated host/custom switch " + i);
            host.clear();
        }
    }

    private static void assertHostRebinds(ShaderInstance host, String context) {
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, context + " cleanup GL error");
        host.apply();
        check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM) == host.getId(), context + " host rebound");
        GL46C.glUniform1f(GL46C.glGetUniformLocation(host.getId(), "ProbeValue"), 0.75f);
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, context + " uniform upload");
        check(Math.abs(GL46C.glGetUniformf(host.getId(), GL46C.glGetUniformLocation(host.getId(), "ProbeValue")) - 0.75f)
                < 0.0001f, context + " uniform value");
    }

    private static ResourceProvider resources() {
        String json = """
                {"vertex":"mmd_state_probe","fragment":"mmd_state_probe",
                 "samplers":[],"attributes":[],"uniforms":[]}
                """;
        PackResources pack = (PackResources) Proxy.newProxyInstance(PackResources.class.getClassLoader(),
                new Class<?>[]{PackResources.class}, (object, method, values) ->
                        method.getName().equals("packId") ? "mmd-state-probe" : null);
        return location -> {
            String path = location.getPath();
            String source = path.endsWith(".json") ? json : path.endsWith(".vsh") ? VERTEX : FRAGMENT;
            return Optional.of(new Resource(pack,
                    () -> new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8))));
        };
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
