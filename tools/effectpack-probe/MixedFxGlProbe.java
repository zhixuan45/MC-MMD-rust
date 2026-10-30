package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.effectpack.EffectPackRuntime;
import com.shiroha.mmdskin.render.effectpack.FxMaterialBinding;
import com.shiroha.mmdskin.render.effectpack.FxShaderSource;
import com.shiroha.mmdskin.render.scene.RenderScene;
import com.shiroha.mmdskin.render.shader.ShaderCompiler;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.shader.ToonShaderBase;
import com.shiroha.mmdskin.render.shader.TranslucentBatchShader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

/** 跨包 Ray/颜色/关闭/继承混合，实际 CPU 材质表窗口和 GPU 排序提交与逐 draw 对比。 */
public final class MixedFxGlProbe {
    private static final int SIZE = 32;
    private static int checks;
    private static final String VERTEX = """
            #version 330 core
            layout(location=0) in vec3 Position;
            out vec2 texCoord0; out vec3 viewNormal; out vec3 viewPos; out vec3 viewLightDir; out float eyeMask;
            void main() {
                texCoord0 = Position.xy * 0.5 + 0.5; viewPos = Position + vec3(0.0,0.0,-2.0);
                viewNormal = vec3(0,0,1); viewLightDir = vec3(0,0,1); eyeMask = 0;
                gl_Position = vec4(Position, 1);
            }
            """;

