package com.shiroha.mmdskin.render.shader;

import org.lwjgl.opengl.GL46C;

/** 独立程序、逗号声明、数组、矩阵及类型不匹配回退。 */
public final class UniformBridgeProbe {
    private static final String VERTEX = "#version 330 core\nvoid main(){gl_Position=vec4(0.0);}";
    private static final String DECLARATIONS = """
            uniform float first, second;
            uniform float weights[2];
            uniform mat4 transform;
            uniform sampler2D image;
            uniform int mode;
            """;
    private static final String FRAGMENT = "#version 330 core\nlayout(location=0) out vec4 c;\n"
            + DECLARATIONS + "void main(){c=vec4(float(first)+second+weights[0]+weights[1]+transform[0][0]+texture(image,vec2(0.5)).r+float(mode));}";

    public static void run() {
        int source = compile(FRAGMENT);
        int target = compile(FRAGMENT.replace("layout(location=0) out vec4 c;", "layout(location=0) out vec4 c;\nuniform float extra;")
                .replace("float(first)", "extra+float(first)"));
        int next = compile(FRAGMENT);
        int invalid = compile(FRAGMENT.replace("uniform float first, second;", "uniform int first; uniform float second;"));
        int missing = compile(FRAGMENT.replace("uniform float first, second;", "uniform float first;").replace("+second", ""));
        try {
            GL46C.glUseProgram(source);
            GL46C.glUniform1f(location(source,"first"),.25f);
            GL46C.glUniform1f(location(source,"second"),.75f);
            GL46C.glUniform1fv(location(source,"weights"),new float[]{.2f,.8f});
            GL46C.glUniformMatrix4fv(location(source,"transform"),false,new float[]{2,0,0,0, 0,3,0,0, 0,0,4,0, 0,0,0,1});
            GL46C.glUniform1i(location(source,"image"),6);
            GL46C.glUniform1i(location(source,"mode"),3);
            Object identity = new Object();
            ToonUniformBridge bridge = new ToonUniformBridge(DECLARATIONS);
            for (int program : new int[]{target,next,target}) {
                GL46C.glUseProgram(program);
                check(bridge.copy(source,program,identity),bridge.failureReason());
                near(GL46C.glGetUniformf(program,location(program,"first")),.25f);
                near(GL46C.glGetUniformf(program,location(program,"second")),.75f);
                near(GL46C.glGetUniformf(program,location(program,"weights[1]")),.8f);
                float[] matrix = new float[16];
                GL46C.glGetUniformfv(program,location(program,"transform"),matrix);
                near(matrix[0],2);
                near(matrix[5],3);
                check(GL46C.glGetUniformi(program,location(program,"image")) == 6,"采样器单元");
                check(GL46C.glGetUniformi(program,location(program,"mode")) == 3,"整数输入");
            }
            check(!bridge.copy(invalid,target,new Object()) && bridge.failureReason().contains("类型"),"类型不匹配必须显式回退");
            check(!bridge.copy(missing,target,new Object()) && bridge.failureReason().contains("second"),"缺少输入必须显式回退");
            int maxUnits = GL46C.glGetInteger(GL46C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
            check(!ToonUniformBridge.isSamplerUnitValid(-1, maxUnits), "负采样器单元必须拒绝");
            check(ToonUniformBridge.isSamplerUnitValid(maxUnits - 1, maxUnits), "末尾有效采样器单元");
            check(!ToonUniformBridge.isSamplerUnitValid(maxUnits, maxUnits), "越界采样器单元必须拒绝");
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR,"uniform 同步 GL 错误");
            System.out.println("PASS uniform bridge: comma declarations, arrays, matrices, samplers, independent locations, target switch, invalid/missing inputs");
        } finally {
            GL46C.glUseProgram(0);
            for (int program : new int[]{source,target,next,invalid,missing}) GL46C.glDeleteProgram(program);
        }
    }

    private static int compile(String source) {
        int program = ShaderCompiler.compileRenderProgram(VERTEX,source,"uniform 同步探针");
        check(program > 0,"来源程序编译");
        return program;
    }

    private static int location(int program,String name) { return GL46C.glGetUniformLocation(program,name); }
    private static void near(float actual,float expected) { check(Math.abs(actual-expected)<.0001f,"同步值不一致"); }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
