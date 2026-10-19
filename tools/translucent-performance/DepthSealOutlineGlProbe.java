package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.config.AbstractMmdSkinConfig;
import com.shiroha.mmdskin.config.ConfigData;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.render.outline.OutlineCoverage;
import com.shiroha.mmdskin.render.outline.OutlineRenderPass;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** 主体混合后绘制后方白描边，验证半透明深度覆盖与真孔洞。 */
public final class DepthSealOutlineGlProbe {
    private static final int SIZE = 60;

    private static final class ProbeConfig extends AbstractMmdSkinConfig {
        ProbeConfig(ConfigData data) { super(data); }
        @Override public String getMobModelReplacement(String entityTypeId) { return ""; }
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static int texture(byte[] pixels, int width) {
        int id = GL46C.glGenTextures();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
        ByteBuffer data = buffer(pixels.length).put(pixels); data.flip();
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, width, 1, 0,
                GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, data);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, GL46C.GL_CLAMP_TO_EDGE);
        return id;
    }

    private static ByteBuffer run(int begin) {
        ByteBuffer data = buffer(20);
        data.putInt(0).putInt(begin).putInt(3).putFloat(1).put((byte) 1).put((byte) 1);
        return data;
    }

    private static float[] readColor(int x) {
        float[] color = new float[4];
        GL46C.glReadPixels(x, SIZE / 2, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, color);
        return color;
    }

    private static void scenario(boolean batch, boolean gpu, boolean layers, boolean sampled, boolean disableSeal) {
        System.setProperty("mmdskin.translucentBatch", Boolean.toString(batch));
        System.setProperty("mmdskin.toonTransparencyLayers", Boolean.toString(layers));
        ConfigData config = new ConfigData();
        config.toonDisableDepthSeal = disableSeal;
        config.toonRenderingEnabled = true; config.toonOutlineEnabled = true;
        config.toonOutlineFollowLighting = false;
        config.toonOutlineR = config.toonOutlineG = config.toonOutlineB = 1;
        ConfigManager.init(new ProbeConfig(config));
        ToonShaderCpu shader = new ToonShaderCpu();
        check(shader.init(), "Toon shader init");
        int vao = GL46C.glGenVertexArrays(), ebo = GL46C.glGenBuffers();
        int positions = GL46C.glGenBuffers(), normals = GL46C.glGenBuffers(), uvs = GL46C.glGenBuffers();
        GL46C.glBindVertexArray(vao);
        ByteBuffer xyz = buffer(72);
        // 前方发片与后方反向外壳覆盖相同像素；外壳不能穿过可见发片。
        for (float z : new float[]{-.4f, .2f}) {
            xyz.putFloat(-1).putFloat(-1).putFloat(z).putFloat(3).putFloat(-1).putFloat(z)
                    .putFloat(-1).putFloat(3).putFloat(z);
        }
        xyz.flip();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, positions);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, xyz, GL46C.GL_STATIC_DRAW);
        GL46C.glEnableVertexAttribArray(0); GL46C.glVertexAttribPointer(0, 3, GL46C.GL_FLOAT, false, 0, 0);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, normals);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, new float[]{0,0,1, 0,0,1, 0,0,1,
                0,0,-1, 0,0,-1, 0,0,-1}, GL46C.GL_STATIC_DRAW);
        GL46C.glEnableVertexAttribArray(1); GL46C.glVertexAttribPointer(1, 3, GL46C.GL_FLOAT, false, 0, 0);
        ByteBuffer uv = buffer(48);
        for (int i = 0; i < 2; i++) uv.putFloat(0).putFloat(0).putFloat(2).putFloat(0).putFloat(0).putFloat(2);
        uv.flip();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, uvs);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, uv, GL46C.GL_STATIC_DRAW);
        GL46C.glEnableVertexAttribArray(2); GL46C.glVertexAttribPointer(2, 2, GL46C.GL_FLOAT, false, 0, 0);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, new int[]{0,1,2, 3,5,4}, GL46C.GL_STATIC_DRAW);
        int atlas = texture(new byte[]{20,30,40,0, 20,30,40,(byte)128, 20,30,40,(byte)255}, 3);
        int white = texture(new byte[]{-1,-1,-1,-1}, 1);
        int fbo = 0, color = 0, depth = 0;
        if (sampled) {
            fbo = GL46C.glGenFramebuffers(); GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
            color = GL46C.glGenTextures(); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, color);
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, SIZE, SIZE, 0,
                    GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, 0L);
            GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, color, 0);
            depth = GL46C.glGenRenderbuffers(); GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, depth);
            GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER, GL46C.GL_DEPTH_COMPONENT24, SIZE, SIZE);
            GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_RENDERBUFFER, depth);
            check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "FBO");
        }
        try (TranslucentDrawOrder order = new TranslucentDrawOrder()) {
            BatchGlProbe.setup(shader);
            shader.setOutlineFollowLighting(false);
            GL46C.glViewport(0, 0, SIZE, SIZE);
            RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL46C.GL_LEQUAL); RenderSystem.depthMask(true);
            RenderSystem.enableBlend(); RenderSystem.blendFuncSeparate(770, 771, 1, 771);
            GL46C.glClearColor(.1f, .1f, .1f, 0);
            GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
            ModelMaterial material = new ModelMaterial();
            material.name = "後髪"; material.tex = atlas;
            material.hasAlpha = material.hasTranslucentPixels = true;
            FloatBuffer matrix = buffer(64).asFloatBuffer(); new Matrix4f().get(matrix);
            TranslucentDrawOrder.Inputs input = gpu
                    ? TranslucentDrawOrder.Inputs.gpu(positions, 6, matrix, null, 1, false, uv, 0, 0, false)
                    : TranslucentDrawOrder.Inputs.cpu(xyz, 0, 6, matrix, null, 1, false, uv, 0, false);
            SubMeshDrawHelper.draw(run(0), 1, 4, GL46C.GL_UNSIGNED_INT, id -> atlas, (id,a) -> a,
                    new ModelMaterial[]{material}, null, false, id -> {}, order, input);
            float[] before = readColor(30);
            float[] solidBefore = readColor(50);
            float[] z = new float[1];
            GL46C.glReadPixels(30, SIZE / 2, 1, 1, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, z);
            check(before[3] > .49f && before[3] < .51f, "translucent alpha preserved with either depth setting");
            float[] opaqueDepth = new float[1];
            GL46C.glReadPixels(50, SIZE / 2, 1, 1, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, opaqueDepth);
            check(Math.abs(opaqueDepth[0] - .3f) < .00001f, "opaque pass must always write depth");
            OutlineRenderPass.draw(shader, new OutlineRenderPass.Inputs(positions, normals, uvs, run(3),
                    1, 4, GL46C.GL_UNSIGNED_INT, matrix, matrix, 1), (id,a) -> a, id -> white);
            float[] after = readColor(30), hole = readColor(10), solid = readColor(50);
            String tag = "batch=" + batch + " gpu=" + gpu + " layers=" + layers + " sampled=" + sampled + " disableSeal=" + disableSeal;
            System.out.println(tag + " depth=" + z[0] + " hair before=" + java.util.Arrays.toString(before)
                    + " after=" + java.util.Arrays.toString(after));
            for (int c = 0; c < 4; c++) {
                if (!disableSeal)
                    check(Math.abs(before[c] - after[c]) <= .005f, "rear white outline overwrote translucent hair: " + tag);
                check(Math.abs(solidBefore[c] - solid[c]) <= .005f, "opaque hair occlusion: " + tag);
            }
            check(Math.abs(z[0] - (disableSeal ? 1.0f : .3f)) < .00001f,
                    "depth seal toggle changes only translucent depth: " + tag);
            if (disableSeal) check(after[0] > .95f && after[3] > .95f, "rear outline demonstrates missing depth seal");
            check(hole[0] > .95f && hole[3] > .95f, "true alpha hole must remain open: " + tag);
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL error: " + tag);
            System.out.println("PASS depth seal + production outline: " + tag);
        } finally {
            shader.cleanup(); OutlineCoverage.release();
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, 0);
            GL46C.glDeleteTextures(new int[]{atlas, white, color});
            GL46C.glDeleteRenderbuffers(depth); GL46C.glDeleteFramebuffers(fbo);
            GL46C.glDeleteBuffers(new int[]{positions, normals, uvs, ebo}); GL46C.glDeleteVertexArrays(vao);
            ConfigManager.init(null);
        }
    }

    public static void main(String[] args) {
        check(GLFW.glfwInit(), "GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, 24);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "depth seal outline regression", 0, 0);
        check(window != 0, "hidden GL window");
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            for (boolean batch : new boolean[]{false, true})
                for (boolean gpu : new boolean[]{false, true})
                    for (boolean sampled : new boolean[]{false, true})
                        for (boolean layers : new boolean[]{false, true})
                            for (boolean disableSeal : new boolean[]{false, true}) scenario(batch, gpu, layers, sampled, disableSeal);
        } finally {
            System.clearProperty("mmdskin.translucentBatch");
            System.clearProperty("mmdskin.toonTransparencyLayers");
            GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate();
        }
    }
}
