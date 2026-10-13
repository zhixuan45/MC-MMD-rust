package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.compat.iris.IrisCompat;
import net.minecraft.client.renderer.ShaderInstance;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 克隆当前实体程序，仅追加最终 alpha 的 discard；保留原照明、属性和 MRT 输出。 */
final class EntityPixelAlphaPass implements AutoCloseable {
    private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{");
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
    private static ShaderInstance cachedSource;
    private static int cachedProgram;
    private static List<Uniform> uniforms = List.of();
    private final int previousProgram;

    private record Uniform(int source, int target, int type) {}

    private EntityPixelAlphaPass(int previousProgram) { this.previousProgram = previousProgram; }

    static EntityPixelAlphaPass begin(boolean shadowPass) {
        int original = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        EntityPixelAlphaPass scope = new EntityPixelAlphaPass(original);
        ShaderInstance shader = RenderSystem.getShader();
        if (shadowPass || shader == null || shader.getId() != original
                || GL46C.glGetUniformLocation(original, "AlphaPass") >= 0) return scope;
        if (!IrisCompat.isIrisProgram(shader) && !shader.getName().equals("rendertype_entity_translucent")) return scope;
        if (shader != cachedSource) {
            if (cachedProgram != 0) GL46C.glDeleteProgram(cachedProgram);
            cachedProgram = 0;
            cachedSource = shader;
            try {
                int slot = IrisCompat.entityColorOutputSlot(shader);
                if (slot >= 0) cachedProgram = create(original, slot);
            } catch (RuntimeException e) {
                LogManager.getLogger().warn("实体像素 alpha 分通道初始化失败，保留原程序: {}", e.getMessage());
            }
        }
        if (cachedProgram == 0) return scope;
        try {
            GL46C.glUseProgram(cachedProgram);
            copyUniforms(original);
        } catch (RuntimeException e) {
            GL46C.glUseProgram(original);
            throw e;
        }
        return scope;
    }

    private static int create(int original, int colorSlot) {
        String output = colorOutput(original, colorSlot);
        if (output == null) return 0;
        int[] shaders = new int[GL46C.glGetProgrami(original, GL46C.GL_ATTACHED_SHADERS)];
        GL46C.glGetAttachedShaders(original, (int[]) null, shaders);
        int vertex = 0;
        String fragment = null;
        for (int shader : shaders) {
            int type = GL46C.glGetShaderi(shader, GL46C.GL_SHADER_TYPE);
            if (type == GL46C.GL_VERTEX_SHADER) vertex = shader;
            else if (type == GL46C.GL_FRAGMENT_SHADER) fragment = GL46C.glGetShaderSource(shader);
            else return 0;
        }
        if (vertex == 0 || fragment == null) return 0;
        if (GL.getCapabilities().OpenGL43 && GL46C.glGetProgramInterfacei(original,
                GL46C.GL_SHADER_STORAGE_BLOCK, GL46C.GL_ACTIVE_RESOURCES) != 0) return 0;
        String code = COMMENTS.matcher(fragment).replaceAll(m -> " ".repeat(m.group().length()));
        var main = MAIN.matcher(code);
        if (!main.find()) return 0;
        int end = main.end(), braces = 1;
        while (end < code.length() && braces != 0) {
            char c = code.charAt(end++);
            if (c == '{') braces++;
            if (c == '}') braces--;
        }
        if (braces != 0) return 0;
        // 在 main 之前声明，避免把 uniform 插到 #version/#extension 指令前面。
        String source = fragment.substring(0, main.start()) + "uniform int AlphaPass;\n"
                + fragment.substring(main.start(), end - 1)
                + "\nif (AlphaPass == 1 && " + output + ".a < 0.999) discard;\n"
                + "if (AlphaPass == 2 && " + output + ".a >= 0.999) discard;\n"
                + fragment.substring(end - 1);
        int fs = GL46C.glCreateShader(GL46C.GL_FRAGMENT_SHADER);
        int program = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL46C.glShaderSource(fs, source);GL46C.glCompileShader(fs);
            if (GL46C.glGetShaderi(fs, GL46C.GL_COMPILE_STATUS) == 0)
                throw new IllegalStateException(GL46C.glGetShaderInfoLog(fs));
            program = GL46C.glCreateProgram();
            GL46C.glAttachShader(program, vertex);GL46C.glAttachShader(program, fs);
            IntBuffer size=stack.mallocInt(1), type=stack.mallocInt(1);
            for (int i=0;i<GL46C.glGetProgrami(original,GL46C.GL_ACTIVE_ATTRIBUTES);i++) {
                String name=GL46C.glGetActiveAttrib(original,i,size,type);
                GL46C.glBindAttribLocation(program,GL46C.glGetAttribLocation(original,name),name);
            }
            // Iris 已变换的输出含显式位置；原版只有 fragColor，仍明确保留槽 0。
            if (!output.startsWith("gl_")) GL46C.glBindFragDataLocation(program,colorSlot,output);
            GL46C.glLinkProgram(program);
            if (GL46C.glGetProgrami(program,GL46C.GL_LINK_STATUS)==0)
                throw new IllegalStateException(GL46C.glGetProgramInfoLog(program));
            List<Uniform> copy=new ArrayList<>();
            for (int i=0;i<GL46C.glGetProgrami(original,GL46C.GL_ACTIVE_UNIFORMS);i++) {
                String name=GL46C.glGetActiveUniform(original,i,size,type);
                for (int element=0;element<size.get(0);element++) {
                    String entry=size.get(0)>1 ? name.replace("[0]","["+element+"]") : name;
                    int from=GL46C.glGetUniformLocation(original,entry), to=GL46C.glGetUniformLocation(program,entry);
                    if (from>=0 && to>=0) {
                        if (!supportedUniform(type.get(0))) throw new IllegalStateException("unsupported uniform type: " + type.get(0));
                        copy.add(new Uniform(from,to,type.get(0)));
                    }
                }
            }
            for (int i=0;i<GL46C.glGetProgrami(original,GL46C.GL_ACTIVE_UNIFORM_BLOCKS);i++) {
                int target=GL46C.glGetUniformBlockIndex(program,GL46C.glGetActiveUniformBlockName(original,i));
                if (target != GL46C.GL_INVALID_INDEX) GL46C.glUniformBlockBinding(program,target,
                        GL46C.glGetActiveUniformBlocki(original,i,GL46C.GL_UNIFORM_BLOCK_BINDING));
            }
            uniforms=List.copyOf(copy);
            return program;
        } catch (RuntimeException e) {
            if (program != 0) GL46C.glDeleteProgram(program);
            throw e;
        } finally { GL46C.glDeleteShader(fs); }
    }

