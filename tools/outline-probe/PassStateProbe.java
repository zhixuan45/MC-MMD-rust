import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import com.shiroha.mmdskin.render.outline.OutlineRenderPass;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/** 以真实 OutlineRenderPass 和隐藏 OpenGL context 核对 pass 状态。 */
public final class PassStateProbe {
    private static final int ORTHOGRAPHIC = 0;
    private static final int PERSPECTIVE = 1;

    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW 初始化失败");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(32, 32, "outline-pass-state", 0, 0);
        try {
            check(window != 0, "隐藏 OpenGL 窗口");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            int vao = GL46C.glGenVertexArrays();
            GL46C.glBindVertexArray(vao);
            int positions = buffer();
            int normals = buffer();
            GL46C.glEnable(GL46C.GL_DEPTH_TEST);

            exercise(ORTHOGRAPHIC, GL46C.GL_LEQUAL, false, positions, normals, 30, 1, 0, 0, false);
            exercise(ORTHOGRAPHIC, GL46C.GL_GREATER, true, positions, normals, 30, 1, 0, 0, false);
            exercise(PERSPECTIVE, GL46C.GL_LEQUAL, false, positions, normals, 30, 1, 0, 0, false);
            for (float zoom : new float[]{10, 30, 60}) {
                for (float modelScale : new float[]{0.5f, 1, 2}) {
                    exercise(ORTHOGRAPHIC, GL46C.GL_LEQUAL, false, positions, normals,
                            zoom, modelScale, 0.7f, -0.35f, true);
                }
            }
            checkMaterialTextures(positions, normals);
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "OpenGL 状态错误");
            System.out.println("PASS outline pass state and width: orthographic zoom/model-scale mapping; perspective unchanged; finally cleanup");

