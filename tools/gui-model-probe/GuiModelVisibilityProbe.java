package com.shiroha.mmdskin.render.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.shiroha.mmdskin.render.scene.RenderScene;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Uses the production model-view composition path and a real hidden GL context.
 *
 * <p>The test deliberately avoids asserting matrix formulas alone: every result is
 * uploaded to a vertex shader, rasterized, and checked through glReadPixels.</p>
 */
public final class GuiModelVisibilityProbe {
    private static final int SIZE = 64;
    private static final int MIN_VISIBLE_PIXELS = 100;

    private static int program;
    private static int vao;
    private static int vertexBuffer;
    private static int modelViewLocation;
    private static int projectionLocation;

    private GuiModelVisibilityProbe() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path output = Path.of(args.length == 0
                ? "build/diagnostics/1201-camera-gui-euphoria-20261113/gui-gl"
                : args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output);

        check(GLFW.glfwInit(), "GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, 24);

        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "MMD GUI model visibility", 0, 0);
        check(window != 0, "hidden GLFW window creation failed");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            run(output);
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void run(Path output) throws IOException {
        createResources();
        PoseStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushPose();
        try {
            configureMinecraftLikeGuiState(modelViewStack);
            VerifyModel model = new VerifyModel();

            PoseStack displayStack = displayStack(50.0f);
            Result inventory = render("INVENTORY public compose", displayStack, RenderScene.INVENTORY, model);
            Result paperDoll = render("PAPERDOLL public compose", displayStack, RenderScene.PAPERDOLL, model);

            // Reproduces the pre-fix implementation: only the entity PoseStack was uploaded.
            Result oldEntityOnly = renderRaw("old entityPose-only baseline", displayStack);

            // WORLD PoseStack already contains its camera/view transform. A second GUI base
            // multiplication would move it to roughly z=-21950 and clip it completely.
            PoseStack worldStack = displayStack(-10950.0f);
            Result world = render("WORLD no duplicate GUI base", worldStack, RenderScene.WORLD, model);

            check(inventory.visiblePixels >= MIN_VISIBLE_PIXELS,
                    "INVENTORY triangle was clipped: " + inventory.visiblePixels + " pixels");
            check(paperDoll.visiblePixels >= MIN_VISIBLE_PIXELS,
                    "PAPERDOLL triangle was clipped: " + paperDoll.visiblePixels + " pixels");
            check(oldEntityOnly.visiblePixels == 0,
                    "old entityPose-only baseline unexpectedly remained visible: "
                            + oldEntityOnly.visiblePixels + " pixels");
            check(world.visiblePixels >= MIN_VISIBLE_PIXELS,
                    "WORLD accumulated the GUI base twice: " + world.visiblePixels + " pixels");
            check(Arrays.equals(inventory.rgba(), paperDoll.rgba()),
                    "INVENTORY and PAPERDOLL must share the same GUI matrix path");
            check(world.modelView.equals(world.rawEntityPose, 0.00001f),
                    "WORLD model-view changed instead of preserving the supplied entity pose");

            writeImage(output.resolve("inventory.png"), inventory);
            writeImage(output.resolve("paperdoll.png"), paperDoll);
            writeImage(output.resolve("old-entity-pose-only.png"), oldEntityOnly);
            writeImage(output.resolve("world-no-double.png"), world);
            writeSummary(output.resolve("result.txt"), inventory, paperDoll, oldEntityOnly, world);

            int glError = GL46C.glGetError();
            check(glError == GL46C.GL_NO_ERROR, "GL error: 0x" + Integer.toHexString(glError));
            System.out.printf(Locale.ROOT,
                    "PASS GUI GL visibility inventory=%d paperdoll=%d old=%d world=%d output=%s%n",
                    inventory.visiblePixels, paperDoll.visiblePixels,
                    oldEntityOnly.visiblePixels, world.visiblePixels, output);
        } finally {
            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
            deleteResources();
        }
    }

    private static void configureMinecraftLikeGuiState(PoseStack modelViewStack) {
        Matrix4f projection = new Matrix4f()
                .setOrtho(0.0f, SIZE, SIZE, 0.0f, 1000.0f, 21000.0f);
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);

