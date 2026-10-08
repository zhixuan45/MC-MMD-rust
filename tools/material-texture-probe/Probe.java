package probe;

import com.shiroha.mmdskin.bridge.runtime.NativeModelLoadPort;
import com.shiroha.mmdskin.bridge.runtime.NativeTexturePort;
import com.shiroha.mmdskin.render.material.MaterialTextureLoader;
import com.shiroha.mmdskin.render.material.ModelMaterial;
import com.shiroha.mmdskin.texture.runtime.TextureRepository;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 无主贴图材质回退的最小真实 OpenGL 探针。 */
public final class Probe {
    private static final float EPS = 0.01f;

    private static final class FakeModel implements NativeModelLoadPort {
        private final String path;
        private final float[] diffuse;
        FakeModel(String path, float[] diffuse) { this.path = path; this.diffuse = diffuse; }
        @Override public long loadPmxModel(String a, String b, long c) { return 1; }
        @Override public long loadPmdModel(String a, String b, long c) { return 1; }
        @Override public long loadVrmModel(String a, String b, long c) { return 1; }
        @Override public String getMaterialTexturePath(long h, int i) { return path; }
        @Override public float[] getMaterialDiffuseColor(long h, int i) { return diffuse; }
    }

    private static final class FakeTexturePort implements NativeTexturePort {
        private final long address = MemoryUtil.nmemAlloc(4);
        FakeTexturePort() { MemoryUtil.memPutByte(address, (byte) 64); MemoryUtil.memPutByte(address + 1, (byte) 64); MemoryUtil.memPutByte(address + 2, (byte) 64); MemoryUtil.memPutByte(address + 3, (byte) 255); }
        @Override public long loadTexture(String filename) { return "shared".equals(filename) ? 1L : 0L; }
        @Override public int textureWidth(long h) { return 1; }
        @Override public int textureHeight(long h) { return 1; }
        @Override public long textureData(long h) { return address; }
        @Override public boolean textureHasAlpha(long h) { return true; }
        @Override public void copyTextureData(ByteBuffer target, long source, int size) { MemoryUtil.memCopy(source, MemoryUtil.memAddress(target), size); }
        @Override public void deleteTexture(long h) { }
        void close() { MemoryUtil.nmemFree(address); }
    }

    public static void main(String[] args) {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW 初始化失败");
        long window = 0L;
        FakeTexturePort texturePort = new FakeTexturePort();
        try {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            window = GLFW.glfwCreateWindow(64, 64, "material-texture-probe", 0, 0);
            if (window == 0L) throw new IllegalStateException("隐藏 GLFW 窗口创建失败");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            TextureRepository.Init();
            TextureRepository.configureRuntimeCollaborators(texturePort);
            runMaterialCases();
            runStateAndSharedCases();
            runSamplerAlphaCase();
            System.out.println("PASS material-texture-probe");
        } finally {
            // 探针进程即将退出，避免无 Minecraft RenderSystem 类时走缓存清理路径。
            texturePort.close();
            if (window != 0L) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void runMaterialCases() {
        assertColor(new float[]{0.5f, 0.5f, 0.5f, 0.5f}, "gray");
        assertColor(new float[]{1.0f, 0.0f, 0.0f, 1.0f}, "red");
        assertColor(new float[]{0.2f, 0.3f, 0.4f, 0.0f}, "transparent");
        assertColor(new float[]{0.2f, 0.3f, 0.4f, 1.0f}, "opaque");
        ModelMaterial failed = MaterialTextureLoader.loadMaterial(new FakeModel("missing", new float[]{1, 0, 0, 1}), 1, 0, new ArrayList<>());
        check(failed.tex == 0 && !failed.ownsTexture, "非空失败路径必须保留 tex=0");
    }

    private static void assertColor(float[] expected, String name) {
        List<String> keys = new ArrayList<>();
        ModelMaterial material = MaterialTextureLoader.loadMaterial(new FakeModel("", expected), 1, 0, keys);
        check(material.tex > 0 && material.ownsTexture && material.hasAlpha, name + " fallback 纹理未创建");
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, material.tex);
        GL46C.glGetTexImage(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, pixel);
        for (int i = 0; i < 4; i++) check(Math.abs((pixel.get(i) & 255) / 255f - expected[i]) <= 1.0f / 255f + EPS, name + " 像素通道 " + i);
        MaterialTextureLoader.releaseOwnedTextures(new ModelMaterial[]{material});
        MaterialTextureLoader.releaseOwnedTextures(new ModelMaterial[]{material});
        check(material.tex == 0 && !material.ownsTexture, name + " 重复释放不安全");
    }

    private static void runStateAndSharedCases() {
        GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, 0);
        GL46C.glPixelStorei(GL46C.GL_UNPACK_ALIGNMENT, 8);
        GL46C.glPixelStorei(GL46C.GL_UNPACK_ROW_LENGTH, 7);
        ModelMaterial m = MaterialTextureLoader.loadMaterial(new FakeModel("", new float[]{.25f, .5f, .75f, 1}), 1, 0, new ArrayList<>());
        check(GL46C.glGetInteger(GL46C.GL_UNPACK_ALIGNMENT) == 8, "UNPACK_ALIGNMENT 未恢复");
        check(GL46C.glGetInteger(GL46C.GL_UNPACK_ROW_LENGTH) == 7, "UNPACK_ROW_LENGTH 未恢复");
        MaterialTextureLoader.releaseOwnedTextures(new ModelMaterial[]{m});

        List<String> keys = new ArrayList<>();
        ModelMaterial shared = MaterialTextureLoader.loadMaterial(new FakeModel("shared", new float[]{1, 0, 0, 1}), 1, 0, keys);
        check(shared.tex > 0 && !shared.ownsTexture && keys.size() == 1, "共享贴图加载失败");
        int id = shared.tex;
        MaterialTextureLoader.releaseOwnedTextures(new ModelMaterial[]{shared});
        check(shared.tex == id, "共享贴图被 owner 释放");
        TextureRepository.releaseAll(keys);
    }