            GL46C.glDeleteBuffers(positions);
            GL46C.glDeleteBuffers(normals);
            GL46C.glDeleteVertexArrays(vao);
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void exercise(int projectionKind, int initialDepthFunc, boolean throwFromResolver,
                                 int positions, int normals, float guiZoom, float modelScale,
                                 float rotateY, float rotateZ, boolean mirrored) {
        GL46C.glDepthFunc(initialDepthFunc);
        GL46C.glDepthMask(true);
        GL46C.glDisableVertexAttribArray(0);
        GL46C.glDisableVertexAttribArray(1);
        RecordingShader shader = new RecordingShader();
        ByteBuffer mesh = ByteBuffer.allocate(20).order(java.nio.ByteOrder.nativeOrder());
        mesh.putInt(0, 0);
        mesh.putInt(8, 0); // resolver 返 0 alpha，因此无需实际 draw。
        mesh.putFloat(12, 1.0f);
        mesh.put(16, (byte) 1);
        mesh.put(18, (byte) 1);
        FloatBuffer projection = matrix(projectionKind);
        projection.position(3); // 分类必须按当前 position 读取矩阵。
        float rootScale = 0.09f * modelScale;
        FloatBuffer modelView = modelView(guiZoom * rootScale, rotateY, rotateZ, mirrored);
        modelView.position(2);
        OutlineRenderPass.Inputs inputs = new OutlineRenderPass.Inputs(
                positions, normals, positions, mesh, 1, 4, GL46C.GL_UNSIGNED_INT, projection, modelView, rootScale);

        boolean threw = false;
        try {
            OutlineRenderPass.draw(shader, inputs, (material, alpha) -> {
                int during = GL46C.glGetInteger(GL46C.GL_DEPTH_FUNC);
                int expected = projectionKind == ORTHOGRAPHIC ? GL46C.GL_LESS : initialDepthFunc;
                check(during == expected, "pass 内 depth func=" + during + " 期望=" + expected);
                check(GL46C.glGetBoolean(GL46C.GL_DEPTH_TEST), "depth test 应保持开启");
                check(!GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK), "pass 内 depth mask 应关闭");
                if (throwFromResolver) throw new ProbeException();
                return 0.0f;
            }, material -> 0);
        } catch (ProbeException expected) {
            threw = true;
        }
        check(threw == throwFromResolver, "resolver 异常路径");
        check(GL46C.glGetInteger(GL46C.GL_DEPTH_FUNC) == initialDepthFunc, "finally 应恢复原 depth func");
        check(GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK), "finally 应恢复 depth mask");
        check(GL46C.glGetVertexAttribi(0, GL46C.GL_VERTEX_ATTRIB_ARRAY_ENABLED) == 0, "position attribute 应关闭");
        check(GL46C.glGetVertexAttribi(1, GL46C.GL_VERTEX_ATTRIB_ARRAY_ENABLED) == 0, "normal attribute 应关闭");
        check(GL46C.glGetVertexAttribi(2, GL46C.GL_VERTEX_ATTRIB_ARRAY_ENABLED) == 0, "UV attribute 应关闭");
        check(GL46C.glIsEnabled(GL46C.GL_DEPTH_TEST), "depth test 应保持开启");
        float expectedWidth = projectionKind == ORTHOGRAPHIC ? 0.0022f * guiZoom : 0.0022f;
        check(Math.abs(shader.outlineWidth - expectedWidth) < 0.00001f,
                "outline width=" + shader.outlineWidth + " expected=" + expectedWidth);
    }

    private static int buffer() {
        int id = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, id);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, new float[]{0, 0, 0}, GL46C.GL_STATIC_DRAW);
        return id;
    }

    /** 按实际 pass 遍历核对逐材质绑定以及无纹理和异常恢复。 */
    private static void checkMaterialTextures(int positions, int normals) {
        int program = testProgram();
        int elements = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, elements);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, new int[]{0}, GL46C.GL_STATIC_DRAW);
        int[] textures = {GL46C.glGenTextures(), GL46C.glGenTextures(), GL46C.glGenTextures()};
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        for (int texture : textures) GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE3);
        ByteBuffer meshes = ByteBuffer.allocate(60).order(java.nio.ByteOrder.nativeOrder());
        for (int i = 0; i < 3; i++) {
            meshes.putInt(i * 20, i);
            meshes.putFloat(i * 20 + 12, 1);
            meshes.put(i * 20 + 16, (byte) 1);
            meshes.put(i * 20 + 18, (byte) 1);
        }
        FloatBuffer projection = matrix(ORTHOGRAPHIC).position(3);
        FloatBuffer modelView = modelView(1, 0, 0, false).position(2);
        var inputs = new OutlineRenderPass.Inputs(positions, normals, positions, meshes, 3, 4,
                GL46C.GL_UNSIGNED_INT, projection, modelView, 1);
        int[] visits = {0};
        var config = com.shiroha.mmdskin.render.shader.ToonConfig.getInstance();
        config.alphaCutoff = .2f;
        config.hairAlphaCutoff = .3f;
        // 第三材质在 draw 前抛出，前两材质使用合法的零索引绘制。
        var shader = new com.shiroha.mmdskin.render.shader.ToonShaderBase() {
            private int material;
            private boolean mask;
            private float cutoff;
            @Override public void useOutline() { GL46C.glUseProgram(program); }
            @Override public void setOutlineAlphaCutoff(float value) { cutoff = value; }
            @Override public void setOutlineUseTextureAlpha(boolean enabled) { mask = enabled; }
            @Override public void setOutlineAlpha(float alpha) {
                float expected = (material == 0 ? .3f : .2f) / alpha;
                check(Math.abs(cutoff - expected) < .00001f, "发片/全局裁切及有效 alpha 换算");
                check(mask == (material < 2), "无纹理材质必须关闭遮罩");
                if (material < 2) check(GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D) == textures[material], "逐材质纹理绑定");
                visits[0] = ++material;
                if (material == 3) throw new ProbeException();
            }
        };
        boolean threw = false;
        try {
            OutlineRenderPass.draw(shader, inputs, (material, alpha) -> .5f,
                    material -> material < 2 ? textures[material] : 0, material -> material == 0);
        } catch (ProbeException expected) { threw = true; }
        check(threw && visits[0] == 3, "应验证全部材质及无纹理异常路径");
        config.alphaCutoff = config.hairAlphaCutoff = .001f;
        check(GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE) == GL46C.GL_TEXTURE3, "恢复活动纹理单元");
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        check(GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D) == textures[2], "恢复原纹理绑定");
        check(GL46C.glGetVertexAttribi(2, GL46C.GL_VERTEX_ATTRIB_ARRAY_ENABLED) == 0, "异常后清理 UV attribute");
        for (int texture : textures) GL46C.glDeleteTextures(texture);
        GL46C.glUseProgram(0);
        GL46C.glDeleteProgram(program);
        GL46C.glDeleteBuffers(elements);
        System.out.println("PASS per-material outline texture: two bindings, untextured mask disabled, exception restoration");
    }

    private static int testProgram() {
        int vertex = GL46C.glCreateShader(GL46C.GL_VERTEX_SHADER);
        GL46C.glShaderSource(vertex, "#version 330 core\nlayout(location=0) in vec3 p; void main(){gl_Position=vec4(p,1);}");
        GL46C.glCompileShader(vertex);
        check(GL46C.glGetShaderi(vertex, GL46C.GL_COMPILE_STATUS) != 0, GL46C.glGetShaderInfoLog(vertex));
        int fragment = GL46C.glCreateShader(GL46C.GL_FRAGMENT_SHADER);
        GL46C.glShaderSource(fragment, "#version 330 core\nout vec4 c; void main(){c=vec4(1);}");
        GL46C.glCompileShader(fragment);
        check(GL46C.glGetShaderi(fragment, GL46C.GL_COMPILE_STATUS) != 0, GL46C.glGetShaderInfoLog(fragment));
        int program = GL46C.glCreateProgram();
        GL46C.glAttachShader(program, vertex);
        GL46C.glAttachShader(program, fragment);
        GL46C.glLinkProgram(program);
        check(GL46C.glGetProgrami(program, GL46C.GL_LINK_STATUS) != 0, GL46C.glGetProgramInfoLog(program));
        GL46C.glDeleteShader(vertex);
        GL46C.glDeleteShader(fragment);
        return program;
    }

    private static FloatBuffer matrix(int kind) {
        FloatBuffer result = ByteBuffer.allocateDirect(19 * Float.BYTES)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        for (int i = 0; i < 19; i++) result.put(i, 0.0f);
        result.put(3, 1.0f); // leading padding before matrix
        int base = 3;
        result.put(base + 15, kind == ORTHOGRAPHIC ? 1.0f : 0.0f);
        result.put(base + 11, kind == ORTHOGRAPHIC ? 0.0f : -1.0f);
        return result;
    }

    private static FloatBuffer modelView(float totalScale, float rotateY, float rotateZ, boolean mirrored) {
        FloatBuffer result = ByteBuffer.allocateDirect(19 * Float.BYTES)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        for (int i = 0; i < 19; i++) result.put(i, 0.0f);
        int base = 2;
        float cy = (float) Math.cos(rotateY), sy = (float) Math.sin(rotateY);
        float cz = (float) Math.cos(rotateZ), sz = (float) Math.sin(rotateZ);
        result.put(base, totalScale * cy * cz);
        result.put(base + 1, totalScale * cy * sz);
        result.put(base + 2, -totalScale * sy);
        result.put(base + 5, totalScale * -sz);
        result.put(base + 6, totalScale * cz);
        result.put(base + 10, mirrored ? -totalScale : totalScale);
        result.put(base + 15, 1.0f);
        return result;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static final class ProbeException extends RuntimeException { }
    private static final class RecordingShader extends com.shiroha.mmdskin.render.shader.ToonShaderBase {
        private float outlineWidth;
        @Override public void setOutlineWidth(float value) { outlineWidth = value; }
    }
}