    public static int run(EffectPackRuntime runtime, String rayA, String rayB, String gray, String warm) throws Exception {
        runtime.settings().characters = gray; runtime.settings().characterModel = "*"; runtime.settings().world = "";
        String[] names = {"skin", "hair", "eyes", "cloth", "prop", "body"};
        String[] effects = {rayA, rayB, gray, warm, ""};
        for (int i = 0; i < effects.length; i++) runtime.settings().bind(false, "mixed", i, names[i], effects[i]);
        int[] owners = {0,1,2,3,4,5,0};
        int oldFbo = GL46C.glGetInteger(GL46C.GL_FRAMEBUFFER_BINDING);
        int fbo = GL46C.glGenFramebuffers(), target = GL46C.glGenTextures(), diffuse = GL46C.glGenTextures();
        int vao = GL46C.glGenVertexArrays(), positions = GL46C.glGenBuffers(), ebo = GL46C.glGenBuffers();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, target);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA32F, SIZE, SIZE, 0, GL46C.GL_RGBA, GL46C.GL_FLOAT, 0L);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, target, 0);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "mixed FBO");
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, diffuse);
        ByteBuffer pixel = buffer(4).put(new byte[]{(byte)170,(byte)130,(byte)90,(byte)255}).flip();
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D,0,GL46C.GL_RGBA8,1,1,0,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,pixel);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MIN_FILTER,GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MAG_FILTER,GL46C.GL_NEAREST);
        GL46C.glBindVertexArray(vao); GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,positions);
        float[] vertices = new float[owners.length * 9];
        int[] indices = new int[owners.length * 3]; ByteBuffer runs = buffer(owners.length * 20);
        for (int i = 0; i < owners.length; i++) {
            float z = -0.8f + i * 0.08f;
            float[] triangle = {-0.85f,-0.8f,z, 0.85f,-0.8f,z, 0,0.85f,z};
            System.arraycopy(triangle,0,vertices,i*9,9);
            for (int j = 0; j < 3; j++) indices[i*3+j] = i*3+j;
            runs.putInt(owners[i]).putInt(i*3).putInt(3).putFloat(0.5f).put((byte)1).put((byte)1).putShort((short)0);
        }
        runs.flip();
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,vertices,GL46C.GL_STATIC_DRAW);
        GL46C.glEnableVertexAttribArray(0); GL46C.glVertexAttribPointer(0,3,GL46C.GL_FLOAT,false,12,0L);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,ebo); GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER,indices,GL46C.GL_STATIC_DRAW);
        ModelMaterial[] materials = new ModelMaterial[names.length];
        for (int i=0;i<materials.length;i++) {
            ModelMaterial m = materials[i] = new ModelMaterial(); m.name = names[i]; m.tex = diffuse;
            m.hasAlpha = true; m.hasTranslucentPixels = true;
        }
        try (var scope = runtime.enter(RenderScene.WORLD,"mixed")) {
            int legacy = compile(runtime,false), batch = compile(runtime,true);
            var register = TranslucentBatchShader.class.getDeclaredMethod("register",int.class); register.setAccessible(true); register.invoke(null,batch);
            var cache = new DrawMaterialCache(materials,null,(i,a)->a,i->diffuse);
            setup(legacy,vao,diffuse);
            try (var fx = new FxMaterialBinding(false); var binding = new MaterialShaderBinding()) {
                clear();
                for (int i=0;i<owners.length;i++) {
                    int owner=owners[i]; binding.apply(cache.resolve(owner,0.5f),2);
                    fx.apply(cache.effect(owner));
                    GL46C.glDrawElements(GL46C.GL_TRIANGLES,3,GL46C.GL_UNSIGNED_INT,i*12L);
                }
            }
            float[] expected = pixels();
            setup(batch,vao,diffuse);
            try (var fx = new FxMaterialBinding(false); var submission = new TranslucentBatchSubmission()) {
                ProgramSamplerLayout full = ProgramSamplerLayout.capture(batch);
                check(full != null && full.capacity() >= 7,"mixed sampler capacity");
                var constructor=ProgramSamplerLayout.class.getDeclaredConstructor(int.class,int[].class); constructor.setAccessible(true);
                ProgramSamplerLayout small=constructor.newInstance(batch,Arrays.copyOf(full.units(),4));
                clear();
                int windows=submission.draw(runs,owners.length,materials,cache,small);
                check(windows>=3,"effect texture windows preserve interleaving: "+windows);
                compare(expected,pixels(),"CPU windows");
                GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,ebo);
                try (var order = new TranslucentDrawOrder()) {
                    ByteBuffer sourceIndices = buffer(indices.length*4); sourceIndices.asIntBuffer().put(indices);
                    var input = TranslucentDrawOrder.Inputs.gpu(positions,vertices.length/3,identity(),identity(),1,false,null,0,0,false)
                            .withIndices(sourceIndices,1);
                    check(order.prepare(input,runs,owners.length,4,materials,null,(id,a)->a,id->diffuse),"mixed GPU prepare");
                    var active=TranslucentDrawOrder.class.getDeclaredField("deviceActive"); active.setAccessible(true);
                    check(active.getBoolean(order),"actual GPU sort path");
                    clear(); GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,order.indexBuffer());
                    check(order.submitBatch(materials,cache)>0,"mixed GPU indirect submission");
                    compare(expected,pixels(),"GPU sorted batch");
                }
            }
            GL46C.glUseProgram(0); GL46C.glDeleteProgram(legacy); GL46C.glDeleteProgram(batch);
            check(GL46C.glGetError()==GL46C.GL_NO_ERROR,"mixed GL errors");
        } finally {
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER,oldFbo);
            GL46C.glBindVertexArray(0);
            GL46C.glDeleteBuffers(ebo); GL46C.glDeleteBuffers(positions); GL46C.glDeleteVertexArrays(vao);
            GL46C.glDeleteFramebuffers(fbo); GL46C.glDeleteTextures(target); GL46C.glDeleteTextures(diffuse);
        }
        System.out.println("PASS mixed cross-pack Ray/color/off/inherit, CPU windows and GPU sort: "+checks+" checks");
        return checks;
    }

    private static int compile(EffectPackRuntime runtime, boolean batch) throws Exception {
        var output=ToonShaderBase.class.getDeclaredMethod("outputSource",String.class,ToonOutputProfile.class); output.setAccessible(true);
        String body=Files.readString(Path.of("common/src/main/resources/assets/mmdskin/shader/toon_main_body.frag.glsl"));
        String fragment=(String)output.invoke(null,FxShaderSource.apply(body,runtime.program()),ToonOutputProfile.vanilla());
        if(batch) { var source=TranslucentBatchShader.class.getDeclaredMethod("source",String.class); source.setAccessible(true); fragment=(String)source.invoke(null,fragment); }
        int program=ShaderCompiler.compileRenderProgram(VERTEX,fragment,"mixed FX "+batch);
        check(program!=0,"mixed shader compile "+batch); return program;
    }
    private static void setup(int program,int vao,int diffuse) {
        GL46C.glUseProgram(program); GL46C.glBindVertexArray(vao); GL46C.glViewport(0,0,SIZE,SIZE);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,diffuse);
        GL46C.glUniform1i(GL46C.glGetUniformLocation(program,"Sampler0"),0);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(program,"LightIntensity"),0.8f);
        GL46C.glUniform1i(GL46C.glGetUniformLocation(program,"ToonLevels"),3);
        GL46C.glUniform3f(GL46C.glGetUniformLocation(program,"ShadowColor"),0.5f,0.5f,0.5f);
        GL46C.glUniform1i(GL46C.glGetUniformLocation(program,"AlphaPass"),2);
        GL46C.glDisable(GL46C.GL_DEPTH_TEST); GL46C.glDisable(GL46C.GL_CULL_FACE); GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        RenderSystem.enableBlend(); GL46C.glEnable(GL46C.GL_BLEND);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA,GL46C.GL_ONE_MINUS_SRC_ALPHA,GL46C.GL_ONE,GL46C.GL_ONE_MINUS_SRC_ALPHA);
        GL46C.glColorMask(true,true,true,true); GL46C.glDepthMask(false);
    }
    private static void clear() { GL46C.glClearColor(0.02f,0.03f,0.04f,0); GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT); }
    private static float[] pixels() { float[] p=new float[SIZE*SIZE*4]; GL46C.glReadPixels(0,0,SIZE,SIZE,GL46C.GL_RGBA,GL46C.GL_FLOAT,p); return p; }
    private static void compare(float[] a,float[] b,String name) {
        float max=0; for(int i=0;i<a.length;i++) { check(Float.isFinite(b[i]),name+" finite"); max=Math.max(max,Math.abs(a[i]-b[i])); }
        check(max<0.001f,name+" maximum pixel delta="+max); System.out.println(name+" max delta="+max);
    }
    private static ByteBuffer buffer(int n) { return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder()); }
    private static FloatBuffer identity() { FloatBuffer b=buffer(64).asFloatBuffer(); new Matrix4f().get(b); return b; }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); checks++; }
}
