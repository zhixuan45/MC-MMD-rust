import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 用纸娃娃 GUI 矩阵复现共面 hull 对薄片主体的覆盖。 */
public final class PaperDollProbe {
    private static final int SIZE = 256;
    private static final int CENTER = SIZE / 2;
    private static final int RASTER_SCALE = 4;
    private static final int BUFFER_SIZE = SIZE * RASTER_SCALE;
    private static final float SCALE = 2.7f;

    public static void main(String[] args) throws Exception {
        boolean baseline = args.length > 0 && "--baseline".equals(args[0]);
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW 初始化失败");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "paperdoll-outline-probe", 0, 0);
        try {
            check(window != 0, "隐藏 OpenGL 窗口");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            int fbo = createFramebuffer();
            int vao = GL46C.glGenVertexArrays();
            GL46C.glBindVertexArray(vao);
            int mesh = createThinDoubleSidedPanel();
            int baseProgram = createProgram(BASE_VERTEX, BASE_FRAGMENT);
            if (baseline) {
                Matrices matrices = paperDollMatrices();
                System.out.printf("GUI baseline: viewDepth=%.1f distanceFade=%.3f modelViewDet=%.4f frontFace=%s%n",
                        matrices.viewDepth, matrices.distanceFade, matrices.det3,
                        GL46C.glGetInteger(GL46C.GL_FRONT_FACE) == GL46C.GL_CCW ? "CCW" : "CW");
                int[] less = runCase(fbo, vao, mesh, baseProgram, matrices, GL46C.GL_LESS,
                        GL46C.GL_LESS, "baseline", 0.0022f, true);
                int[] lequal = runCase(fbo, vao, mesh, baseProgram, matrices, GL46C.GL_LEQUAL,
                        GL46C.GL_LEQUAL, "baseline", 0.0022f, true);
                System.out.printf("LESS: bodyWhite=%d bodyBlack=%d exteriorOutline=%d%n", less[0], less[1], less[2]);
                System.out.printf("LEQUAL: bodyWhite=%d bodyBlack=%d exteriorOutline=%d%n", lequal[0], lequal[1], lequal[2]);
                check(matrices.distanceFade == 0.0f, "基线应触发 GUI 距离衰减归零");
                check(less[1] == 0, "LESS 基线不应让共面 hull 覆盖主体");
                check(lequal[1] > SIZE, "LEQUAL 基线应复现共面 hull 涂黑主体");
                System.out.println("PASS paperdoll baseline: zero expansion + LEQUAL admits coplanar back surface");
            } else {
                Matrices matrices = paperDollMatrices();
                System.out.printf("GUI fixed: viewDepth=%.1f modelViewDet=%.4f body=LEQUAL outline=LESS%n",
                        matrices.viewDepth, matrices.det3);
                float paperDollWidth = 0.0022f * 30.0f;
                System.out.printf("GUI outline uniform=%.4f (0.0022 x GUI zoom 30), raster=%dx%n",
                        paperDollWidth, RASTER_SCALE);
                int[] fixed = runCase(fbo, vao, mesh, baseProgram, matrices, GL46C.GL_LEQUAL,
                        GL46C.GL_LESS, "production", paperDollWidth, false);
                printCandidate("production orthographic pass", fixed);
                int[] box = runCase(fbo, vao, createPanelWithSides(), baseProgram, matrices,
                        GL46C.GL_LEQUAL, GL46C.GL_LESS, "production", paperDollWidth, false);
                printCandidate("production box with side strips", box);
                check(fixed[1] == 0, "修复回归：主体内部不得被描边涂黑");
                int[] boxScan = scanSubpixelOffsets(fbo, vao, createPanelWithSides(), baseProgram,
                        matrices, paperDollWidth);
                System.out.printf("4x subpixel scan: bodyBlackMax=%d exteriorOutlineMax=%d%n",
                        boxScan[1], boxScan[2]);
                check(boxScan[1] == 0, "修复回归：子像素平移后主体内部不得被涂黑");
                check(boxScan[2] > 0, "修复回归：子像素扫描应检出真实外缘描边");
                System.out.println("PASS paperdoll outline regression: thin-panel interior preserved; supersampled exterior edge detected");
            }
            verifyPerspectiveParity(fbo, vao, mesh);
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "OpenGL 状态错误");
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static int[] runCase(int fbo, int vao, int mesh, int baseProgram, Matrices matrices,
                                 int bodyDepth, int outlineDepth, String variant, float width,
                                 boolean sourceSnapshot) throws Exception {
        int outline = createOutlineProgram(variant, sourceSnapshot);
        int[] result = render(fbo, vao, mesh, baseProgram, outline, matrices, bodyDepth, outlineDepth, width);
        GL46C.glDeleteProgram(outline);
        return result;
    }

    private static void printCandidate(String label, int[] result) {
        System.out.printf("%s: bodyWhite=%d bodyBlack=%d exteriorOutline=%d%n",
                label, result[0], result[1], result[2]);
    }

    private static int[] scanSubpixelOffsets(int fbo, int vao, int mesh, int baseProgram,
                                             Matrices matrices, float width) throws Exception {
        int outline = createOutlineProgram("production", false);
        int maxWhite = 0, maxBlack = 0, maxExterior = 0;
        // 1/16 GUI-unit increments are quarter-pixel steps in this 4x raster.
        for (int yi = 0; yi < 4; yi++) for (int xi = 0; xi < 4; xi++) {
            float[] model = matrices.modelView.clone();
            model[12] += xi * 0.0625f;
            model[13] += yi * 0.0625f;
            Matrices shifted = new Matrices(matrices.projection, model, matrices.viewDepth,
                    matrices.distanceFade, matrices.det3);
            int[] result = render(fbo, vao, mesh, baseProgram, outline, shifted,
                    GL46C.GL_LEQUAL, GL46C.GL_LESS, width);
            maxWhite = Math.max(maxWhite, result[0]);
            maxBlack = Math.max(maxBlack, result[1]);
            maxExterior = Math.max(maxExterior, result[2]);
        }
        GL46C.glDeleteProgram(outline);
        return new int[]{maxWhite, maxBlack, maxExterior};
    }

    private static void verifyPerspectiveParity(int fbo, int vao, int mesh) throws Exception {
        int legacy = createOutlineProgram("baseline", true);
        int current = createOutlineProgram("production", false);
        for (float depth : new float[]{2f, 10f, 30f, 45f}) {
            for (float width : new float[]{0.0022f, 0.01f}) {
                ByteBuffer a = renderPerspectiveFrame(fbo, vao, mesh, legacy, depth, width);
                ByteBuffer b = renderPerspectiveFrame(fbo, vao, mesh, current, depth, width);
                int differences = 0;
                for (int i = 0; i < a.capacity(); i++) if (a.get(i) != b.get(i)) differences++;
                int visible = 0;
                for (int i = 0; i < a.capacity(); i += 4) {
                    if ((a.get(i) & 255) < 15 && (a.get(i + 1) & 255) < 15
                            && (a.get(i + 2) & 255) < 15) visible++;
                }
                check(visible > 0, "透视对照必须包含实际描边输出，不能仅比较空背景");
                check(differences == 0, "透视描边变更超出 GUI 分支：depth=" + depth
                        + " width=" + width + " diffBytes=" + differences);
                System.out.printf("Perspective parity depth=%.1f width=%.4f diffBytes=%d outlinePixels=%d%n",
                        depth, width, differences, visible);
            }
        }
        GL46C.glDeleteProgram(legacy); GL46C.glDeleteProgram(current);
    }

    private static ByteBuffer renderPerspectiveFrame(int fbo, int vao, int count, int program,
                                                    float depth, float width) {
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        GL46C.glViewport(0, 0, BUFFER_SIZE, BUFFER_SIZE);
        GL46C.glClearColor(1, 0, 1, 1);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glDisable(GL46C.GL_DEPTH_TEST);
        GL46C.glDepthMask(false);
        GL46C.glEnable(GL46C.GL_CULL_FACE);
        GL46C.glCullFace(GL46C.GL_FRONT);
        GL46C.glUseProgram(program);
        float[] projection = perspectiveMatrix(1.04719755f, 0.1f, 100f);
        float[] modelView = translation(0, 0, -depth);
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(program, "ProjMat"), false, projection);
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(program, "ModelViewMat"), false, modelView);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(program, "OutlineWidth"), width);
        GL46C.glUniform3f(GL46C.glGetUniformLocation(program, "OutlineColor"), 0, 0, 0);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(program, "OutlineAlpha"), 1);
        GL46C.glBindVertexArray(vao);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, count);
        GL46C.glFinish();
        ByteBuffer pixels = ByteBuffer.allocateDirect(BUFFER_SIZE * BUFFER_SIZE * 4);
        GL46C.glReadPixels(0, 0, BUFFER_SIZE, BUFFER_SIZE, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, pixels);
        GL46C.glDepthMask(true);
        GL46C.glDisable(GL46C.GL_CULL_FACE);
        return pixels;
    }

    private static float[] perspectiveMatrix(float fovy, float near, float far) {
        float f = (float)(1.0 / Math.tan(fovy * 0.5));
        float[] m = new float[16];
        m[0] = f; m[5] = f; m[10] = -(far + near) / (far - near);
        m[11] = -1f; m[14] = -(2f * far * near) / (far - near);
        return m;
    }

    private static int createFramebuffer() {
        int fbo = GL46C.glGenFramebuffers();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        int color = GL46C.glGenTextures();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, color);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, BUFFER_SIZE, BUFFER_SIZE, 0,
                GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0,
                GL46C.GL_TEXTURE_2D, color, 0);
        int depth = GL46C.glGenRenderbuffers();
        GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER, depth);
        GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER, GL46C.GL_DEPTH_COMPONENT24, BUFFER_SIZE, BUFFER_SIZE);
        GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT,
                GL46C.GL_RENDERBUFFER, depth);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE,
                "GL_DEPTH_COMPONENT24 FBO 完整性");
        return fbo;
    }

    private static int createThinDoubleSidedPanel() {
        // 两个反向面完全共面：主 pass 双面白色；hull pass 的背面可暴露深度函数差异。
        float[] positions = {
                -12,-12,0, 12,-12,0, 12,12,0, -12,-12,0, 12,12,0, -12,12,0,
                -12,-12,0, -12,12,0, 12,12,0, -12,-12,0, 12,12,0, 12,-12,0
        };
        float[] normals = {
                0,0,1, 0,0,1, 0,0,1, 0,0,1, 0,0,1, 0,0,1,
                0,0,-1, 0,0,-1, 0,0,-1, 0,0,-1, 0,0,-1, 0,0,-1
        };
        check(normals.length == positions.length, "薄片 positions/normals 顶点数必须相同");
        int position = buffer(0, 3, positions);
        int normal = buffer(1, 3, normals);
        return 12;
    }

    private static int createPanelWithSides() {
        List<Float> positions = new ArrayList<>(), normals = new ArrayList<>();
        addDoubleFace(positions, normals, new float[][]{
                {-12,-12,0.04f},{12,-12,0.04f},{12,12,0.04f},{-12,12,0.04f}}, 0,0,1);
        addDoubleFace(positions, normals, new float[][]{
                {-12,-12,-0.04f},{-12,12,-0.04f},{12,12,-0.04f},{12,-12,-0.04f}}, 0,0,-1);
        addDoubleFace(positions, normals, new float[][]{
                {12,-12,-0.04f},{12,12,-0.04f},{12,12,0.04f},{12,-12,0.04f}}, 1,0,0);
        addDoubleFace(positions, normals, new float[][]{
                {-12,-12,-0.04f},{-12,-12,0.04f},{-12,12,0.04f},{-12,12,-0.04f}}, -1,0,0);
        addDoubleFace(positions, normals, new float[][]{
                {-12,12,-0.04f},{-12,12,0.04f},{12,12,0.04f},{12,12,-0.04f}}, 0,1,0);
        addDoubleFace(positions, normals, new float[][]{
                {-12,-12,-0.04f},{12,-12,-0.04f},{12,-12,0.04f},{-12,-12,0.04f}}, 0,-1,0);
        float[] p = toArray(positions), n = toArray(normals);
        check(p.length == n.length, "盒体 positions/normals 顶点数必须相同");
        buffer(0, 3, p); buffer(1, 3, n);
        return p.length / 3;
    }

    private static void addDoubleFace(List<Float> positions, List<Float> normals, float[][] corners,
                                      float nx, float ny, float nz) {
        int[] order = {0,1,2,0,2,3, 2,1,0,3,2,0};
        for (int i = 0; i < order.length; i++) {
            float[] point = corners[order[i]];
            positions.add(point[0]); positions.add(point[1]); positions.add(point[2]);
            float sign = i < 6 ? 1f : -1f;
            normals.add(nx * sign); normals.add(ny * sign); normals.add(nz * sign);
        }
    }

    private static float[] toArray(List<Float> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }

    private static int buffer(int location, int size, float[] values) {
        int id = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, id);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, values, GL46C.GL_STATIC_DRAW);
        GL46C.glEnableVertexAttribArray(location);
        GL46C.glVertexAttribPointer(location, size, GL46C.GL_FLOAT, false, 0, 0);
        return id;
    }

    private static int[] render(int fbo, int vao, int count, int baseProgram, int outlineProgram,
                                Matrices matrices, int bodyDepth, int outlineDepth, float width) {
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        GL46C.glViewport(0, 0, BUFFER_SIZE, BUFFER_SIZE);
        GL46C.glDisable(GL46C.GL_BLEND);
        GL46C.glDisable(GL46C.GL_CULL_FACE);
        GL46C.glEnable(GL46C.GL_DEPTH_TEST);
        GL46C.glDepthFunc(bodyDepth);
        GL46C.glDepthMask(true);
        GL46C.glClearColor(1, 0, 1, 1);
        GL46C.glClearDepth(1.0);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT | GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glBindVertexArray(vao);
        GL46C.glUseProgram(baseProgram);
        matrices.upload(baseProgram);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, count);
        ByteBuffer baseMask = ByteBuffer.allocateDirect(BUFFER_SIZE * BUFFER_SIZE * 4);
        GL46C.glReadPixels(0, 0, BUFFER_SIZE, BUFFER_SIZE, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, baseMask);

        GL46C.glUseProgram(outlineProgram);
        matrices.upload(outlineProgram);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(outlineProgram, "OutlineWidth"), width);
        GL46C.glUniform3f(GL46C.glGetUniformLocation(outlineProgram, "OutlineColor"), 0, 0, 0);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(outlineProgram, "OutlineAlpha"), 1);
        GL46C.glDepthFunc(outlineDepth);
        GL46C.glDepthMask(false);
        GL46C.glEnable(GL46C.GL_CULL_FACE);
        GL46C.glCullFace(GL46C.GL_FRONT);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, count);
        GL46C.glFinish();
        ByteBuffer pixels = ByteBuffer.allocateDirect(BUFFER_SIZE * BUFFER_SIZE * 4);
        GL46C.glReadPixels(0, 0, BUFFER_SIZE, BUFFER_SIZE, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, pixels);
        int white = 0, black = 0, exterior = 0;
        for (int y = 0; y < BUFFER_SIZE; y++) for (int x = 0; x < BUFFER_SIZE; x++) {
            int offset = (y * BUFFER_SIZE + x) * 4;
            int r = pixels.get(offset) & 255, g = pixels.get(offset + 1) & 255, b = pixels.get(offset + 2) & 255;
            int baseOffset = offset;
            boolean body = (baseMask.get(baseOffset) & 255) > 240
                    && (baseMask.get(baseOffset + 1) & 255) > 240
                    && (baseMask.get(baseOffset + 2) & 255) > 240;
            boolean blackPixel = r < 15 && g < 15 && b < 15;
            if (body && r > 240 && g > 240 && b > 240) white++;
            if (body && blackPixel) black++;
            if (!body && blackPixel && isNearBody(baseMask, x, y, 4 * RASTER_SCALE)) exterior++;
        }
        GL46C.glDepthMask(true);
        GL46C.glDisable(GL46C.GL_CULL_FACE);
        return new int[]{white, black, exterior};
    }

    private static boolean isNearBody(ByteBuffer mask, int x, int y, int radius) {
        for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++) {
            if (dx * dx + dy * dy > radius * radius) continue;
            int px = x + dx, py = y + dy;
            if (px < 0 || py < 0 || px >= BUFFER_SIZE || py >= BUFFER_SIZE) continue;
            int offset = (py * BUFFER_SIZE + px) * 4;
            if ((mask.get(offset) & 255) > 240 && (mask.get(offset + 1) & 255) > 240
                    && (mask.get(offset + 2) & 255) > 240) return true;
        }
        return false;
    }

    private static int createOutlineProgram(String variant, boolean sourceSnapshot) throws Exception {
        Path root = Path.of("common/src/main/resources/assets/mmdskin/shader");
        String vertex = Files.readString(root.resolve("toon_outline_body.vert.glsl")).replace("\r\n", "\n");
        String fragment = Files.readString(root.resolve("toon_outline_body.frag.glsl"))
                .replace("\r\n", "\n")
                .replace("/* TOON_OUTPUT_DECLARATIONS */", "layout(location=0) out vec4 color;")
                .replace("/* TOON_OUTPUT_WRITER */", "void writeToonOutputs(vec3 c, vec3 a, vec3 n, float alpha) { color=vec4(c,alpha); }");
        if (sourceSnapshot) {
            vertex = legacyPerspectiveExpansion(vertex);
            fragment = fragment.replace("    // 正交投影的视线不随屏幕位置与 GUI 图层深度改变。\n"
                            + "    bool orthographic = abs(ProjMat[2][3]) < 0.000001 && abs(ProjMat[3][3] - 1.0) < 0.000001;\n"
                            + "    vec3 viewDir = orthographic ? vec3(0.0, 0.0, 1.0) : normalize(-viewPos);",
                    "    vec3 viewDir = normalize(-viewPos);");
        }
        return link(vertex, fragment);
    }

    private static String legacyPerspectiveExpansion(String source) {
        String startMarker = "    bool orthographic = abs(ProjMat[2][3])";
        String endMarker = "    gl_Position = ProjMat * vPos;";
        int start = source.indexOf(startMarker), end = source.indexOf(endMarker, start);
        check(start >= 0 && end > start, "定位当前描边顶点展开代码以生成旧版 baseline");
        String legacy = "    float viewDepth = max(-vPos.z, 0.5);\n"
                + "    float outlineScale = mix(0.8, 1.2, clamp((viewDepth - 1.0) / 12.0, 0.0, 1.0));\n"
                + "    float distanceFade = clamp(1.0 - (viewDepth - 25.0) / 15.0, 0.0, 1.0);\n"
                + "    vPos.xyz += transformedNormal * (OutlineWidth * outlineScale * distanceFade);\n\n";
        return source.substring(0, start) + legacy + source.substring(end);
    }

    private static final String BASE_VERTEX = "#version 330 core\nlayout(location=0) in vec3 Position;\nuniform mat4 ProjMat; uniform mat4 ModelViewMat;\nvoid main(){gl_Position=ProjMat*ModelViewMat*vec4(Position,1.0);}";
    private static final String BASE_FRAGMENT = "#version 330 core\nlayout(location=0) out vec4 color;\nvoid main(){color=vec4(1.0);}";

    private static int createProgram(String vertex, String fragment) { return link(vertex, fragment); }
    private static int link(String vertex, String fragment) {
        int vs = compile(GL46C.GL_VERTEX_SHADER, vertex), fs = compile(GL46C.GL_FRAGMENT_SHADER, fragment);
        int program = GL46C.glCreateProgram();
        GL46C.glAttachShader(program, vs); GL46C.glAttachShader(program, fs); GL46C.glLinkProgram(program);
        check(GL46C.glGetProgrami(program, GL46C.GL_LINK_STATUS) != 0, GL46C.glGetProgramInfoLog(program));
        return program;
    }
    private static int compile(int type, String source) {
        int shader = GL46C.glCreateShader(type); GL46C.glShaderSource(shader, source); GL46C.glCompileShader(shader);
        check(GL46C.glGetShaderi(shader, GL46C.GL_COMPILE_STATUS) != 0, GL46C.glGetShaderInfoLog(shader));
        return shader;
    }

    private static Matrices paperDollMatrices() {
        // Manual column-major GUI ortho and modelview: GUI ortho + camera(-11000) + paper scale/rotation.
        float[] projection = identity();
        projection[0] = 2f / SIZE; projection[5] = -2f / SIZE;
        projection[10] = -2f / 20000f; projection[12] = -1f; projection[13] = 1f;
        projection[14] = -1.1f;
        float[] model = multiply(translation(CENTER, CENTER, -10950f),
                multiply(scale(SCALE, SCALE, -SCALE), multiply(rotationZ((float)Math.PI), rotationY((float)Math.toRadians(200)))));
        float depth = -(model[14]);
        float fade = clamp(1f - (depth - 25f) / 15f, 0f, 1f);
        float det = model[0]*(model[5]*model[10]-model[9]*model[6])
                - model[4]*(model[1]*model[10]-model[9]*model[2])
                + model[8]*(model[1]*model[6]-model[5]*model[2]);
        return new Matrices(projection, model, depth, fade, det);
    }

    private static float[] identity() { return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}; }
    private static float[] translation(float x,float y,float z){float[]m=identity();m[12]=x;m[13]=y;m[14]=z;return m;}
    private static float[] scale(float x,float y,float z){float[]m=identity();m[0]=x;m[5]=y;m[10]=z;return m;}
    private static float[] rotationZ(float a){float[]m=identity();float c=(float)Math.cos(a),s=(float)Math.sin(a);m[0]=c;m[1]=s;m[4]=-s;m[5]=c;return m;}
    private static float[] rotationY(float a){float[]m=identity();float c=(float)Math.cos(a),s=(float)Math.sin(a);m[0]=c;m[2]=-s;m[8]=s;m[10]=c;return m;}
    private static float[] multiply(float[]a,float[]b){float[]r=new float[16];for(int c=0;c<4;c++)for(int row=0;row<4;row++)for(int k=0;k<4;k++)r[c*4+row]+=a[k*4+row]*b[c*4+k];return r;}
    private static float clamp(float x,float lo,float hi){return Math.max(lo,Math.min(hi,x));}
    private record Matrices(float[] projection,float[] modelView,float viewDepth,float distanceFade,float det3){
        void upload(int p){GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(p,"ProjMat"),false,projection);GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(p,"ModelViewMat"),false,modelView);}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