        modelViewStack.setIdentity();
        modelViewStack.translate(0.0f, 0.0f, -11000.0f);
        RenderSystem.applyModelViewMatrix();

        RenderSystem.viewport(0, 0, SIZE, SIZE);
        GL46C.glDepthFunc(GL46C.GL_LEQUAL);
        GL46C.glEnable(GL46C.GL_DEPTH_TEST);
        GL46C.glDepthMask(true);
        GL46C.glDisable(GL46C.GL_CULL_FACE);
        GL46C.glDisable(GL46C.GL_BLEND);
        GL46C.glReadBuffer(GL46C.GL_BACK);
    }

    private static PoseStack displayStack(float z) {
        PoseStack stack = new PoseStack();
        stack.translate(SIZE * 0.5f, SIZE * 0.5f, z);
        stack.scale(12.0f, 12.0f, -12.0f);
        return stack;
    }

    private static Result render(String name, PoseStack stack, RenderScene scene,
                                 VerifyModel model) {
        Matrix4f matrix = new Matrix4f(
                model.composeModelViewMatrix(stack, scene));
        ByteBuffer pixels = drawAndRead(matrix);
        return new Result(name, matrix, new Matrix4f(stack.last().pose()), pixels);
    }

    private static Result renderRaw(String name, PoseStack stack) {
        Matrix4f matrix = new Matrix4f(stack.last().pose());
        return new Result(name, matrix, new Matrix4f(stack.last().pose()), drawAndRead(matrix));
    }

    private static ByteBuffer drawAndRead(Matrix4f modelView) {
        GL46C.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);

        GL46C.glUseProgram(program);
        GL46C.glBindVertexArray(vao);
        float[] projectionValues = new float[16];
        float[] modelViewValues = new float[16];
        RenderSystem.getProjectionMatrix().get(projectionValues);
        modelView.get(modelViewValues);
        GL46C.glUniformMatrix4fv(projectionLocation, false, projectionValues);
        GL46C.glUniformMatrix4fv(modelViewLocation, false, modelViewValues);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        GL46C.glFinish();

        ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
        GL46C.glReadPixels(0, 0, SIZE, SIZE, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, pixels);
        GL46C.glBindVertexArray(0);
        GL46C.glUseProgram(0);
        return pixels;
    }

    private static void createResources() {
        program = compileProgram();
        modelViewLocation = GL46C.glGetUniformLocation(program, "ModelViewMat");
        projectionLocation = GL46C.glGetUniformLocation(program, "ProjMat");
        check(modelViewLocation >= 0 && projectionLocation >= 0, "missing matrix uniforms");

        vao = GL46C.glGenVertexArrays();
        vertexBuffer = GL46C.glGenBuffers();
        GL46C.glBindVertexArray(vao);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, vertexBuffer);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, new float[]{
                -1.0f, -1.0f, 0.0f,
                 1.0f, -1.0f, 0.0f,
                 0.0f,  1.0f, 0.0f
        }, GL46C.GL_STATIC_DRAW);
        GL46C.glEnableVertexAttribArray(0);
        GL46C.glVertexAttribPointer(0, 3, GL46C.GL_FLOAT, false, 0, 0L);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, 0);
        GL46C.glBindVertexArray(0);
    }

    private static int compileProgram() {
        int vertex = compileShader(GL46C.GL_VERTEX_SHADER, """
                #version 330 core
                layout(location = 0) in vec3 Position;
                uniform mat4 ModelViewMat;
                uniform mat4 ProjMat;
                void main() {
                    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                }
                """);
        int fragment = compileShader(GL46C.GL_FRAGMENT_SHADER, """
                #version 330 core
                out vec4 Color;
                void main() {
                    Color = vec4(0.2, 0.8, 0.35, 1.0);
                }
                """);
        int result = GL46C.glCreateProgram();
        GL46C.glAttachShader(result, vertex);
        GL46C.glAttachShader(result, fragment);
        GL46C.glLinkProgram(result);
        GL46C.glDeleteShader(vertex);
        GL46C.glDeleteShader(fragment);
        check(GL46C.glGetProgrami(result, GL46C.GL_LINK_STATUS) == GL46C.GL_TRUE,
                "GL program link failed: " + GL46C.glGetProgramInfoLog(result));
        return result;
    }

    private static int compileShader(int type, String source) {
        int shader = GL46C.glCreateShader(type);
        GL46C.glShaderSource(shader, source);
        GL46C.glCompileShader(shader);
        check(GL46C.glGetShaderi(shader, GL46C.GL_COMPILE_STATUS) == GL46C.GL_TRUE,
                "GL shader compile failed: " + GL46C.glGetShaderInfoLog(shader));
        return shader;
    }

    private static void writeImage(Path path, Result result) throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            int sourceY = SIZE - 1 - y;
            for (int x = 0; x < SIZE; x++) {
                int offset = (sourceY * SIZE + x) * 4;
                int r = result.pixels.get(offset) & 0xff;
                int g = result.pixels.get(offset + 1) & 0xff;
                int b = result.pixels.get(offset + 2) & 0xff;
                int a = result.pixels.get(offset + 3) & 0xff;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        check(ImageIO.write(image, "png", path.toFile()), "PNG writer unavailable");
    }

    private static void writeSummary(Path path, Result inventory, Result paperDoll,
                                     Result oldEntityOnly, Result world) throws IOException {
        String summary = String.format(Locale.ROOT, """
                PASS GUI model visibility probe
                projection: 1.20.1 orthographic, zNear=1000, zFar=21000
                RenderSystem model-view base: z=-11000
                GL vendor: %s
                GL renderer: %s
                GL version: %s
                INVENTORY visible pixels: %d
                PAPERDOLL visible pixels: %d
                old entityPose-only visible pixels: %d (expected 0)
                WORLD visible pixels after no double multiply: %d
                INVENTORY/PAPERDOLL pixels identical: %s
                WORLD model-view equals supplied entity pose: %s
                """,
                glString(GL46C.GL_VENDOR),
                glString(GL46C.GL_RENDERER),
                glString(GL46C.GL_VERSION),
                inventory.visiblePixels,
                paperDoll.visiblePixels,
                oldEntityOnly.visiblePixels,
                world.visiblePixels,
                Arrays.equals(inventory.rgba(), paperDoll.rgba()),
                world.modelView.equals(world.rawEntityPose, 0.00001f));
        Files.writeString(path, summary);
    }

    private static String glString(int name) {
        String value = GL46C.glGetString(name);
        return value == null ? "unknown" : value;
    }

    private static void deleteResources() {
        if (vertexBuffer != 0) GL46C.glDeleteBuffers(vertexBuffer);
        if (vao != 0) GL46C.glDeleteVertexArrays(vao);
        if (program != 0) GL46C.glDeleteProgram(program);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Result {
        final String name;
        final Matrix4f modelView;
        final Matrix4f rawEntityPose;
        final ByteBuffer pixels;
        final int visiblePixels;

        Result(String name, Matrix4f modelView, Matrix4f rawEntityPose,
               ByteBuffer pixels) {
            this.name = name;
            this.modelView = modelView;
            this.rawEntityPose = rawEntityPose;
            this.pixels = pixels;
            this.visiblePixels = countVisible(pixels);
        }

        byte[] rgba() {
            byte[] copy = new byte[SIZE * SIZE * 4];
            pixels.duplicate().get(copy);
            return copy;
        }
    }

    private static int countVisible(ByteBuffer pixels) {
        int count = 0;
        for (int i = 0; i < SIZE * SIZE; i++) {
            int offset = i * 4;
            int rgb = (pixels.get(offset) & 0xff)
                    + (pixels.get(offset + 1) & 0xff)
                    + (pixels.get(offset + 2) & 0xff);
            if (rgb > 8) {
                count++;
            }
        }
        return count;
    }

    private static final class VerifyModel extends BaseModelInstance {
        @Override
        protected void doRenderModel(Entity entity, float yaw, float pitch, Vector3f translation,
                                     PoseStack stack, int light, RenderScene scene) {
            throw new AssertionError("probe must inspect the matrix without issuing a model draw");
        }

        @Override
        protected void onUpdate(float delta) {
            throw new AssertionError("matrix-only probe must not evaluate the model");
        }

        @Override
        public void dispose() {
        }
    }
}
