import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.ui.paperdoll.PaperDollFrameCache;
import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.nio.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Production offscreen cache: pixel oracle, target isolation, refresh and failure boundaries. */
public class PaperDollGlProbe {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static ByteBuffer bytes(int size) { return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()); }
    static int program, vao, target, texture, depth;
    static void draw() {
        RenderSystem.enableBlend(); RenderSystem.disableCull(); RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL46C.GL_ALWAYS); RenderSystem.depthMask(true);
        RenderSystem.blendEquation(GL46C.GL_FUNC_ADD);
        RenderSystem.blendFuncSeparate(GL46C.GL_SRC_ALPHA, GL46C.GL_ONE_MINUS_SRC_ALPHA,
                GL46C.GL_ONE, GL46C.GL_ONE_MINUS_SRC_ALPHA);
        GL46C.glUseProgram(program); GL46C.glBindVertexArray(vao);
        int loc = GL46C.glGetUniformLocation(program, "Tint");
        GL46C.glUniform4f(loc, .8f, .3f, .1f, .4f); GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        GL46C.glUniform4f(loc, .1f, .8f, .4f, .5f); GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
        RenderSystem.disableDepthTest(); RenderSystem.depthMask(false);
    }
    static void background() {
        RenderSystem.disableScissor(); RenderSystem.colorMask(true,true,true,true); RenderSystem.depthMask(true);
        GL46C.glClearBufferfv(GL46C.GL_COLOR, 0, new float[]{.12f,.23f,.34f,1});
        GL46C.glClearBufferfv(GL46C.GL_DEPTH, 0, new float[]{.17f});
        RenderSystem.depthMask(false); RenderSystem.disableDepthTest();
        RenderSystem.viewport(4,5,48,48); RenderSystem.enableScissor(7,9,25,28);
    }
    static ByteBuffer pixels() {
        ByteBuffer out=bytes(64*64*4); GL46C.glReadPixels(0,0,64,64,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,out); return out;
    }
    static void same(ByteBuffer expected, ByteBuffer actual) {
        int max=0; for(int i=0;i<expected.capacity();i++) max=Math.max(max,Math.abs((expected.get(i)&255)-(actual.get(i)&255)));
        check(max<=2,"premultiplied composite differs by "+max);
    }
    static int[] state() {
        int[] out=new int[32]; int n=0;
        for(int property:new int[]{GL46C.GL_DRAW_FRAMEBUFFER_BINDING,GL46C.GL_READ_FRAMEBUFFER_BINDING,
                GL46C.GL_CURRENT_PROGRAM,GL46C.GL_VERTEX_ARRAY_BINDING,GL46C.GL_ACTIVE_TEXTURE,
                GL46C.GL_BLEND_SRC_RGB,GL46C.GL_BLEND_DST_RGB,GL46C.GL_BLEND_SRC_ALPHA,GL46C.GL_BLEND_DST_ALPHA,
                GL46C.GL_BLEND_EQUATION_RGB,GL46C.GL_BLEND_EQUATION_ALPHA}) out[n++]=GL46C.glGetInteger(property);
        for(int property:new int[]{GL46C.GL_BLEND,GL46C.GL_DEPTH_TEST,GL46C.GL_CULL_FACE,GL46C.GL_SCISSOR_TEST})
            out[n++]=GL46C.glIsEnabled(property)?1:0;
        out[n++]=GL46C.glGetBoolean(GL46C.GL_DEPTH_WRITEMASK)?1:0;
        int[] array=new int[4]; GL46C.glGetIntegerv(GL46C.GL_VIEWPORT,array); for(int v:array)out[n++]=v;
        GL46C.glGetIntegerv(GL46C.GL_SCISSOR_BOX,array); for(int v:array)out[n++]=v;
        int active=GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE); RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
        out[n++]=GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D); out[n]=GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING,0);
        RenderSystem.activeTexture(active); return out;
    }
    static void depthUnchanged() {
        FloatBuffer data=bytes(64*64*4).asFloatBuffer(); GL46C.glReadPixels(0,0,64,64,GL46C.GL_DEPTH_COMPONENT,GL46C.GL_FLOAT,data);
        for(int i=0;i<data.capacity();i++) check(Math.abs(data.get(i)-.17f)<.00001f,"offscreen depth leaked");
    }
    public static void main(String[] args) {
        check(GLFW.glfwInit(),"GLFW"); GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4); GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
        long window=GLFW.glfwCreateWindow(64,64,"paperdoll probe",0,0); check(window!=0,"window");
        try { GLFW.glfwMakeContextCurrent(window); GL.createCapabilities(); run(); }
        finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }
    static void run() {
        RenderSystem.initRenderThread();
        program=ShaderCompiler.compileRenderProgram("""
                #version 330 core
                void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,.2,1);}
                """, "#version 330 core\nuniform vec4 Tint;out vec4 Color;void main(){Color=Tint;}","cache probe");
        check(program!=0,"shader"); vao=GL46C.glGenVertexArrays();
        target=GL46C.glGenFramebuffers(); GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER,target);
        texture=GL46C.glGenTextures(); RenderSystem.bindTexture(texture);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D,0,GL46C.GL_RGBA8,64,64,0,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,0L);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER,GL46C.GL_COLOR_ATTACHMENT0,GL46C.GL_TEXTURE_2D,texture,0);
        depth=GL46C.glGenRenderbuffers(); GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER,depth);
        GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER,GL46C.GL_DEPTH_COMPONENT24,64,64);
        GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER,GL46C.GL_DEPTH_ATTACHMENT,GL46C.GL_RENDERBUFFER,depth);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0); check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER)==GL46C.GL_FRAMEBUFFER_COMPLETE,"target");
        background(); draw(); ByteBuffer reference=pixels();
        background(); int[] expectedState=state();
        var count=new AtomicInteger(); Runnable callback=()->{count.incrementAndGet();draw();};
        try(var cache=new PaperDollFrameCache()) {
            check(cache.render("a",30,0,callback),"initial render"); same(reference,pixels()); depthUnchanged();
            check(Arrays.equals(expectedState,state()),"refresh GL state");
            background(); expectedState=state(); check(cache.render("a",30,1_000_000,callback),"cache hit");
            same(reference,pixels()); depthUnchanged(); check(count.get()==1,"hit skipped callback");
            check(Arrays.equals(expectedState,state()),"hit GL state");
            check(cache.render("a",30,40_000_000,callback)&&count.get()==2,"rate refresh");
            check(cache.render("b",30,40_000_001,callback)&&count.get()==3,"key refresh");
            cache.invalidate(); check(cache.render("b",30,40_000_002,callback)&&count.get()==4,"explicit invalidation");
            RenderSystem.viewport(2,3,32,30); check(cache.render("b",30,40_000_003,callback)&&count.get()==5,"resize invalidation");
            check(cache.vramBytes()==32*30*8,"budget accounting");
            cache.invalidate(); int[] beforeFailure=state(); boolean threw=false;
            try { cache.render("b",30,50_000_000,()->{throw new IllegalStateException("probe");}); }
            catch(IllegalStateException expected){threw=true;}
            check(threw&&Arrays.equals(beforeFailure,state()),"exception target restore");
            check(cache.render("b",30,50_000_001,callback)&&count.get()==6,"failed refresh must retry");
            for(int unsupported:new int[]{GL46C.GL_STENCIL_TEST,GL46C.GL_COLOR_LOGIC_OP,GL46C.GL_FRAMEBUFFER_SRGB}) {
                GL46C.glEnable(unsupported); check(!cache.render("b",30,60_000_000,()->{throw new AssertionError("unsupported target callback");}),"fallback"); GL46C.glDisable(unsupported);
            }
            GL46C.glDrawBuffers(new int[]{GL46C.GL_COLOR_ATTACHMENT0,GL46C.GL_COLOR_ATTACHMENT1});
            check(!cache.render("b",30,60_000_000,callback),"MRT fallback"); GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
            GL46C.glEnablei(GL46C.GL_BLEND,1); GL46C.glDisablei(GL46C.GL_BLEND,0);
            check(!cache.render("b",30,60_000_000,callback),"indexed blend fallback");
            GL46C.glEnablei(GL46C.GL_BLEND,0);
            cache.close(); check(cache.vramBytes()==0,"release");
        }
        check(GL46C.glGetError()==0,"GL error");
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER,0); GL46C.glDeleteFramebuffers(target);
        GL46C.glDeleteTextures(texture); GL46C.glDeleteRenderbuffers(depth);GL46C.glDeleteProgram(program);GL46C.glDeleteVertexArrays(vao);
        System.out.println("PASS paperdoll GL composite, depth, clock, target and state boundaries");
    }
}
