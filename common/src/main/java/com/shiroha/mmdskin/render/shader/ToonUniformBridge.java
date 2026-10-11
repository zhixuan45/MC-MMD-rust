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
    private record Plan(int program, List<Binding> bindings) {}
    private final Set<String> uniforms;
    private final Map<Object, Plan> bindings = new IdentityHashMap<>();

    ToonUniformBridge(String lightingSource) {
        uniforms = new java.util.HashSet<>();
        var matcher = Pattern.compile("\\buniform\\s+\\w+\\s+(\\w+)").matcher(lightingSource);
        while (matcher.find()) uniforms.add(matcher.group(1));
    }

    boolean copy(int source, int target, Object identity) {
        if (uniforms.isEmpty() || source <= 0) return false;
        // GL 编号可复用，光影重载后必须按新 shader 实例重新取位置。
        Plan cached = identity == null ? null : bindings.get(identity);
        List<Binding> plan;
        if (cached == null || cached.program() != source) {
            plan = plan(source, target);
            if (bindings.size() > 16) bindings.clear();
            if (identity != null) bindings.put(identity, new Plan(source, plan));
        } else plan = cached.bindings();
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

    private List<Binding> plan(int source, int target) {
        List<Binding> result = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            int count = GL46C.glGetProgrami(target, GL46C.GL_ACTIVE_UNIFORMS);
            for (int i = 0; i < count; i++) {
                String name = GL46C.glGetActiveUniform(target, i, size, type);
                String base = name.replaceFirst("\\[0]$", "");
                if (!uniforms.contains(base)) continue;
                if (!supported(type.get(0))) return List.of();
                for (int element = 0; element < size.get(0); element++) {
                    String targetName = size.get(0) > 1 ? base + "[" + element + "]" : name;
                    String sourceName = targetName.replace("ToonPackTexture", "texture");
                    int sourceLocation = GL46C.glGetUniformLocation(source, sourceName);
                    if (sourceLocation < 0) return List.of();
                    result.add(new Binding(sourceLocation, GL46C.glGetUniformLocation(target, targetName), type.get(0)));
                }
            }
        }
        return result;
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
