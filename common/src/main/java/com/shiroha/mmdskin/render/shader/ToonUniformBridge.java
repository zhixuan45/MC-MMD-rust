package com.shiroha.mmdskin.render.shader;

import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Iris apply 之后同步已更新的光照 uniform；采样器沿用 Iris 绑定的单元。 */
final class ToonUniformBridge {
    private record Binding(int source, int target, int type) {}
    private record Plan(int source, int target, List<Binding> bindings, String reason) {}
    private record Uniform(int type, int size) {}
    private final Set<String> uniforms;
    private final Map<Object, Plan> bindings = new IdentityHashMap<>();
    private String failureReason = "";

    ToonUniformBridge(String lightingSource) {
        uniforms = new java.util.HashSet<>();
        var matcher = Pattern.compile("\\buniform\\s+\\w+\\s+([^;]+);").matcher(lightingSource);
        while (matcher.find()) {
            for (String declaration : matcher.group(1).split(",")) {
                uniforms.add(declaration.trim().replaceFirst("\\s*\\[.*", ""));
            }
        }
    }

    boolean copy(int source, int target, Object identity) {
        failureReason = "";
        if (uniforms.isEmpty() || source <= 0) return false;
        // GL 编号可复用，光影重载后必须按新 shader 实例重新取位置。
        Plan cached = identity == null ? null : bindings.get(identity);
        if (cached == null || cached.source() != source || cached.target() != target) {
            cached = plan(source, target);
            if (bindings.size() > 16) bindings.clear();
            if (identity != null) bindings.put(identity, cached);
        }
        failureReason = cached.reason();
        List<Binding> plan = cached.bindings();
        if (plan.isEmpty()) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var floats = stack.mallocFloat(16);
            var integers = stack.mallocInt(4);
            for (Binding binding : plan) {
                if (isInteger(binding.type())) {
                    integers.clear();
                    GL46C.glGetUniformiv(source, binding.source(), integers);
                    switch (binding.type()) {
                        case GL46C.GL_INT_VEC2, GL46C.GL_BOOL_VEC2 -> GL46C.glUniform2iv(binding.target(), integers.limit(2));
                        case GL46C.GL_INT_VEC3, GL46C.GL_BOOL_VEC3 -> GL46C.glUniform3iv(binding.target(), integers.limit(3));
                        case GL46C.GL_INT_VEC4, GL46C.GL_BOOL_VEC4 -> GL46C.glUniform4iv(binding.target(), integers);
                        default -> GL46C.glUniform1i(binding.target(), integers.get(0));
                    }
                } else {
                    floats.clear();
                    GL46C.glGetUniformfv(source, binding.source(), floats);
                    switch (binding.type()) {
                        case GL46C.GL_FLOAT -> GL46C.glUniform1f(binding.target(), floats.get(0));
                        case GL46C.GL_FLOAT_VEC2 -> GL46C.glUniform2fv(binding.target(), floats.limit(2));
                        case GL46C.GL_FLOAT_VEC3 -> GL46C.glUniform3fv(binding.target(), floats.limit(3));
                        case GL46C.GL_FLOAT_VEC4 -> GL46C.glUniform4fv(binding.target(), floats.limit(4));
                        case GL46C.GL_FLOAT_MAT2 -> GL46C.glUniformMatrix2fv(binding.target(), false, floats.limit(4));
                        case GL46C.GL_FLOAT_MAT3 -> GL46C.glUniformMatrix3fv(binding.target(), false, floats.limit(9));
                        case GL46C.GL_FLOAT_MAT4 -> GL46C.glUniformMatrix4fv(binding.target(), false, floats);
                        default -> throw new IllegalStateException("未验证的光照 uniform 类型");
                    }
                }
            }
        }
        return true;
    }

    String failureReason() { return failureReason; }

    private Plan failure(int source, int target, String reason) {
        return new Plan(source, target, List.of(), reason);
    }

    private Plan plan(int source, int target) {
        List<Binding> result = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            Map<String, Uniform> sourceUniforms = new java.util.HashMap<>();
            int sourceCount = GL46C.glGetProgrami(source, GL46C.GL_ACTIVE_UNIFORMS);
            for (int i = 0; i < sourceCount; i++) {
                String name = GL46C.glGetActiveUniform(source, i, size, type).replaceFirst("\\[0]$", "");
                sourceUniforms.put(name, new Uniform(type.get(0), size.get(0)));
            }
            int count = GL46C.glGetProgrami(target, GL46C.GL_ACTIVE_UNIFORMS);
            for (int i = 0; i < count; i++) {
                String name = GL46C.glGetActiveUniform(target, i, size, type);
                String base = name.replaceFirst("\\[0]$", "");
                if (!uniforms.contains(base)) continue;
                if (!supported(type.get(0))) return failure(source, target, "不支持的 uniform 类型: " + base);
                String sourceBase = base.equals("ToonPackTexture") ? "texture" : base;
                Uniform input = sourceUniforms.get(sourceBase);
                if (input == null) return failure(source, target, "来源缺少活动 uniform: " + sourceBase);
                if (input.type() != type.get(0) || input.size() < size.get(0)) {
                    return failure(source, target, "uniform 类型或数组大小不匹配: " + sourceBase);
                }
                for (int element = 0; element < size.get(0); element++) {
                    String targetName = size.get(0) > 1 ? base + "[" + element + "]" : name;
                    String sourceName = sourceBase + (size.get(0) > 1 ? "[" + element + "]" : "");
                    int sourceLocation = GL46C.glGetUniformLocation(source, sourceName);
                    if (sourceLocation < 0) return failure(source, target, "来源缺少 uniform 位置: " + sourceName);
                    result.add(new Binding(sourceLocation, GL46C.glGetUniformLocation(target, targetName), type.get(0)));
                }
            }
        }
        return new Plan(source, target, result, result.isEmpty() ? "没有可同步的光照输入" : "");
    }

    private static boolean supported(int type) {
        return isInteger(type) || switch (type) {
            case GL46C.GL_FLOAT, GL46C.GL_FLOAT_VEC2, GL46C.GL_FLOAT_VEC3, GL46C.GL_FLOAT_VEC4,
                 GL46C.GL_FLOAT_MAT2, GL46C.GL_FLOAT_MAT3, GL46C.GL_FLOAT_MAT4 -> true;
            default -> false;
        };
    }

    private static boolean isInteger(int type) {
        return switch (type) {
            case GL46C.GL_INT, GL46C.GL_INT_VEC2, GL46C.GL_INT_VEC3, GL46C.GL_INT_VEC4,
                 GL46C.GL_BOOL, GL46C.GL_BOOL_VEC2, GL46C.GL_BOOL_VEC3, GL46C.GL_BOOL_VEC4,
                 GL46C.GL_SAMPLER_2D, GL46C.GL_SAMPLER_2D_SHADOW, GL46C.GL_SAMPLER_3D -> true;
            default -> false;
        };
    }
}
