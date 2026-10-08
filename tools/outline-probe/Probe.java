import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** 隐藏窗口验证真实描边 shader 的纯色与透明度契约。 */
public final class Probe {
    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW 初始化失败");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(64, 64, "outline-probe", 0, 0);
        try {
            check(window != 0, "隐藏 OpenGL 窗口");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            Path shaders = Path.of("common/src/main/resources/assets/mmdskin/shader");
            String vertex = Files.readString(shaders.resolve("toon_outline_body.vert.glsl"));
            String fragment = Files.readString(shaders.resolve("toon_outline_body.frag.glsl"))
                    .replace("/* TOON_OUTPUT_DECLARATIONS */", "layout(location=0) out vec4 color;")
                    .replace("/* TOON_OUTPUT_WRITER */", "void writeToonOutputs(vec3 c, vec3 a, vec3 n, float alpha) { color=vec4(c,alpha); }");
            int program = GL46C.glCreateProgram();
            int vs = compile(GL46C.GL_VERTEX_SHADER, vertex);
            int fs = compile(GL46C.GL_FRAGMENT_SHADER, fragment);
            GL46C.glAttachShader(program, vs);
            GL46C.glAttachShader(program, fs);
            GL46C.glLinkProgram(program);
            check(GL46C.glGetProgrami(program, GL46C.GL_LINK_STATUS) != 0, GL46C.glGetProgramInfoLog(program));
            GL46C.glUseProgram(program);
            check(GL46C.glGetUniformLocation(program, "Sampler0") == -1, "描边不能采样材质纹理");
            check(GL46C.glGetAttribLocation(program, "UV0") == -1, "描边不能依赖 UV");
            check(GL46C.glGetUniformLocation(program, "OutlineAlpha") >= 0, "逐子网格 alpha 必须存在");
            check(GL46C.glGetUniformLocation(program, "GlobalAlpha") == -1, "最终 alpha 不能再乘全局 alpha");
            float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(program, "ProjMat"), false, identity);
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(program, "ModelViewMat"), false, identity);
            GL46C.glUniform1f(GL46C.glGetUniformLocation(program, "OutlineWidth"), 0.0022f);
            GL46C.glUniform3f(GL46C.glGetUniformLocation(program, "OutlineColor"), 0.2f, 0.3f, 0.4f);
            int vao = GL46C.glGenVertexArrays();
            GL46C.glBindVertexArray(vao);
            int positions = buffer(0, new float[]{-.6f,-.6f,-.5f, 0,.6f,-.5f, .6f,-.6f,-.5f});
            int normals = buffer(1, new float[]{0,0,-1, 0,0,-1, 0,0,-1});
            GL46C.glViewport(0, 0, 64, 64);
            GL46C.glDisable(GL46C.GL_BLEND);
            GL46C.glDisable(GL46C.GL_DEPTH_TEST);
            GL46C.glEnable(GL46C.GL_CULL_FACE);
            GL46C.glCullFace(GL46C.GL_FRONT);
            // 故意绑定不同颜色/透明度的残留纹理，输出必须一致。
            int texture = GL46C.glGenTextures();
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
            // 模型回调提供已合成的 0.5 * 0.8 = 0.4，通道只使用一次。
            float[] opaque = sample(program, new float[]{1,0,0,1}, 0.4f);
            float[] transparent = sample(program, new float[]{0,0,0,0}, 0.4f);
            near(opaque, new float[]{0.2f,0.3f,0.4f,0.4f});
            near(transparent, opaque);
            near(sample(program, new float[]{1,1,1,1}, 0), new float[4]);
            GL46C.glDeleteTextures(texture);
            GL46C.glDeleteBuffers(positions);
            GL46C.glDeleteBuffers(normals);
            GL46C.glDeleteVertexArrays(vao);
            GL46C.glDeleteProgram(program);
            GL46C.glDeleteShader(vs);
            GL46C.glDeleteShader(fs);
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "OpenGL 状态错误");
            System.out.println("PASS outline: GLSL link, no UV/sampler, pure color, texture independence, final alpha applied once");
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static int compile(int type, String source) {
        int shader = GL46C.glCreateShader(type);
        GL46C.glShaderSource(shader, source);
        GL46C.glCompileShader(shader);
        check(GL46C.glGetShaderi(shader, GL46C.GL_COMPILE_STATUS) != 0, GL46C.glGetShaderInfoLog(shader));
        return shader;
    }

    private static int buffer(int location, float[] data) {
        int buffer = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, buffer);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, data, GL46C.GL_STATIC_DRAW);
        GL46C.glEnableVertexAttribArray(location);
        GL46C.glVertexAttribPointer(location, 3, GL46C.GL_FLOAT, false, 0, 0);
        return buffer;
    }

    private static float[] sample(int program, float[] texel, float alpha) {
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA32F, 1, 1, 0, GL46C.GL_RGBA, GL46C.GL_FLOAT, texel);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(program, "OutlineAlpha"), alpha);
        GL46C.glClearColor(0, 0, 0, 0);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        float[] pixel = new float[4];
        GL46C.glReadPixels(32, 32, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, pixel);
        return pixel;
    }

    private static void near(float[] actual, float[] expected) {
        for (int i = 0; i < 4; i++) {
            check(Math.abs(actual[i] - expected[i]) < 0.01f,
                    "像素输出 " + Arrays.toString(actual) + " 期望 " + Arrays.toString(expected));
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
