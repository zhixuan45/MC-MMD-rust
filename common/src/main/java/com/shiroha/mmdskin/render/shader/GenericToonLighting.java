package com.shiroha.mmdskin.render.shader;

import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

import java.util.IdentityHashMap;
import java.util.Map;

/** 原包照明不可用时读取可选的标准 Iris 输入，不读取未知阴影/材质纹理。 */
final class GenericToonLighting {
    private record Input(int program, int direction, int sun, int moon, int angle, int view, int fog, int rain) {}
    private final Map<Object, Input> inputs = new IdentityHashMap<>();
    private int enabled, direction, hasDirection, skyTint, weather;

    void select(int program) {
        enabled = GL46C.glGetUniformLocation(program, "ToonGenericEnvironment");
        direction = GL46C.glGetUniformLocation(program, "ToonEnvironmentDirection");
        hasDirection = GL46C.glGetUniformLocation(program, "ToonHasEnvironmentDirection");
        skyTint = GL46C.glGetUniformLocation(program, "ToonEnvironmentSkyTint");
        weather = GL46C.glGetUniformLocation(program, "ToonEnvironmentWeather");
    }

    void upload(int source, Object identity) {
        if (enabled < 0) return;
        GL46C.glUniform1i(enabled, source > 0 ? 1 : 0);
        GL46C.glUniform1i(hasDirection, 0);
        GL46C.glUniform3f(skyTint, 1, 1, 1);
        GL46C.glUniform1f(weather, 1);
        if (source <= 0) return;
        Input input = identity == null ? null : inputs.get(identity);
        if (input == null || input.program() != source) {
            input = inspect(source);
            if (inputs.size() > 16) inputs.clear();
            if (identity != null) inputs.put(identity, input);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var vector = stack.mallocFloat(3);
            var matrix = stack.mallocFloat(16);
            boolean night = input.angle() >= 0 && GL46C.glGetUniformf(source, input.angle()) > 0.5f;
            int position = input.direction() >= 0 ? input.direction()
                    : night && input.moon() >= 0 ? input.moon() : input.sun();
            float x = 0, y = 0, z = 0;
            if (position >= 0) {
                GL46C.glGetUniformfv(source, position, vector);
                x = vector.get(0); y = vector.get(1); z = vector.get(2);
                if (input.direction() < 0 && night && input.moon() < 0) { x = -x; y = -y; z = -z; }
            } else if (input.angle() >= 0 && input.view() >= 0) {
                // 标准太阳相位与视图矩阵都可读时才推导方向，否则保留调用方方向。
                float phase = GL46C.glGetUniformf(source, input.angle());
                float wx = -(float) Math.sin((phase - 0.25f) * Math.PI * 2);
                float wy = (float) Math.cos((phase - 0.25f) * Math.PI * 2);
                if (night) { wx = -wx; wy = -wy; }
                GL46C.glGetUniformfv(source, input.view(), matrix);
                x = matrix.get(0) * wx + matrix.get(4) * wy;
                y = matrix.get(1) * wx + matrix.get(5) * wy;
                z = matrix.get(2) * wx + matrix.get(6) * wy;
            }
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (Float.isFinite(length) && length > 0.0001f) {
                GL46C.glUniform3f(direction, x / length, y / length, z / length);
                GL46C.glUniform1i(hasDirection, 1);
            }
            if (input.fog() >= 0) {
                GL46C.glGetUniformfv(source, input.fog(), vector);
                float r = vector.get(0), g = vector.get(1), b = vector.get(2);
                float max = Math.max(r, Math.max(g, b));
                if (Float.isFinite(max) && max > 0.0001f && r >= 0 && g >= 0 && b >= 0) {
                    // 雾色只提供少量色调，不把雾的绝对亮度误当作照度。
                    GL46C.glUniform3f(skyTint, 0.8f + 0.2f * r / max,
                            0.8f + 0.2f * g / max, 0.8f + 0.2f * b / max);
                }
            }
            if (input.rain() >= 0) {
                float rain = GL46C.glGetUniformf(source, input.rain());
                if (Float.isFinite(rain)) GL46C.glUniform1f(weather, 1 - 0.35f * Math.max(0, Math.min(1, rain)));
            }
        }
    }

    private static Input inspect(int program) {
        Map<String, Integer> types = new java.util.HashMap<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1); var type = stack.mallocInt(1);
            int count = GL46C.glGetProgrami(program, GL46C.GL_ACTIVE_UNIFORMS);
            for (int i = 0; i < count; i++) {
                String name = GL46C.glGetActiveUniform(program, i, size, type);
                if (size.get(0) == 1) types.put(name, type.get(0));
            }
        }
        int angle = location(program, types, "sunAngle", GL46C.GL_FLOAT);
        if (angle < 0) angle = location(program, types, "timeAngle", GL46C.GL_FLOAT);
        return new Input(program, location(program, types, "shadowLightPosition", GL46C.GL_FLOAT_VEC3),
                location(program, types, "sunPosition", GL46C.GL_FLOAT_VEC3),
                location(program, types, "moonPosition", GL46C.GL_FLOAT_VEC3), angle,
                location(program, types, "gbufferModelView", GL46C.GL_FLOAT_MAT4),
                location(program, types, "fogColor", GL46C.GL_FLOAT_VEC3),
                location(program, types, "rainStrength", GL46C.GL_FLOAT));
    }

    private static int location(int program, Map<String, Integer> types, String name, int type) {
        return types.getOrDefault(name, -1) == type ? GL46C.glGetUniformLocation(program, name) : -1;
    }
}
