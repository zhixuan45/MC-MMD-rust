package com.shiroha.mmdskin.render.pipeline.transparency;

import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** A draw-time clone: callers continue uploading material/chunk uniforms to the original program. */
final class TransparencyProgram implements AutoCloseable {
    private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{");
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
    private record Uniform(int from, int to, int type) {}
    final int source, program;
    private final List<Uniform> uniforms = new ArrayList<>();
    private final int[] shaders;

    TransparencyProgram(int source, String declarations, String capture) {
        this.source = source;
        shaders = attached(source);
        int vertex = 0;
        String fragment = null;
        for (int shader : shaders) {
            switch (GL46C.glGetShaderi(shader, GL46C.GL_SHADER_TYPE)) {
                case GL46C.GL_VERTEX_SHADER -> vertex = shader;
                case GL46C.GL_FRAGMENT_SHADER -> fragment = GL46C.glGetShaderSource(shader);
                default -> throw new IllegalArgumentException("Unsupported world transparency shader stage");
            }
        }
        if (vertex == 0 || fragment == null) throw new IllegalArgumentException("Missing shader source");
        String output = colorOutput(source);
        if (output == null || fragment.contains("gl_FragDepth"))
            throw new IllegalArgumentException("Unsupported world transparency output");
        var main = MAIN.matcher(COMMENTS.matcher(fragment).replaceAll(m -> " ".repeat(m.group().length())));
        if (!main.find()) throw new IllegalArgumentException("Missing fragment main");
        // Keep #extension directives in place. Renaming main preserves returns and all alpha discards.
        fragment = fragment.substring(0, main.start()) + declarations + "\nvoid mmdOriginalMain() {"
                + fragment.substring(main.end()) + "\nvoid main() { mmdOriginalMain();\n"
                + capture.replace("MMD_COLOR", output) + "\n}\n";
        fragment = fragment.replaceFirst("#version[^\\r\\n]*", "#version 430 core");
        int fs = compile(GL46C.GL_FRAGMENT_SHADER, fragment);
        int clone = GL46C.glCreateProgram();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL46C.glAttachShader(clone, vertex);
            GL46C.glAttachShader(clone, fs);
            IntBuffer size = stack.mallocInt(1), type = stack.mallocInt(1);
            for (int i = 0; i < GL46C.glGetProgrami(source, GL46C.GL_ACTIVE_ATTRIBUTES); i++) {
                String name = GL46C.glGetActiveAttrib(source, i, size, type);
                int location = GL46C.glGetAttribLocation(source, name);
                if (location >= 0) GL46C.glBindAttribLocation(clone, location, name);
            }
            GL46C.glBindFragDataLocation(clone, 0, output);
            GL46C.glLinkProgram(clone);
            if (GL46C.glGetProgrami(clone, GL46C.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL46C.glGetProgramInfoLog(clone));
            for (int i = 0; i < GL46C.glGetProgrami(source, GL46C.GL_ACTIVE_UNIFORMS); i++) {
                String name = GL46C.glGetActiveUniform(source, i, size, type);
                for (int j = 0; j < size.get(0); j++) {
                    String entry = size.get(0) > 1 ? name.replace("[0]", "[" + j + "]") : name;
                    int from = GL46C.glGetUniformLocation(source, entry), to = GL46C.glGetUniformLocation(clone, entry);
                    if (from >= 0 && to >= 0) uniforms.add(new Uniform(from, to, type.get(0)));
                }
            }
            for (int i = 0; i < GL46C.glGetProgrami(source, GL46C.GL_ACTIVE_UNIFORM_BLOCKS); i++) {
                int target = GL46C.glGetUniformBlockIndex(clone, GL46C.glGetActiveUniformBlockName(source, i));
                if (target != GL46C.GL_INVALID_INDEX) GL46C.glUniformBlockBinding(clone, target,
                        GL46C.glGetActiveUniformBlocki(source, i, GL46C.GL_UNIFORM_BLOCK_BINDING));
            }
            program = clone;
        } catch (RuntimeException e) {
            GL46C.glDeleteProgram(clone);
            throw e;
        } finally {
            GL46C.glDeleteShader(fs);
        }
    }

    boolean matches() {
        return GL46C.glIsProgram(source) && java.util.Arrays.equals(shaders, attached(source));
    }

    void bind() {
        GL46C.glUseProgram(program);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer f = stack.mallocFloat(16);
            IntBuffer v = stack.mallocInt(4);
            for (Uniform u : uniforms) {
                switch (u.type) {
                    case GL46C.GL_FLOAT -> { GL46C.glGetUniformfv(source,u.from,f); GL46C.glUniform1f(u.to,f.get(0)); }
                    case GL46C.GL_FLOAT_VEC2 -> { GL46C.glGetUniformfv(source,u.from,f); GL46C.glUniform2f(u.to,f.get(0),f.get(1)); }
                    case GL46C.GL_FLOAT_VEC3 -> { GL46C.glGetUniformfv(source,u.from,f); GL46C.glUniform3f(u.to,f.get(0),f.get(1),f.get(2)); }
                    case GL46C.GL_FLOAT_VEC4 -> { GL46C.glGetUniformfv(source,u.from,f); GL46C.glUniform4f(u.to,f.get(0),f.get(1),f.get(2),f.get(3)); }
                    case GL46C.GL_FLOAT_MAT2 -> { f.limit(4); GL46C.glGetUniformfv(source,u.from,f); GL46C.glUniformMatrix2fv(u.to,false,f); f.limit(16); }
                    case GL46C.GL_FLOAT_MAT3 -> { f.limit(9); GL46C.glGetUniformfv(source,u.from,f); GL46C.glUniformMatrix3fv(u.to,false,f); f.limit(16); }
                    case GL46C.GL_FLOAT_MAT4 -> { GL46C.glGetUniformfv(source,u.from,f); GL46C.glUniformMatrix4fv(u.to,false,f); }
                    case GL46C.GL_INT_VEC2, GL46C.GL_BOOL_VEC2 -> { GL46C.glGetUniformiv(source,u.from,v); GL46C.glUniform2i(u.to,v.get(0),v.get(1)); }
                    case GL46C.GL_INT_VEC3, GL46C.GL_BOOL_VEC3 -> { GL46C.glGetUniformiv(source,u.from,v); GL46C.glUniform3i(u.to,v.get(0),v.get(1),v.get(2)); }
                    case GL46C.GL_INT_VEC4, GL46C.GL_BOOL_VEC4 -> { GL46C.glGetUniformiv(source,u.from,v); GL46C.glUniform4i(u.to,v.get(0),v.get(1),v.get(2),v.get(3)); }
                    default -> { GL46C.glGetUniformiv(source,u.from,v); GL46C.glUniform1i(u.to,v.get(0)); }
                }
            }
        }
    }

