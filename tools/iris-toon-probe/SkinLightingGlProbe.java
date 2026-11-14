import com.shiroha.mmdskin.render.material.ModelMaterial;
import com.shiroha.mmdskin.render.material.SubMeshDrawHelper;
import com.shiroha.mmdskin.render.outline.SkinGlowPass;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

public final class SkinLightingGlProbe {
    private static final int SIZE = 96;
    private static int framebuffer, texture, normals;
    private static ByteBuffer meshes;
    private static final ModelMaterial[] materials = {new ModelMaterial()};

    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "Skin lighting", 0, 0);
        if (window == 0) throw new IllegalStateException("OpenGL context");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        ToonShaderCpu shader = new ToonShaderCpu();
        try {
            setup();
            check(shader.init(), "production shader compiled");
            configure(shader);
            shadow(shader);
            slopedGlow(shader);
            glow(shader, "vanilla");
            ToonOutputProfile profile = ToonOutputProfile.resolve("fixture", "gbuffers_entities",
                    "albedo.rgb = pow(albedo.rgb, vec3(2.2)); /* DRAWBUFFERS:70 */ "
                            + "gl_FragData[0] = vec4(1.0); gl_FragData[1] = albedo;", new int[]{7, 0});
            check(shader.selectOutputProfile(profile), "linear output profile");
            configure(shader);
            check(shader.getColorOutputEncoding() == 2, "linear output encoding selected");
            glow(shader, "linear-reordered", shader.getColorOutputEncoding());
            check(GL46C.glGetError() == 0, "no GL errors");
            System.out.println("PASS skin lighting: shadow pixels, halo, occlusion, MRT, state restoration");
        } finally {
            shader.cleanup();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void setup() {
        GL46C.glBindVertexArray(GL46C.glGenVertexArrays());
        buffer(0, 3, new float[]{-.25f,-.25f,0, .25f,-.25f,0, .25f,.25f,0, -.25f,.25f,0});
        normals = buffer(1, 3, new float[]{0,0,1, 0,0,1, 0,0,1, 0,0,1});
        buffer(2, 2, new float[8]);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, GL46C.glGenBuffers());
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, new int[]{0,1,2,0,2,3}, GL46C.GL_STATIC_DRAW);
        texture = colorTexture(1, new float[]{.8f,.6f,.4f,1});
        framebuffer = GL46C.glGenFramebuffers();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, framebuffer);
        for (int slot = 0; slot < 2; slot++) {
            int color = colorTexture(SIZE, null);
            GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0 + slot,
                    GL46C.GL_TEXTURE_2D, color, 0);
        }
        int depth = GL46C.glGenRenderbuffers();
        GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, depth);
        GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER, GL46C.GL_DEPTH24_STENCIL8, SIZE, SIZE);
        GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_STENCIL_ATTACHMENT,
                GL46C.GL_RENDERBUFFER, depth);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "FBO");
        GL46C.glViewport(0, 0, SIZE, SIZE);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
        materials[0].name = "顏";
        meshes = BufferUtils.createByteBuffer(20).order(ByteOrder.nativeOrder());
        meshes.putInt(0,0).putInt(4,0).putInt(8,6).putFloat(12,1).put(16,(byte)1).put(17,(byte)1);
    }

    private static void configure(ToonShaderCpu shader) {
        shader.useMain();
        FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
        matrix.put(new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}).flip();
        shader.setProjectionMatrix(matrix);
        shader.setModelViewMatrix(matrix);
        shader.setSampler0(0);
        shader.setLightDirection(0,0,1);
        shader.setToonLevels(4);
        shader.setTransitionSoftness(1.2f);
        shader.setShadowColor(.78f,.84f,.94f);
        shader.setRimLight(1,0);
        shader.setSpecular(96,0);
        shader.setGlobalAlpha(1);
        shader.setEyeEmission(0);
        shader.setSkinEmission(0);
        shader.setBaseBrightness(.55f);
        GL46C.glUniform4f(GL46C.glGetUniformLocation(shader.getMainProgram(), "MaterialColor"), 1,1,1,1);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(shader.getMainProgram(), "MaterialSpecularPower"), 96);
    }

    private static void shadow(ToonShaderCpu shader) {
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        GL46C.glDisable(GL46C.GL_DEPTH_TEST);
        shader.setLightIntensity(0);
        normal(0,0,-1);
        shader.setShadowBrightness(0);
        draw();
        float[] baseline = pixel(48,48);
        shader.setShadowBrightness(.2f);
        draw();
        float[] lifted = pixel(48,48);
        check(lifted[0] > baseline[0] + .05f, "shadow lift survives 55% base brightness and zero illumination");
        shader.setShadowBrightness(1);
        draw();
        close(pixel(48,48)[0], .8f, .002f, "full shadow lift restores material color");
        shader.setLightIntensity(1);
        normal(0,0,1);
        shader.setShadowBrightness(0);
        draw();
        float[] lit = pixel(48,48);
        shader.setShadowBrightness(1);
        draw();
        close(pixel(48,48)[0], lit[0], .0001f, "lit pixels unchanged");
        shader.setShadowBrightness(0);
        System.out.println("PASS shadow: baseline=" + baseline[0] + ", 20%=" + lifted[0]);
    }

    private static void glow(ToonShaderCpu shader, String name) throws Exception {
        glow(shader, name, 0);
    }

    private static void slopedGlow(ToonShaderCpu shader) {
        int positions = GL46C.glGetVertexAttribi(0, GL46C.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, positions);
        GL46C.glBufferSubData(GL46C.GL_ARRAY_BUFFER, 0,
                new float[]{-.25f,-.25f,-.1f, .25f,-.25f,.1f, .25f,.25f,.1f, -.25f,.25f,-.1f});
        GL46C.glClearColor(0, 0, 0, 0);
        GL46C.glDepthMask(true);
        GL46C.glClearDepth(1);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
        shader.setSkinEmission(.2f);
        SkinGlowPass.draw(shader, () -> capture(true), () -> capture(false));
        float left = pixel(39,48)[0], right = pixel(56,48)[0];
        check(left > .1f && right > .1f, "blurred depth must not punch holes in the emitters");
        close(left, right, .01f, "same glow on both sides of the sloped surface");
        GL46C.glBufferSubData(GL46C.GL_ARRAY_BUFFER, 0,
                new float[]{-.25f,-.25f,0, .25f,-.25f,0, .25f,.25f,0, -.25f,.25f,0});
        System.out.println("PASS sloped skin does not occlude its own glow");
    }

    private static void glow(ToonShaderCpu shader, String name, int outputEncoding) throws Exception {
        for (int format : new int[]{GL46C.GL_DEPTH24_STENCIL8, GL46C.GL_DEPTH32F_STENCIL8}) {
            GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER, format, SIZE, SIZE);
            boolean reordered = name.contains("reordered");
            GL46C.glDrawBuffers(reordered
                    ? new int[]{GL46C.GL_COLOR_ATTACHMENT1, GL46C.GL_COLOR_ATTACHMENT0}
                    : new int[]{GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_COLOR_ATTACHMENT1});
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glColorMaski(0,true,true,true,true);
            GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, new float[]{.03f,.03f,.03f,.4f});
            GL46C.glClearBufferfv(GL46C.GL_COLOR, 1, new float[]{.03f,.03f,.03f,.4f});
            GL46C.glDepthMask(true);
            GL46C.glClearBufferfv(GL46C.GL_DEPTH,0,new float[]{1});
            GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
            GL46C.glScissor(0,0,32,SIZE);
            GL46C.glClearBufferfv(GL46C.GL_DEPTH,0,new float[]{.1f});
            GL46C.glScissor(0,0,SIZE,SIZE);
            shader.setSkinEmission(.2f);
            int vao = GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING);
            int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
            SkinGlowPass.draw(shader, () -> capture(true), () -> capture(false));
            check(GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING) == vao, "VAO restored");
            check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM) == program, "program restored");
            check(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == framebuffer, "FBO restored");
            check(GL46C.glGetInteger(GL46C.GL_DRAW_BUFFER1) == (reordered
                    ? GL46C.GL_COLOR_ATTACHMENT0 : GL46C.GL_COLOR_ATTACHMENT1), "MRT restored");
            GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
            float halo = pixel(34,48)[0];
            check(halo > (outputEncoding == 2 ? .035f : .04f),
                    "visible halo outside x=36 skin edge (" + name + "): " + halo);
            close(pixel(29,48)[0], .03f, .0001f, "foreground hides halo");
            close(pixel(48,48)[3], .4f, .0001f, "alpha unchanged");
            float[] pixels = new float[SIZE * SIZE * 4];
            GL46C.glReadPixels(0,0,SIZE,SIZE,GL46C.GL_RGBA,GL46C.GL_FLOAT,pixels);
            Path output = Path.of("build/skin-lighting-diagnostics/" + name + "-" + format + ".png");
            Files.createDirectories(output.getParent());
            BufferedImage image = new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_RGB);
            for (int row = 0; row < SIZE; row++) for (int column = 0; column < SIZE; column++) {
                int offset = (row * SIZE + column) * 4;
                image.setRGB(column,SIZE-row-1, channel(pixels[offset]) << 16
                        | channel(pixels[offset+1]) << 8 | channel(pixels[offset+2]));
            }
            ImageIO.write(image,"png",output.toFile());
            GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT1);
            close(pixel(48,48)[0], .03f, .0001f, "metadata unchanged");
            check(GL46C.glGetError() == 0, "glow pass GL error");
            System.out.println("PASS glow " + name + " depth=" + format + " halo=" + halo);
        }
    }

    private static void capture(boolean depthOnly) {
        SubMeshDrawHelper.drawSkinGlow(meshes,1,4,GL46C.GL_UNSIGNED_INT,
                id -> texture, (id,alpha) -> alpha, materials,null,depthOnly);
    }

    private static void draw() { GL46C.glDrawElements(GL46C.GL_TRIANGLES,6,GL46C.GL_UNSIGNED_INT,0L); }

    private static float[] pixel(int horizontal, int vertical) {
        float[] pixel = new float[4];
        GL46C.glReadPixels(horizontal,vertical,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,pixel);
        return pixel;
    }

    private static int channel(float value) { return Math.round(Math.clamp(value,0,1) * 255); }

    private static void normal(float horizontal, float vertical, float forward) {
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,normals);
        GL46C.glBufferSubData(GL46C.GL_ARRAY_BUFFER,0,new float[]{
                horizontal,vertical,forward, horizontal,vertical,forward,
                horizontal,vertical,forward, horizontal,vertical,forward});
    }

    private static int colorTexture(int size, float[] values) {
        int texture = GL46C.glGenTextures();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,texture);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D,0,GL46C.GL_RGBA32F,size,size,0,
                GL46C.GL_RGBA,GL46C.GL_FLOAT,values);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MIN_FILTER,GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MAG_FILTER,GL46C.GL_NEAREST);
        return texture;
    }

    private static int buffer(int attribute, int size, float[] values) {
        int buffer = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,buffer);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,values,GL46C.GL_DYNAMIC_DRAW);
        GL46C.glEnableVertexAttribArray(attribute);
        GL46C.glVertexAttribPointer(attribute,size,GL46C.GL_FLOAT,false,0,0);
        return buffer;
    }

    private static void close(float actual, float expected, float tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " expected " + expected);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