    private static boolean supportedUniform(int type) {
        return switch (type) {
            case GL46C.GL_FLOAT,GL46C.GL_FLOAT_VEC2,GL46C.GL_FLOAT_VEC3,GL46C.GL_FLOAT_VEC4,
                    GL46C.GL_FLOAT_MAT2,GL46C.GL_FLOAT_MAT3,GL46C.GL_FLOAT_MAT4,
                    GL46C.GL_INT,GL46C.GL_INT_VEC2,GL46C.GL_INT_VEC3,GL46C.GL_INT_VEC4,
                    GL46C.GL_BOOL,GL46C.GL_BOOL_VEC2,GL46C.GL_BOOL_VEC3,GL46C.GL_BOOL_VEC4,
                    GL46C.GL_SAMPLER_1D,GL46C.GL_SAMPLER_2D,GL46C.GL_SAMPLER_3D,GL46C.GL_SAMPLER_CUBE,
                    GL46C.GL_SAMPLER_1D_SHADOW,GL46C.GL_SAMPLER_2D_SHADOW,GL46C.GL_SAMPLER_CUBE_SHADOW,
                    GL46C.GL_SAMPLER_1D_ARRAY,GL46C.GL_SAMPLER_2D_ARRAY,GL46C.GL_SAMPLER_2D_ARRAY_SHADOW -> true;
            default -> false;
        };
    }