    private static void runSamplerAlphaCase() {
        try {
            String frag = Files.readString(Path.of("common/src/main/resources/assets/mmdskin/shader/toon_main_body.frag.glsl"))
                    .replace("/* TOON_OUTPUT_DECLARATIONS */", "layout(location=0) out vec4 probeColor;")
                    .replace("/* TOON_OUTPUT_WRITER */", "#define writeToonOutputs(c,a,n,al) probeColor=vec4(c,al)");
            int program = link("#version 330 core\nout vec2 texCoord0; out vec3 viewNormal; out vec3 viewPos; out vec3 viewLightDir; void main(){const vec2 p[3]=vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3));gl_Position=vec4(p[gl_VertexID],0,1);texCoord0=vec2(.5);viewNormal=vec3(0,0,1);viewPos=vec3(0,0,-1);viewLightDir=vec3(0,0,1);}", frag);
            int fbo = GL46C.glGenFramebuffers(), color = GL46C.glGenTextures();
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, color); GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MIN_FILTER,GL46C.GL_LINEAR); GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MAG_FILTER,GL46C.GL_LINEAR); GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D,0,GL46C.GL_RGBA8,1,1,0,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo); GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER,GL46C.GL_COLOR_ATTACHMENT0,GL46C.GL_TEXTURE_2D,color,0);
            check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "Toon probe FBO 不完整");
            for (float alpha : new float[]{0.0f, 0.5f, 1.0f}) {
                ModelMaterial material = MaterialTextureLoader.loadMaterial(new FakeModel("", new float[]{.5f,.5f,.5f,alpha}),1,0,new ArrayList<>());
                GL46C.glViewport(0,0,1,1); GL46C.glUseProgram(program); GL46C.glActiveTexture(GL46C.GL_TEXTURE0); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,material.tex); ByteBuffer texCheck=ByteBuffer.allocateDirect(4); GL46C.glGetTexImage(GL46C.GL_TEXTURE_2D,0,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,texCheck); System.out.println("texture alpha="+(texCheck.get(3)&255));
                GL46C.glUniform1i(GL46C.glGetUniformLocation(program,"Sampler0"),0); GL46C.glUniform1f(GL46C.glGetUniformLocation(program,"LightIntensity"),1); GL46C.glUniform1i(GL46C.glGetUniformLocation(program,"ToonLevels"),2); GL46C.glUniform1f(GL46C.glGetUniformLocation(program,"RimPower"),1); GL46C.glUniform1f(GL46C.glGetUniformLocation(program,"RimIntensity"),0); GL46C.glUniform3f(GL46C.glGetUniformLocation(program,"ShadowColor"),1,1,1); GL46C.glUniform1f(GL46C.glGetUniformLocation(program,"SpecularPower"),1); GL46C.glUniform1f(GL46C.glGetUniformLocation(program,"SpecularIntensity"),0); GL46C.glUniform1f(GL46C.glGetUniformLocation(program,"AlphaCutoff"),.1f); GL46C.glUniform1f(GL46C.glGetUniformLocation(program,"GlobalAlpha"),1);
                GL46C.glDrawArrays(GL46C.GL_TRIANGLES,0,3); ByteBuffer out=ByteBuffer.allocateDirect(4); GL46C.glReadPixels(0,0,1,1,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,out); int actualAlpha=out.get(3)&255; System.out.println("Toon alpha="+alpha+" read="+actualAlpha); check(alpha < .1f ? actualAlpha==0 : actualAlpha>0, "Toon alpha="+alpha+" 输出错误"); MaterialTextureLoader.releaseOwnedTextures(new ModelMaterial[]{material});
            }
            GL46C.glDeleteProgram(program); GL46C.glDeleteFramebuffers(fbo); GL46C.glDeleteTextures(color);
            System.out.println("PASS actual Toon fragment alpha=0/0.5/1");
        } catch (Exception e) { throw new IllegalStateException("Toon shader probe 失败", e); }
    }

    private static int link(String vertexSource, String fragmentSource) {
        int v=GL46C.glCreateShader(GL46C.GL_VERTEX_SHADER); GL46C.glShaderSource(v,vertexSource); GL46C.glCompileShader(v); check(GL46C.glGetShaderi(v,GL46C.GL_COMPILE_STATUS)!=0,GL46C.glGetShaderInfoLog(v));
        int f=GL46C.glCreateShader(GL46C.GL_FRAGMENT_SHADER); GL46C.glShaderSource(f,fragmentSource); GL46C.glCompileShader(f); check(GL46C.glGetShaderi(f,GL46C.GL_COMPILE_STATUS)!=0,GL46C.glGetShaderInfoLog(f));
        int p=GL46C.glCreateProgram(); GL46C.glAttachShader(p,v); GL46C.glAttachShader(p,f); GL46C.glLinkProgram(p); check(GL46C.glGetProgrami(p,GL46C.GL_LINK_STATUS)!=0,GL46C.glGetProgramInfoLog(p)); GL46C.glDeleteShader(v); GL46C.glDeleteShader(f); return p;
    }

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
