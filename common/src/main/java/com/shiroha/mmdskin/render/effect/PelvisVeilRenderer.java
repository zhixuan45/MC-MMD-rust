package com.shiroha.mmdskin.render.effect;

import com.google.gson.Gson;
import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.bridge.runtime.NativeRenderBackendPort;
import com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyPipeline;
import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import com.shiroha.mmdskin.util.AssetsUtil;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 模型实例拥有的闭合骨盆半透明遮罩（游泳水波纹 / 鞘翅气流）；CPU/GPU 后端共用实时骨骼锚点。 */
public final class PelvisVeilRenderer implements AutoCloseable {
    private static final int LATITUDES = 24, LONGITUDES = 48;
    private final PelvisVeilPose pose;
    private final ByteBuffer positions;
    private int program, vao, vertices;
    private int modelViewLocation, projectionLocation, timeLocation;
    private int opacityLocation, styleLocation, lightLocation, tintLocation, outputEncodingLocation;
    private boolean attempted;

    public PelvisVeilRenderer(NativeRenderBackendPort port, long model) {
        int count = port.getBoneCount(model);
        positions = MemoryUtil.memAlloc(Math.multiplyExact(Math.max(0, count), 12)).order(ByteOrder.nativeOrder());
        String[] names = new Gson().fromJson(port.getBoneNames(model), String[].class);
        pose = new PelvisVeilPose(names != null ? names : new String[0]);
    }

