import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL46C;

import java.nio.IntBuffer;

/** 隐藏 GL 探针：隔离验证 Iris overlay texel 与实体颜色混合公式。 */
public final class OverlayProbe {
    private static final String VERTEX = """
            #version 330 core
            in ivec2 iris_UV1;
            uniform sampler2D iris_overlay;
            out vec4 entityColor;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                vec4 overlayColor = texelFetch(iris_overlay, iris_UV1, 0);
                entityColor = vec4(overlayColor.rgb, 1.0 - overlayColor.a);
                entityColor.rgb *= float(entityColor.a != 0.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 330 core
            in vec4 entityColor;
            uniform vec3 albedo;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(mix(albedo, entityColor.rgb, entityColor.a), 1.0);
            }
            """;

    private OverlayProbe() {}

    public static void run() {
        int program = ShaderCompiler.compileRenderProgram(VERTEX, FRAGMENT, "Iris overlay 接口探针");
        if (program == 0) throw new AssertionError("Iris overlay 探针着色器编译失败");
        int framebuffer = GL46C.glGenFramebuffers();
        int output = GL46C.glGenTextures();
        int overlay = GL46C.glGenTextures();
        int uvBuffer = GL46C.glGenBuffers();
        int indices = GL46C.glGenBuffers();
        int vao = GL46C.glGenVertexArrays();
        try {
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, framebuffer);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, output);
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA32F, 1, 1, 0,
                    GL46C.GL_RGBA, GL46C.GL_FLOAT, (java.nio.ByteBuffer) null);
            GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0,
                    GL46C.GL_TEXTURE_2D, output, 0);
            if (GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) != GL46C.GL_FRAMEBUFFER_COMPLETE) {
                throw new AssertionError("Iris overlay 探针 FBO 不完整");
            }

            GL46C.glBindVertexArray(vao);
            IntBuffer uv = BufferUtils.createIntBuffer(8);
            for (int i = 0; i < 3; i++) uv.put(0).put(10);
            uv.put(0).put(10);
            uv.flip();
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, uvBuffer);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, uv, GL46C.GL_STATIC_DRAW);
            int uvLocation = GL46C.glGetAttribLocation(program, "iris_UV1");
            if (uvLocation < 0) throw new AssertionError("Iris overlay 探针缺少 iris_UV1");
            GL46C.glEnableVertexAttribArray(uvLocation);
            GL46C.glVertexAttribIPointer(uvLocation, 2, GL46C.GL_INT, 0, 0L);
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, indices);
            GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, new int[]{0, 1, 2}, GL46C.GL_STATIC_DRAW);

            GL46C.glUseProgram(program);
            GL46C.glUniform1i(GL46C.glGetUniformLocation(program, "iris_overlay"), 1);
            GL46C.glUniform3f(GL46C.glGetUniformLocation(program, "albedo"), 0.7f, 0.4f, 0.2f);
            GL46C.glViewport(0, 0, 1, 1);
            GL46C.glDisable(GL46C.GL_BLEND);

            GL46C.glActiveTexture(GL46C.GL_TEXTURE1);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, overlay);
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, 16, 16, 0,
                    GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, neutralOverlayRow());
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAX_LEVEL, 0);
            float[] neutral = drawAndRead();
            expect(neutral, new float[]{0.7f, 0.4f, 0.2f}, "UV1=(0,10) 中性 overlay");

            // 同游戏路径一致：非实例索引绘制从缓冲尾部读取第 0 实例。
            uv.clear();
            for (int i = 0; i < 3; i++) uv.put(15).put(15);
            uv.put(0).put(10).flip();
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, uvBuffer);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, uv, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribI4i(uvLocation, 8, 9, 3, 4);
            GL46C.glVertexAttribIPointer(uvLocation, 2, GL46C.GL_INT, 0, 24L);
            GL46C.glVertexAttribDivisor(uvLocation, 1);
            expect(drawAndRead(), new float[]{0.7f, 0.4f, 0.2f}, "缓冲尾部 UV1=(0,10)");
            int[] current = new int[4];
            GL46C.glGetVertexAttribIiv(uvLocation, GL46C.GL_CURRENT_VERTEX_ATTRIB, current);
            if (!java.util.Arrays.equals(current, new int[]{8, 9, 3, 4})) {
                throw new AssertionError("覆盖坐标修改了全局常量属性");
            }

            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, 16, 16, 0,
                    GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(16 * 16 * 4));
            float[] missing = drawAndRead();
            expect(missing, new float[]{0.0f, 0.0f, 0.0f}, "零 alpha overlay");
            System.out.println("PASS: Iris overlay 公式在 UV1=(0,10) 保持 albedo；零 alpha 覆盖纹理输出黑色（独立 GL 公式探针，不代表完整游戏渲染）");
        } finally {
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, 0);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
            GL46C.glDeleteTextures(new int[]{output, overlay});
            GL46C.glDeleteBuffers(new int[]{uvBuffer, indices});
            GL46C.glDeleteVertexArrays(vao);
            GL46C.glDeleteFramebuffers(framebuffer);
            GL46C.glDeleteProgram(program);
        }
    }

    private static java.nio.ByteBuffer neutralOverlayRow() {
        var pixels = BufferUtils.createByteBuffer(16 * 16 * 4);
        // 仅第 10 行中性，确保测试能发现错误坐标。
        for (int x = 0; x < 16; x++) {
            int offset = (10 * 16 + x) * 4;
            for (int c = 0; c < 4; c++) pixels.put(offset + c, (byte) 255);
        }
        return pixels;
    }

    private static float[] drawAndRead() {
        GL46C.glDrawElements(GL46C.GL_TRIANGLES, 3, GL46C.GL_UNSIGNED_INT, 0L);
        float[] pixel = new float[4];
        GL46C.glReadPixels(0, 0, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, pixel);
        return pixel;
    }

    private static void expect(float[] actual, float[] expected, String label) {
        for (int i = 0; i < expected.length; i++) {
            if (Math.abs(actual[i] - expected[i]) > 0.002f) {
                throw new AssertionError(label + " 输出错误: " + actual[i] + " != " + expected[i]);
            }
        }
    }
}
