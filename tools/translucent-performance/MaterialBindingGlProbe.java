package com.shiroha.mmdskin.render.material;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;

/** 真实 GL 校验重复设置、单字段变化和 Iris 颜色输入分支。 */
public final class MaterialBindingGlProbe {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static int compile(int type, String source) {
        int shader = GL46C.glCreateShader(type);
        GL46C.glShaderSource(shader, source);
        GL46C.glCompileShader(shader);
        check(GL46C.glGetShaderi(shader, GL46C.GL_COMPILE_STATUS) != 0, GL46C.glGetShaderInfoLog(shader));
        return shader;
    }

    static int program(String vertex, String fragment) {
        int vs = compile(GL46C.GL_VERTEX_SHADER, "#version 330 core\n" + vertex);
        int fs = compile(GL46C.GL_FRAGMENT_SHADER, "#version 330 core\n" + fragment);
        int program = GL46C.glCreateProgram();
        GL46C.glAttachShader(program, vs); GL46C.glAttachShader(program, fs);
        GL46C.glLinkProgram(program);
        check(GL46C.glGetProgrami(program, GL46C.GL_LINK_STATUS) != 0, GL46C.glGetProgramInfoLog(program));
        GL46C.glDeleteShader(vs); GL46C.glDeleteShader(fs);
        GL46C.glUseProgram(program);
        return program;
    }

    static void vector(int program, String name, float... expected) {
        int location = GL46C.glGetUniformLocation(program, name);
        check(location >= 0, "missing uniform " + name);
        float[] actual = new float[expected.length];
        GL46C.glGetUniformfv(program, location, actual);
        for (int i = 0; i < expected.length; i++) check(Math.abs(actual[i] - expected[i]) < 1e-6f, name + " channel " + i);
    }

    static void colorInputs(boolean attributes) {
        String vertex = attributes
                ? "in vec4 Color; in vec4 iris_Color; out vec4 c; void main(){c=Color+iris_Color;gl_Position=vec4(0,0,0,1);}"
                : "void main(){gl_Position=vec4(0,0,0,1);}";
        int p = program(vertex, (attributes ? "in vec4 c;" : "")
                + "uniform vec4 iris_ColorModulator; uniform int AlphaPass; out vec4 result;"
                + "void main(){result=" + (attributes ? "c*" : "") + "iris_ColorModulator*float(AlphaPass+1);}");
        int modulator = GL46C.glGetUniformLocation(p, "iris_ColorModulator");
        GL46C.glUniform4f(modulator, .8f, .7f, .6f, .3f);
        int[] locations = {GL46C.glGetAttribLocation(p, "Color"), GL46C.glGetAttribLocation(p, "iris_Color")};
        for (int location : locations) if (location >= 0) {
            GL46C.glVertexAttrib4f(location, .1f, .2f, .3f, .4f);
            GL46C.glEnableVertexAttribArray(location);
        }
        MaterialRenderState state = new MaterialRenderState();
        state.color[0] = .5f; state.color[1] = .4f; state.color[2] = .3f; state.alpha = .2f;
        try (var binding = new MaterialShaderBinding()) {
            for (int repeat = 0; repeat < 4; repeat++) {
                binding.apply(state, repeat % 3);
                binding.apply(state, repeat % 3);
                vector(p, "iris_ColorModulator", attributes ? .8f : .8f * state.color[0],
                        attributes ? .7f : .7f * state.color[1], attributes ? .6f : .6f * state.color[2],
                        attributes ? 1 : state.alpha);
                check(GL46C.glGetUniformi(p, GL46C.glGetUniformLocation(p, "AlphaPass")) == repeat % 3, "alpha pass changes");
                for (int location : locations) if (location >= 0) {
                    float[] color = new float[4];
                    GL46C.glGetVertexAttribfv(location, GL46C.GL_CURRENT_VERTEX_ATTRIB, color);
                    check(color[0] == state.color[0] && color[3] == state.alpha, "color attribute updated");
                    check(GL46C.glGetVertexAttribi(location, GL46C.GL_VERTEX_ATTRIB_ARRAY_ENABLED) == 0, "array disabled");
                }
                state.color[0] += .1f; state.alpha += .1f;
            }
        }
        vector(p, "iris_ColorModulator", .8f, .7f, .6f, .3f);
        check(GL46C.glGetUniformi(p, GL46C.glGetUniformLocation(p, "AlphaPass")) == 0, "alpha pass restored");
        for (int location : locations) if (location >= 0) {
            float[] color = new float[4]; GL46C.glGetVertexAttribfv(location, GL46C.GL_CURRENT_VERTEX_ATTRIB, color);
            check(color[0] == .1f && color[3] == .4f, "host color restored");
            check(GL46C.glGetVertexAttribi(location, GL46C.GL_VERTEX_ATTRIB_ARRAY_ENABLED) != 0, "host array restored");
            GL46C.glDisableVertexAttribArray(location);
        }
        GL46C.glDeleteProgram(p);
    }