    /**
     * 必须在宿主实体着色器（含 Iris G-buffer FBO）仍处于绑定状态时调用，
     * 以便与 WorldTransparencyPipeline、光影水面及 colortex0 正确交互。
     */
    public void render(NativeRenderBackendPort port, long model, Matrix4f modelView,
                       PelvisVeilState state, float lightIntensity,
                       int colorOutputSlot, int outputEncoding, boolean shaderPackActive) {
        if (state == null || !state.shouldEvaluate()) return;
        positions.clear();
        int copied = port.copyBonePositionsToBuffer(model, positions);
        PelvisVeilMode activeStyle = state.renderMode();
        if (!activeStyle.isActive()) return;

        Matrix4f transform = new Matrix4f(modelView).mul(pose.resolve(positions, copied, activeStyle));
        float exposure = PelvisVeilPose.computeViewExposure(transform);
        float opacity = state.advance(exposure, System.nanoTime());
        if (opacity < PelvisVeilState.MIN_VISIBLE_OPACITY || !state.renderMode().isActive()) {
            return;
        }

        try (State glState = new State()) {
            if (!initialize()) return;
            GL33C.glUseProgram(program);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GL33C.glUniformMatrix4fv(modelViewLocation, false, transform.get(stack.mallocFloat(16)));
                GL33C.glUniformMatrix4fv(projectionLocation, false, RenderSystem.getProjectionMatrix().get(stack.mallocFloat(16)));
            }
            GL33C.glUniform1f(timeLocation, state.seconds());
            GL33C.glUniform1f(opacityLocation, opacity);
            GL33C.glUniform1i(styleLocation, state.renderMode().styleId());
            GL33C.glUniform1f(lightLocation, Math.max(0.0f, Math.min(1.0f, lightIntensity)));
            GL33C.glUniform3f(tintLocation, state.tintR(), state.tintG(), state.tintB());
            GL33C.glUniform1i(outputEncodingLocation, outputEncoding);

            GL33C.glBindVertexArray(vao);
            GL33C.glEnable(GL33C.GL_DEPTH_TEST);
            GL33C.glDepthFunc(GL33C.GL_LEQUAL);
            GL33C.glDepthMask(false);
            GL33C.glEnable(GL33C.GL_CULL_FACE);
            GL33C.glCullFace(GL33C.GL_BACK);
            GL33C.glFrontFace(GL33C.GL_CCW);

            // 优先在原 FBO drawBuffers 未被修改前尝试加入世界透明队列，保证与光影水面及透明材质精确排序。
            if (WorldTransparencyPipeline.prepareModel()) {
                try (var capture = WorldTransparencyPipeline.capture()) { draw(); }
                return;
            }

            // 回退即时混合：仅写入已确认的 colortex0 槽位，避免改写 Iris 的法线/材质等辅助附件。
            int slot = resolveColorSlot(colorOutputSlot, glState.drawBuffers);
            if (slot < 0) return;
            GL33C.glDrawBuffer(glState.drawBuffers[slot]);
            // 光影即时混合不覆写 colortex0.a（保留光影的实体/天空通道编码）。
            GL33C.glColorMaski(0, true, true, true, !shaderPackActive);
            GL33C.glEnablei(GL33C.GL_BLEND, 0);
            if (GL.getCapabilities().OpenGL40) {
                org.lwjgl.opengl.GL40C.glBlendEquationSeparatei(0, GL33C.GL_FUNC_ADD, GL33C.GL_FUNC_ADD);
                org.lwjgl.opengl.GL40C.glBlendFuncSeparatei(0, GL33C.GL_SRC_ALPHA, GL33C.GL_ONE_MINUS_SRC_ALPHA,
                        GL33C.GL_ONE, GL33C.GL_ONE_MINUS_SRC_ALPHA);
            } else {
                GL33C.glBlendEquationSeparate(GL33C.GL_FUNC_ADD, GL33C.GL_FUNC_ADD);
                GL33C.glBlendFuncSeparate(GL33C.GL_SRC_ALPHA, GL33C.GL_ONE_MINUS_SRC_ALPHA, GL33C.GL_ONE, GL33C.GL_ONE_MINUS_SRC_ALPHA);
            }
            draw();
        }
    }

    static int resolveColorSlot(int requestedSlot, int[] drawBuffers) {
        if (drawBuffers == null || drawBuffers.length == 0) return -1;
        if (requestedSlot >= 0 && requestedSlot < drawBuffers.length && drawBuffers[requestedSlot] != GL33C.GL_NONE) {
            return requestedSlot;
        }
        return drawBuffers[0] != GL33C.GL_NONE ? 0 : -1;
    }

    private void draw() {
        GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, LATITUDES * LONGITUDES * 6);
    }

    private boolean initialize() {
        if (attempted) return program != 0;
        attempted = true;
        program = ShaderCompiler.compileRenderProgram(
                AssetsUtil.getAssetsAsString("shader/pelvis_veil.vert.glsl"),
                AssetsUtil.getAssetsAsString("shader/pelvis_veil.frag.glsl"),
                "Pelvis translucent veil");
        if (program == 0) return false;
        modelViewLocation = GL33C.glGetUniformLocation(program, "ModelViewMat");
        projectionLocation = GL33C.glGetUniformLocation(program, "ProjMat");
        timeLocation = GL33C.glGetUniformLocation(program, "Time");
        opacityLocation = GL33C.glGetUniformLocation(program, "Opacity");
        styleLocation = GL33C.glGetUniformLocation(program, "VeilStyle");
        lightLocation = GL33C.glGetUniformLocation(program, "LightIntensity");
        tintLocation = GL33C.glGetUniformLocation(program, "TintColor");
        outputEncodingLocation = GL33C.glGetUniformLocation(program, "OutputEncoding");
        float[] mesh = new float[LATITUDES * LONGITUDES * 18];
        int offset = 0;
        for (int lat = 0; lat < LATITUDES; lat++) {
            for (int lon = 0; lon < LONGITUDES; lon++) {
                offset = vertex(mesh, offset, lat, lon);
                offset = vertex(mesh, offset, lat + 1, lon);
                offset = vertex(mesh, offset, lat + 1, lon + 1);
                offset = vertex(mesh, offset, lat, lon);
                offset = vertex(mesh, offset, lat + 1, lon + 1);
                offset = vertex(mesh, offset, lat, lon + 1);
            }
        }
        vao = GL33C.glGenVertexArrays();
        vertices = GL33C.glGenBuffers();
        GL33C.glBindVertexArray(vao);
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vertices);
        GL33C.glBufferData(GL33C.GL_ARRAY_BUFFER, mesh, GL33C.GL_STATIC_DRAW);
        GL33C.glEnableVertexAttribArray(0);
        GL33C.glVertexAttribPointer(0, 3, GL33C.GL_FLOAT, false, 12, 0);
        return true;
    }

    private static int vertex(float[] mesh, int offset, int lat, int lon) {
        double latitude = Math.PI * lat / LATITUDES - Math.PI * 0.5;
        double longitude = Math.PI * 2 * lon / LONGITUDES;
        mesh[offset++] = (float) (Math.cos(latitude) * Math.cos(longitude));
        mesh[offset++] = (float) Math.sin(latitude);
        mesh[offset++] = (float) (Math.cos(latitude) * Math.sin(longitude));
        return offset;
    }

    @Override
    public void close() {
        if (program != 0) GL33C.glDeleteProgram(program);
        if (vao != 0) GL33C.glDeleteVertexArrays(vao);
        if (vertices != 0) GL33C.glDeleteBuffers(vertices);
        MemoryUtil.memFree(positions);
        program = vao = vertices = 0;
    }

    /** 成对恢复原始 GL 状态，不触碰 Blaze3D 缓存。 */
    private static final class State implements AutoCloseable {
        final int fbo = GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
        final int program = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
        final int vao = GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING);
        final int arrayBuffer = GL33C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING);
        final boolean depth = GL33C.glIsEnabled(GL33C.GL_DEPTH_TEST), mask = GL33C.glGetBoolean(GL33C.GL_DEPTH_WRITEMASK);
        final int depthFunc = GL33C.glGetInteger(GL33C.GL_DEPTH_FUNC);
        final boolean cull = GL33C.glIsEnabled(GL33C.GL_CULL_FACE), blend = GL33C.glIsEnabledi(GL33C.GL_BLEND, 0);
        final int cullFace = GL33C.glGetInteger(GL33C.GL_CULL_FACE_MODE), frontFace = GL33C.glGetInteger(GL33C.GL_FRONT_FACE);
        final boolean indexed = GL.getCapabilities().OpenGL40;
        final int srcRgb = parameter(GL33C.GL_BLEND_SRC_RGB), dstRgb = parameter(GL33C.GL_BLEND_DST_RGB);
        final int srcAlpha = parameter(GL33C.GL_BLEND_SRC_ALPHA), dstAlpha = parameter(GL33C.GL_BLEND_DST_ALPHA);
        final int eqRgb = parameter(GL33C.GL_BLEND_EQUATION_RGB), eqAlpha = parameter(GL33C.GL_BLEND_EQUATION_ALPHA);
        final int[] drawBuffers;
        final boolean colorMaskR, colorMaskG, colorMaskB, colorMaskA;

        State() {
            drawBuffers = new int[fbo == 0 ? 1 : GL33C.glGetInteger(GL33C.GL_MAX_DRAW_BUFFERS)];
            for (int i = 0; i < drawBuffers.length; i++) drawBuffers[i] = GL33C.glGetInteger(GL33C.GL_DRAW_BUFFER0 + i);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer maskBuf = stack.malloc(4);
                GL33C.glGetBooleani_v(GL33C.GL_COLOR_WRITEMASK, 0, maskBuf);
                colorMaskR = maskBuf.get(0) != 0;
                colorMaskG = maskBuf.get(1) != 0;
                colorMaskB = maskBuf.get(2) != 0;
                colorMaskA = maskBuf.get(3) != 0;
            }
        }

        private int parameter(int name) { return indexed ? GL33C.glGetIntegeri(name, 0) : GL33C.glGetInteger(name); }
        private static void enabled(int cap, boolean value) { if (value) GL33C.glEnable(cap); else GL33C.glDisable(cap); }

        @Override
        public void close() {
            GL33C.glUseProgram(program);
            GL33C.glBindVertexArray(vao);
            GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, arrayBuffer);
            if (fbo == 0) {
                GL33C.glDrawBuffer(drawBuffers[0]);
            } else {
                GL33C.glDrawBuffers(drawBuffers);
            }
            enabled(GL33C.GL_DEPTH_TEST, depth);
            GL33C.glDepthFunc(depthFunc);
            GL33C.glDepthMask(mask);
            enabled(GL33C.GL_CULL_FACE, cull);
            GL33C.glCullFace(cullFace);
            GL33C.glFrontFace(frontFace);
            if (blend) GL33C.glEnablei(GL33C.GL_BLEND, 0); else GL33C.glDisablei(GL33C.GL_BLEND, 0);
            if (indexed) {
                org.lwjgl.opengl.GL40C.glBlendFuncSeparatei(0, srcRgb, dstRgb, srcAlpha, dstAlpha);
                org.lwjgl.opengl.GL40C.glBlendEquationSeparatei(0, eqRgb, eqAlpha);
            } else {
                GL33C.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
                GL33C.glBlendEquationSeparate(eqRgb, eqAlpha);
            }
            GL33C.glColorMaski(0, colorMaskR, colorMaskG, colorMaskB, colorMaskA);
        }
    }
}
