package com.shiroha.mmdskin.render.outline;

import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL46C;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** 生产描边 pass 的覆盖率、遮挡、MRT 和 GL 状态回归。 */
public final class OutlineCoverageProbe {
    private static final float[] BACKGROUND = {.2f,.3f,.4f,.25f};
    private static final int SIZE = 64;

    public static void run(ToonShaderCpu shader) {
        ConfigManager.init(new ConfigManager.IConfigProvider() {
            public boolean isOpenGLLightingEnabled() { return true; }
            public int getModelPoolMaxCount() { return 20; }
            public boolean isMMDShaderEnabled() { return false; }
            public String getMobModelReplacement(String id) { return ""; }
            public float getToonOutlineWidth() { return .001f; }
            public float getToonOutlineR() { return 1; }
            public float getToonOutlineG() { return 1; }
            public float getToonOutlineB() { return 1; }
        });
        int fbo = GL46C.glGenFramebuffers();
        int main = texture(SIZE, null), meta = texture(SIZE, null);
        int alpha = texture(1, new float[]{.2f,.8f,.1f,.8f});
        int depth = GL46C.glGenRenderbuffers();
        int callerVao = GL46C.glGenVertexArrays();
        GL46C.glBindVertexArray(callerVao);
        int positions = buffer(new float[9]);
        int normals = buffer(new float[]{0,0,-1, 0,0,-1, 0,0,-1});
        int uv = buffer(new float[6]);
        int indices = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,indices);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER,new int[]{0,1,2},GL46C.GL_STATIC_DRAW);
        ByteBuffer meshes = BufferUtils.createByteBuffer(20).order(ByteOrder.nativeOrder());
        meshes.putInt(0,0).putInt(4,0).putInt(8,3).putFloat(12,.5f).put(16,(byte)1);
        FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
        matrix.put(new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}).flip();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER,fbo);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER,GL46C.GL_COLOR_ATTACHMENT0,GL46C.GL_TEXTURE_2D,main,0);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER,GL46C.GL_COLOR_ATTACHMENT1,GL46C.GL_TEXTURE_2D,meta,0);
        GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER,depth);
        GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER,GL46C.GL_DEPTH24_STENCIL8,SIZE,SIZE);
        GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER,GL46C.GL_DEPTH_STENCIL_ATTACHMENT,GL46C.GL_RENDERBUFFER,depth);
        GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0,GL46C.GL_COLOR_ATTACHMENT1});
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER)==GL46C.GL_FRAMEBUFFER_COMPLETE,"outline probe FBO");
        GL46C.glViewport(0,0,SIZE,SIZE);
        try {
            int fractional = 0;
            for (int phase=0; phase<4; phase++) {
                if (phase == 2) {
                    ToonOutputProfile reordered = ToonOutputProfile.resolve("ComplementaryShaders_v4.7.1", "gbuffers_entities",
                            "albedo.rgb = pow(albedo.rgb, vec3(2.2)); /* DRAWBUFFERS:70 */ "
                                    + "gl_FragData[0] = vec4(1.0); gl_FragData[1] = albedo;", new int[]{7,0});
                    check(shader.selectOutputProfile(reordered),"reordered color profile");
                }
                int[] routing = phase < 2 ? new int[]{GL46C.GL_COLOR_ATTACHMENT0,GL46C.GL_COLOR_ATTACHMENT1}
                        : new int[]{GL46C.GL_COLOR_ATTACHMENT1,GL46C.GL_COLOR_ATTACHMENT0};
                GL46C.glDrawBuffers(routing);
                prepareDepth(1, .1f);
                if (phase == 3) GL46C.glColorMaski(1,true,false,true,true);
                float shift=phase*.25f;
                float[] p={coordinate(8+shift),coordinate(8),-.2f,
                        coordinate(8+shift),coordinate(55),-.2f,
                        coordinate(55+shift),coordinate(12),-.2f};
                GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,positions);
                GL46C.glBufferSubData(GL46C.GL_ARRAY_BUFFER,0,p);
                GL46C.glActiveTexture(GL46C.GL_TEXTURE3);
                GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
                GL46C.glScissor(0,0,SIZE,SIZE);
                GL46C.glDepthFunc(GL46C.GL_LEQUAL);
                GL46C.glDepthMask(true);
                GL46C.glDisable(GL46C.GL_CULL_FACE);
                GL46C.glBlendEquationSeparatei(0,GL46C.GL_FUNC_ADD,GL46C.GL_FUNC_REVERSE_SUBTRACT);
                GL46C.glBlendFuncSeparatei(0,GL46C.GL_SRC_ALPHA,GL46C.GL_ONE_MINUS_SRC_ALPHA,GL46C.GL_ZERO,GL46C.GL_ONE);
                GL46C.glDisablei(GL46C.GL_BLEND,0);
                GL46C.glEnablei(GL46C.GL_BLEND,1);
                GL46C.glBlendFuncSeparatei(1,GL46C.GL_ONE,GL46C.GL_ZERO,GL46C.GL_ONE,GL46C.GL_ZERO);
                shader.useMain();
                int oldProgram=GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
                OutlineRenderPass.Inputs inputs=new OutlineRenderPass.Inputs(positions,normals,uv,meshes,1,4,
                        GL46C.GL_UNSIGNED_INT,matrix,matrix,1);
                OutlineRenderPass.draw(shader,inputs,(id,a)->a,id->alpha);
                check(GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM)==oldProgram,"program restoration");
                check(GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING)==callerVao,"caller VAO restoration");
                check(GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE)==GL46C.GL_TEXTURE3,"active texture restoration");
                check(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING)==fbo,"draw FBO restoration");
                check(GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING)==fbo,"read FBO restoration");
                check(GL46C.glGetInteger(GL46C.GL_DRAW_BUFFER1)==routing[1],"MRT routing restoration");
                check(GL46C.glGetInteger(GL46C.GL_DEPTH_FUNC)==GL46C.GL_LEQUAL && GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK),"depth restoration");
                check(!GL46C.glIsEnabled(GL46C.GL_CULL_FACE) && !GL46C.glIsEnabledi(GL46C.GL_BLEND,0),"cull/blend restoration");
                check(GL46C.glGetIntegeri(GL46C.GL_BLEND_SRC_RGB,1)==GL46C.GL_ONE,"other attachment blend restoration");
                check(GL46C.glGetIntegeri(GL46C.GL_BLEND_EQUATION_ALPHA,0)==GL46C.GL_FUNC_REVERSE_SUBTRACT,"alpha equation restoration");
                GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
                float[] pixels=new float[SIZE*SIZE*4];
                GL46C.glReadPixels(0,0,SIZE,SIZE,GL46C.GL_RGBA,GL46C.GL_FLOAT,pixels);
                int inside=0;
                for (int y=0;y<SIZE;y++) for(int x=0;x<SIZE;x++) {
                    int off=(y*SIZE+x)*4;
                    if(phase==3) close(pixels[off+1],BACKGROUND[1],.0001f,"target color mask respected");
                    if(x<16) for(int c=0;c<4;c++) close(pixels[off+c],BACKGROUND[c],.0001f,"foreground occlusion");
                    if(pixels[off+3]>.26f && pixels[off+3]<.54f) fractional++;
                    if(pixels[off+3]>.54f) {
                        inside++;
                        close(pixels[off],.52f,.004f,"premultiplied RGB with texture alpha once");
                        close(pixels[off+3],.55f,.004f,"effective alpha once");
                    }
                }
                check(inside>100,"outline visible outside occluder");
                GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT1);
                GL46C.glReadPixels(0,0,SIZE,SIZE,GL46C.GL_RGBA,GL46C.GL_FLOAT,pixels);
                for(int i=0;i<pixels.length;i++) close(pixels[i],BACKGROUND[i%4],.0001f,"metadata untouched");
                // 相同输入强制异常，检查 finally 是否恢复目标和 VAO。
                try {
                    OutlineRenderPass.draw(shader,inputs,(id,a)->{throw new IllegalStateException("probe");},id->alpha);
                    throw new AssertionError("resolver should throw");
                } catch(IllegalStateException expected) {
                    check(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING)==fbo,"exception FBO restoration");
                    check(GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING)==callerVao,"exception VAO restoration");
                }
            }
            // 实际 Iris 使用 texture 深度；同时覆盖合并 stencil 与纯浮点深度。
            int depthTexture=GL46C.glGenTextures();
            try {
                GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,depthTexture);
                GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER,GL46C.GL_DEPTH_STENCIL_ATTACHMENT,GL46C.GL_RENDERBUFFER,0);
                for(int format:new int[]{GL46C.GL_DEPTH_COMPONENT32F,GL46C.GL_DEPTH24_STENCIL8,GL46C.GL_DEPTH32F_STENCIL8}) {
                    boolean packed=format!=GL46C.GL_DEPTH_COMPONENT32F;
                    GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER,GL46C.GL_DEPTH_STENCIL_ATTACHMENT,GL46C.GL_TEXTURE_2D,0,0);
                    GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D,0,format,SIZE,SIZE,0,
                            packed?GL46C.GL_DEPTH_STENCIL:GL46C.GL_DEPTH_COMPONENT,
                            format==GL46C.GL_DEPTH24_STENCIL8?GL46C.GL_UNSIGNED_INT_24_8:
                            format==GL46C.GL_DEPTH32F_STENCIL8?GL46C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV:GL46C.GL_FLOAT,0L);
                    GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER,packed?GL46C.GL_DEPTH_STENCIL_ATTACHMENT:GL46C.GL_DEPTH_ATTACHMENT,
                            GL46C.GL_TEXTURE_2D,depthTexture,0);
                    prepareDepth(1,.1f);
                    for(int[] viewport:new int[][]{{0,0,SIZE,SIZE},{4,3,40,42},{2,5,31,32},{0,0,SIZE,SIZE}}) {
                        GL46C.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
                        try(OutlineCoverage pass=OutlineCoverage.begin(0)) {
                            check(pass.isSampled(),"texture depth sampling: "+format);
                            pass.composite();
                        }
                        int[] restored=new int[4];GL46C.glGetIntegerv(GL46C.GL_VIEWPORT,restored);
                        check(java.util.Arrays.equals(viewport,restored),"resize/offset viewport restoration");
                        check(GL46C.glGetError()==GL46C.GL_NO_ERROR,"depth format/resize GL error: "+format);
                    }
                }
            } finally { GL46C.glDeleteTextures(depthTexture); }
            check(fractional>100,"diagonal edges need partial coverage across subpixel positions");
            check(GL46C.glGetError()==GL46C.GL_NO_ERROR,"outline GL errors");
            System.out.println("PASS outline coverage: subpixel phases=4 partial pixels="+fractional+", occlusion, alpha, MRT, state, exceptions, texture depth formats=3, resize/offset, reordered color slot/mask");
        } finally {
            OutlineCoverage.release();
            ConfigManager.init(null);
            shader.selectOutputProfile(ToonOutputProfile.vanilla());
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER,0);
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glDisable(GL46C.GL_BLEND);
            GL46C.glBindVertexArray(0);
            GL46C.glDeleteVertexArrays(callerVao);
            for(int id:new int[]{positions,normals,uv,indices}) GL46C.glDeleteBuffers(id);
            for(int id:new int[]{main,meta,alpha}) GL46C.glDeleteTextures(id);
            GL46C.glDeleteRenderbuffers(depth);
            GL46C.glDeleteFramebuffers(fbo);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        }
    }

    private static float coordinate(float p) { return p/SIZE*2-1; }
    private static int buffer(float[] data) {
        int id=GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,id);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,data,GL46C.GL_DYNAMIC_DRAW);
        return id;
    }
    private static int texture(int size,float[] data) {
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        int id=GL46C.glGenTextures();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,id);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D,0,GL46C.GL_RGBA32F,size,size,0,GL46C.GL_RGBA,GL46C.GL_FLOAT,data);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MIN_FILTER,GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MAG_FILTER,GL46C.GL_NEAREST);
        return id;
    }
    private static void prepareDepth(float back,float front) {
        GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        GL46C.glDepthMask(true);
        GL46C.glColorMask(true,true,true,true);
        GL46C.glClearDepth(back);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glClearBufferfv(GL46C.GL_COLOR,0,BACKGROUND);
        GL46C.glClearBufferfv(GL46C.GL_COLOR,1,BACKGROUND);
        GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
        GL46C.glScissor(0,0,16,SIZE);
        GL46C.glClearDepth(front);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
    }
    private static void close(float actual,float expected,float tolerance,String what) {
        check(Math.abs(actual-expected)<tolerance,what+": "+actual+" != "+expected);
    }
    private static void check(boolean test,String what) { if(!test) throw new AssertionError(what); }
}