    private static int[] attached(int program) {
        int[] ids = new int[GL46C.glGetProgrami(program, GL46C.GL_ATTACHED_SHADERS)];
        GL46C.glGetAttachedShaders(program, (int[]) null, ids);
        return ids;
    }

    private static String colorOutput(int source) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer location = stack.mallocInt(1);
            int count = GL46C.glGetProgramInterfacei(source, GL46C.GL_PROGRAM_OUTPUT, GL46C.GL_ACTIVE_RESOURCES);
            for (int i = 0; i < count; i++) {
                GL46C.glGetProgramResourceiv(source, GL46C.GL_PROGRAM_OUTPUT, i,
                        stack.ints(GL46C.GL_LOCATION), null, location);
                if (location.get(0) == 0)
                    return GL46C.glGetProgramResourceName(source, GL46C.GL_PROGRAM_OUTPUT, i);
            }
            return null;
        }
    }

    static int compile(int type, String source) {
        int shader = GL46C.glCreateShader(type);
        GL46C.glShaderSource(shader, source);
        GL46C.glCompileShader(shader);
        if (GL46C.glGetShaderi(shader, GL46C.GL_COMPILE_STATUS) == 0) {
            String message = GL46C.glGetShaderInfoLog(shader);
            GL46C.glDeleteShader(shader);
            throw new IllegalStateException(message);
        }
        return shader;
    }

    @Override public void close() { GL46C.glDeleteProgram(program); }
}