    static void toonInputs() {
        int p = program("void main(){gl_Position=vec4(0,0,0,1);}",
                "uniform vec4 MaterialColor; uniform vec3 MaterialSpecular; uniform float MaterialSpecularPower;"
                + "uniform int AlphaPass; out vec4 result; void main(){result=MaterialColor+vec4(MaterialSpecular*MaterialSpecularPower,float(AlphaPass));}");
        var state = new MaterialRenderState();
        state.resolve(new ModelMaterial(), null, 0, .4f);
        try (var binding = new MaterialShaderBinding()) {
            binding.apply(state, 1); binding.apply(state, 1);
            vector(p, "MaterialColor", 1, 1, 1, .4f);
            vector(p, "MaterialSpecular", 0, 0, 0);
            state.specular[1] = .7f; state.specularPower = 21;
            binding.apply(state, 2);
            vector(p, "MaterialColor", 1, 1, 1, .4f);
            vector(p, "MaterialSpecular", 0, .7f, 0);
            vector(p, "MaterialSpecularPower", 21);
            state.alpha = .8f; binding.apply(state, 2);
            vector(p, "MaterialColor", 1, 1, 1, .8f);
        }
        GL46C.glDeleteProgram(p);
    }

    static void sphereInputs() {
        int p = program("void main(){gl_Position=vec4(0,0,0,1);}",
                "uniform int SphereMode; uniform vec4 SphereMul, SphereAdd; uniform sampler2D SphereTexture;"
                + "out vec4 result; void main(){result=texture(SphereTexture,vec2(0))*SphereMul+SphereAdd+vec4(float(SphereMode));}");
        int location = GL46C.glGetUniformLocation(p, "SphereTexture");
        GL46C.glUniform1i(location, 1);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE2);
        int[] textures = {GL46C.glGenTextures(), GL46C.glGenTextures()};
        ModelMaterial material = new ModelMaterial();
        material.sphereTex = textures[0]; material.sphereMode = 1;
        var state = new MaterialRenderState(); state.resolve(material, null, 0, 1);
        int unit;
        try (var sphere = new SphereTextureBinding(false)) {
            unit = GL46C.glGetUniformi(p, location);
            check(unit >= 3, "sphere uses free unit");
            sphere.apply(material, state); sphere.apply(material, state);
            vector(p, "SphereMul", 1, 1, 1, 1); vector(p, "SphereAdd", 0, 0, 0, 0);
            material.sphereMode = 0; sphere.apply(material, state);
            check(GL46C.glGetUniformi(p, GL46C.glGetUniformLocation(p, "SphereMode")) == 0, "sphere disabled");
            material.sphereMode = 2; material.sphereTex = textures[1];
            state.sphereMul[0] = .3f; state.sphereAdd[3] = .6f;
            sphere.apply(material, state); sphere.apply(material, state);
            vector(p, "SphereMul", .3f, 1, 1, 1); vector(p, "SphereAdd", 0, 0, 0, .6f);
            check(GL46C.glGetUniformi(p, GL46C.glGetUniformLocation(p, "SphereMode")) == 2, "sphere mode changed");
            check(GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE) == GL46C.GL_TEXTURE2, "sphere active unit restored");
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
            check(GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D) == textures[1], "sphere texture changed");
            GL46C.glActiveTexture(GL46C.GL_TEXTURE2);
        }
        check(GL46C.glGetUniformi(p, location) == 1, "sphere sampler restored");
        check(GL46C.glGetUniformi(p, GL46C.glGetUniformLocation(p, "SphereMode")) == 0, "sphere mode reset");
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
        check(GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D) == 0, "sphere host texture restored");
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glDeleteTextures(textures); GL46C.glDeleteProgram(p);
    }

    public static void main(String[] args) {
        check(GLFW.glfwInit(), "GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(32, 32, "material binding probe", 0, 0);
        check(window != 0, "GL window");
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            int vao = GL46C.glGenVertexArrays(); GL46C.glBindVertexArray(vao);
            toonInputs(); colorInputs(true); colorInputs(false); sphereInputs();
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "GL error");
            GL46C.glDeleteVertexArrays(vao);
            System.out.println("MaterialBindingGlProbe PASS: repeated/changed state, Toon uniforms, Iris color attributes/modulator, sphere mode/factors/textures, host state restoration");
        } finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }
}
