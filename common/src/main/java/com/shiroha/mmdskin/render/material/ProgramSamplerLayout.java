package com.shiroha.mmdskin.render.material;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

/** 缓存程序 sampler 元数据，并按当前 uniform 分配安全纹理单元。 */
public final class ProgramSamplerLayout {
    static final int MAX_BATCH_TEXTURES = 8;
    private static final Map<Integer, Metadata> CACHE = new HashMap<>();
    private final int program;
    private final int[] units;
    private final int[] uniformUnits;

    private ProgramSamplerLayout(int program, int[] units) {
        this.program = program;
        this.units = units;
        uniformUnits = new int[MAX_BATCH_TEXTURES];
        for (int i = 0; i < uniformUnits.length; i++) uniformUnits[i] = units[i % units.length];
    }

    static ProgramSamplerLayout capture(int program) {
        Metadata metadata = metadata(program, true);
        if (metadata == null) return null;
        int maxUnits = GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_IMAGE_UNITS);
        boolean[] occupied = new boolean[maxUnits];
        for (Sampler sampler : metadata.samplers) {
            if (sampler.batchSampler) continue;
            for (int location : sampler.locations) {
                if (location < 0) continue;
                int unit = GL46C.glGetUniformi(program, location);
                if (unit < 0 || unit >= occupied.length) return null;
                occupied[unit] = true;
            }
        }
        // 单元足够但主贴图绑定了自定义 sampler 时，保留普通提交。
        int diffuse = GL46C.glGetUniformLocation(program, "Sampler0");
        if (diffuse >= 0) {
            int unit = GL46C.glGetUniformi(program, diffuse);
            if (unit < 0 || unit >= GL46C.glGetInteger(GL46C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)
                    || GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, unit) != 0) return null;
        }
        int capacity = Math.min(MAX_BATCH_TEXTURES, maxUnits - count(occupied));
        if (capacity <= 0) return null;
        int[] units = new int[capacity];
        int next = 0;
        for (int unit = 0; unit < occupied.length && next < capacity; unit++) {
            if (!occupied[unit]) units[next++] = unit;
        }
        return next == capacity ? new ProgramSamplerLayout(program, units) : null;
    }

    static int sphereUnit(int program, int sphereLocation) {
        Metadata metadata = metadata(program, false);
        if (metadata == null) return -1;
        boolean[] occupied = new boolean[GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_IMAGE_UNITS)];
        for (Sampler sampler : metadata.samplers) {
            for (int location : sampler.locations) {
                if (location == sphereLocation) continue;
                int unit = GL46C.glGetUniformi(program, location);
                if (unit >= 0 && unit < occupied.length) occupied[unit] = true;
            }
        }
        for (int unit = occupied.length - 1; unit >= 3; unit--) if (!occupied[unit]) return unit;
        return -1;
    }

    public static void forget(int program) { CACHE.remove(program); }

    int program() { return program; }
    int capacity() { return units.length; }
    int[] units() { return units; }
    int[] uniformUnits() { return uniformUnits; }

    private static Metadata metadata(int program, boolean cacheResult) {
        if (program <= 0) return null;
        Metadata cached = CACHE.get(program);
        if (cached != null) return cached;
        var samplers = new java.util.ArrayList<Sampler>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer size = stack.mallocInt(1);
            IntBuffer type = stack.mallocInt(1);
            int active = GL46C.glGetProgrami(program, GL46C.GL_ACTIVE_UNIFORMS);
            for (int i = 0; i < active; i++) {
                String name = GL46C.glGetActiveUniform(program, i, size, type);
                if (!isSampler(type.get(0)) || name == null) continue;
                int count = Math.max(1, size.get(0));
                int[] locations = new int[count];
                for (int element = 0; element < count; element++) {
                    String entry = count == 1 ? name : name.replace("[0]", "[" + element + "]");
                    locations[element] = GL46C.glGetUniformLocation(program, entry);
                }
                samplers.add(new Sampler(type.get(0), count, locations, name.startsWith("MmdBatchTextures")));
            }
        }
        cached = new Metadata(samplers.toArray(Sampler[]::new));
        if (cacheResult) CACHE.put(program, cached);
        return cached;
    }

    private static int count(boolean[] values) {
        int count = 0;
        for (boolean value : values) if (value) count++;
        return count;
    }

    private static boolean isSampler(int type) {
        return switch (type) {
            case GL46C.GL_SAMPLER_1D, GL46C.GL_SAMPLER_2D, GL46C.GL_SAMPLER_3D,
                    GL46C.GL_SAMPLER_CUBE, GL46C.GL_SAMPLER_1D_SHADOW, GL46C.GL_SAMPLER_2D_SHADOW,
                    GL46C.GL_SAMPLER_CUBE_SHADOW, GL46C.GL_SAMPLER_1D_ARRAY,
                    GL46C.GL_SAMPLER_2D_ARRAY, GL46C.GL_SAMPLER_1D_ARRAY_SHADOW,
                    GL46C.GL_SAMPLER_2D_ARRAY_SHADOW, GL46C.GL_SAMPLER_2D_RECT,
                    GL46C.GL_SAMPLER_2D_RECT_SHADOW, GL46C.GL_SAMPLER_BUFFER,
                    GL46C.GL_SAMPLER_2D_MULTISAMPLE, GL46C.GL_SAMPLER_2D_MULTISAMPLE_ARRAY,
                    GL46C.GL_SAMPLER_CUBE_MAP_ARRAY, GL46C.GL_SAMPLER_CUBE_MAP_ARRAY_SHADOW,
                    GL46C.GL_INT_SAMPLER_1D, GL46C.GL_INT_SAMPLER_2D, GL46C.GL_INT_SAMPLER_3D,
                    GL46C.GL_INT_SAMPLER_CUBE, GL46C.GL_INT_SAMPLER_1D_ARRAY, GL46C.GL_INT_SAMPLER_2D_ARRAY,
                    GL46C.GL_INT_SAMPLER_2D_RECT, GL46C.GL_INT_SAMPLER_BUFFER,
                    GL46C.GL_INT_SAMPLER_2D_MULTISAMPLE, GL46C.GL_INT_SAMPLER_2D_MULTISAMPLE_ARRAY,
                    GL46C.GL_INT_SAMPLER_CUBE_MAP_ARRAY, GL46C.GL_UNSIGNED_INT_SAMPLER_1D,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_2D, GL46C.GL_UNSIGNED_INT_SAMPLER_3D,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_CUBE, GL46C.GL_UNSIGNED_INT_SAMPLER_1D_ARRAY,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY, GL46C.GL_UNSIGNED_INT_SAMPLER_2D_RECT,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_BUFFER, GL46C.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE_ARRAY,
                    GL46C.GL_UNSIGNED_INT_SAMPLER_CUBE_MAP_ARRAY -> true;
            default -> false;
        };
    }

    private record Sampler(int type, int count, int[] locations, boolean batchSampler) { }
    private record Metadata(Sampler[] samplers) { }
}