    private static String colorOutput(int program,int slot) {
        if (GL46C.glGetFragDataLocation(program,"fragColor")==slot) return "fragColor";
        if (!GL.getCapabilities().OpenGL43) return null;
        try (MemoryStack stack=MemoryStack.stackPush()) {
            IntBuffer location=stack.mallocInt(1);
            for (int i=0;i<GL46C.glGetProgramInterfacei(program,GL46C.GL_PROGRAM_OUTPUT,GL46C.GL_ACTIVE_RESOURCES);i++) {
                GL46C.glGetProgramResourceiv(program,GL46C.GL_PROGRAM_OUTPUT,i,
                        stack.ints(GL46C.GL_LOCATION),null,location);
                if (location.get(0)==slot) return GL46C.glGetProgramResourceName(program,GL46C.GL_PROGRAM_OUTPUT,i);
            }
        }
        return null;
    }

    private static void copyUniforms(int source) {
        try (MemoryStack stack=MemoryStack.stackPush()) {
            FloatBuffer f=stack.mallocFloat(16);IntBuffer v=stack.mallocInt(4);
            for (Uniform u:uniforms) {
                switch (u.type) {
                    case GL46C.GL_FLOAT -> {GL46C.glGetUniformfv(source,u.source,f);GL46C.glUniform1f(u.target,f.get(0));}
                    case GL46C.GL_FLOAT_VEC2 -> {GL46C.glGetUniformfv(source,u.source,f);GL46C.glUniform2f(u.target,f.get(0),f.get(1));}
                    case GL46C.GL_FLOAT_VEC3 -> {GL46C.glGetUniformfv(source,u.source,f);GL46C.glUniform3f(u.target,f.get(0),f.get(1),f.get(2));}
                    case GL46C.GL_FLOAT_VEC4 -> {GL46C.glGetUniformfv(source,u.source,f);GL46C.glUniform4f(u.target,f.get(0),f.get(1),f.get(2),f.get(3));}
                    case GL46C.GL_FLOAT_MAT2 -> {f.limit(4);GL46C.glGetUniformfv(source,u.source,f);GL46C.glUniformMatrix2fv(u.target,false,f);f.limit(16);}
                    case GL46C.GL_FLOAT_MAT3 -> {f.limit(9);GL46C.glGetUniformfv(source,u.source,f);GL46C.glUniformMatrix3fv(u.target,false,f);f.limit(16);}
                    case GL46C.GL_FLOAT_MAT4 -> {GL46C.glGetUniformfv(source,u.source,f);GL46C.glUniformMatrix4fv(u.target,false,f);}
                    case GL46C.GL_INT_VEC2,GL46C.GL_BOOL_VEC2 -> {GL46C.glGetUniformiv(source,u.source,v);GL46C.glUniform2i(u.target,v.get(0),v.get(1));}
                    case GL46C.GL_INT_VEC3,GL46C.GL_BOOL_VEC3 -> {GL46C.glGetUniformiv(source,u.source,v);GL46C.glUniform3i(u.target,v.get(0),v.get(1),v.get(2));}
                    case GL46C.GL_INT_VEC4,GL46C.GL_BOOL_VEC4 -> {GL46C.glGetUniformiv(source,u.source,v);GL46C.glUniform4i(u.target,v.get(0),v.get(1),v.get(2),v.get(3));}
                    default -> {GL46C.glGetUniformiv(source,u.source,v);GL46C.glUniform1i(u.target,v.get(0));}
                }
            }
        }
    }

    @Override public void close() { GL46C.glUseProgram(previousProgram); }
}
