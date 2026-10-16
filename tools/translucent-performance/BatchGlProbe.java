package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import com.shiroha.mmdskin.render.shader.TranslucentBatchShader;
import com.shiroha.mmdskin.texture.runtime.TextureAlphaCoverage;
import java.nio.*;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Real production Toon programs: compare sorted per-run draws against material-table submission. */
public class BatchGlProbe {
    static final int SIZE = 64, COUNT = 36;
    static ByteBuffer buffer(int n) { return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder()); }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void uniform(int p, String name, float value) { GL46C.glUniform1f(GL46C.glGetUniformLocation(p,name),value); }
    static void setup(ToonShaderCpu shader) {
        shader.useMain();
        FloatBuffer matrix = buffer(64).asFloatBuffer(); new Matrix4f().get(matrix);
        shader.setProjectionMatrix(matrix); shader.setModelViewMatrix(matrix);
        shader.setLightDirection(.2f,.3f,1); shader.setLightIntensity(.8f);
        shader.setToonLevels(3); shader.setTransitionSoftness(.5f);
        shader.setShadowColor(.4f,.5f,.6f); shader.setSpecular(64,.2f); shader.setRimLight(2,.1f);
        shader.setAlphaCutoff(.001f); shader.setSampler0(0); shader.setEyeEmission(.6f);
    }
    static int texture(int seed) {
        int id=GL46C.glGenTextures(); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,id);
        ByteBuffer pixels=buffer(8*8*4);
        for(int y=0;y<8;y++) for(int x=0;x<8;x++) {
            pixels.put((byte)(35+(seed*41+x*19)%200)).put((byte)(30+(seed*29+y*21)%200))
                    .put((byte)(40+(seed*17+x*y*3)%200)).put((byte)((x+y)%5==0?255:130));
        }
        pixels.flip();
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D,0,GL46C.GL_RGBA8,8,8,0,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,pixels);
        GL46C.glGenerateMipmap(GL46C.GL_TEXTURE_2D);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MIN_FILTER,GL46C.GL_LINEAR_MIPMAP_LINEAR);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MAG_FILTER,GL46C.GL_LINEAR);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_WRAP_S,GL46C.GL_REPEAT);
        return id;
    }
    static ByteBuffer render(ToonShaderCpu shader, boolean batch, TranslucentBatchSubmission submission,
                             ByteBuffer runs, ModelMaterial[] mats, ByteBuffer morphs, int[] draws) {
        setup(shader);
        GL46C.glClearColor(.08f,.12f,.18f,.2f); GL46C.glDepthMask(true);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT|GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glDepthMask(false);
        if(batch) {
            check(TranslucentBatchShader.current()!=null,"production batch program eligibility");
            draws[0]=submission.draw(runs,COUNT,mats,morphs,i->mats[i].tex,(i,a)->a);
            check(draws[0]>0 && draws[0]<COUNT,"batch reduced actual submissions");
        } else {
            MaterialRenderState state=new MaterialRenderState();
            try(MaterialShaderBinding binding=new MaterialShaderBinding(); SphereTextureBinding sphere=new SphereTextureBinding(false)) {
                for(int i=0;i<COUNT;i++) {
                    int b=i*20,id=runs.getInt(b); var material=mats[id];
                    state.resolve(material,morphs,id,runs.getFloat(b+12)); binding.apply(state,2); sphere.apply(material,state);
                    shader.setEyeMaterialMask(material.eyeEmissionMaskMode());
                    if(runs.get(b+17)!=0) GL46C.glDisable(GL46C.GL_CULL_FACE); else GL46C.glEnable(GL46C.GL_CULL_FACE);
                    GL46C.glActiveTexture(GL46C.GL_TEXTURE0); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,material.tex);
                    GL46C.glDrawElements(GL46C.GL_TRIANGLES,runs.getInt(b+8),GL46C.GL_UNSIGNED_INT,(long)runs.getInt(b+4)*4);
                }
            }
        }
        ByteBuffer pixels=buffer(SIZE*SIZE*4);
        GL46C.glReadPixels(0,0,SIZE,SIZE,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,pixels);
        check(GL46C.glGetError()==GL46C.GL_NO_ERROR,"GL error after render");
        return pixels;
    }
    public static void main(String[] args) {
        check(GLFW.glfwInit(),"GLFW"); GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4); GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
        long window=GLFW.glfwCreateWindow(SIZE,SIZE,"batch probe",0,0); check(window!=0,"GL window");
        try { GLFW.glfwMakeContextCurrent(window); GL.createCapabilities(); run(); }
        finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }
    static void run() {
        RenderSystem.initRenderThread();
        System.setProperty("mmdskin.translucentBatch","false"); var legacy=new ToonShaderCpu(); check(legacy.init(),"legacy shader");
        System.clearProperty("mmdskin.translucentBatch"); var batch=new ToonShaderCpu(); check(batch.init(),"batch shader");
        int vao=GL46C.glGenVertexArrays(); GL46C.glBindVertexArray(vao);
        // Two windings share all vertex data. Every triangle has a different material owner.
        ByteBuffer vertices=buffer(3*9*4);
        float[][] positions={{-.9f,-.8f},{.9f,-.8f},{0,.9f}};
        for(int i=0;i<3;i++) vertices.putFloat(positions[i][0]).putFloat(positions[i][1]).putFloat(-.2f)
                .putFloat(.2f).putFloat(.1f).putFloat(1).putFloat(i==1?3:0).putFloat(i==2?3:0).putFloat(.4f);
        vertices.flip();
        int vbo=GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,vbo);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,vertices,GL46C.GL_STATIC_DRAW);
        for(int i=0;i<4;i++) { GL46C.glEnableVertexAttribArray(i); GL46C.glVertexAttribPointer(i,i<2?3:i==2?2:1,GL46C.GL_FLOAT,false,36,i==0?0:i==1?12:i==2?24:32); }
        ByteBuffer indices=buffer(COUNT*12),runs=buffer(COUNT*20);
        ModelMaterial[] mats=new ModelMaterial[10]; int[] textures=new int[12];
        for(int i=0;i<textures.length;i++) textures[i]=texture(i);
        ByteBuffer morphs=buffer(mats.length*56*4);
        for(int i=0;i<mats.length;i++) {
            mats[i]=new ModelMaterial(); mats[i].tex=textures[i]; mats[i].name=i%3==0?"eye":i%3==1?"eyelash":"skin";
            mats[i].hasAlpha=true; mats[i].hasTranslucentPixels=true;
            mats[i].diffuse=new float[]{.7f,.85f,1,1}; mats[i].specular=new float[]{.2f,.1f,.3f}; mats[i].specularPower=40;
            if(i%3!=0) { mats[i].sphereTex=textures[10+i%2]; mats[i].sphereMode=1+i%2; }
            for(int j=0;j<28;j++) morphs.putFloat((i*56+j)*4,1);
            morphs.putFloat((i*56+16)*4,.8f).putFloat((i*56+20)*4,.7f).putFloat((i*56+48)*4,.03f);
        }
        for(int i=0;i<COUNT;i++) {
            indices.putInt(0).putInt(i%2==0?1:2).putInt(i%2==0?2:1);
            int b=i*20; runs.putInt(b,i%10).putInt(b+4,i*3).putInt(b+8,3).putFloat(b+12,i%4==0?1:.45f)
                    .put(b+16,(byte)1).put(b+17,(byte)(i%3==0?1:0));
        }
        indices.flip(); int ebo=GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,ebo);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER,indices,GL46C.GL_STATIC_DRAW);
        GL46C.glViewport(0,0,SIZE,SIZE); GL46C.glEnable(GL46C.GL_BLEND);
        GL46C.glBlendFuncSeparate(GL46C.GL_SRC_ALPHA,GL46C.GL_ONE_MINUS_SRC_ALPHA,GL46C.GL_ONE,GL46C.GL_ONE_MINUS_SRC_ALPHA);
        GL46C.glEnable(GL46C.GL_DEPTH_TEST); GL46C.glDepthFunc(GL46C.GL_LEQUAL);
        int[] draws={0};
        try(var submission=new TranslucentBatchSubmission()) {
            for(int cullMode:new int[]{GL46C.GL_BACK,GL46C.GL_FRONT,GL46C.GL_FRONT_AND_BACK}) {
                for(int winding:new int[]{GL46C.GL_CCW,GL46C.GL_CW}) {
                    GL46C.glCullFace(cullMode); GL46C.glFrontFace(winding);
                    var reference=render(legacy,false,submission,runs,mats,morphs,draws);
                    GL46C.glEnable(GL46C.GL_CULL_FACE);
                    var actual=render(batch,true,submission,runs,mats,morphs,draws);
                    int max=0,changed=0; for(int i=0;i<actual.capacity();i++) {int diff=Math.abs((reference.get(i)&255)-(actual.get(i)&255)); max=Math.max(max,diff);if(diff>1)changed++;}
                    check(max<=1,"pixel difference max="+max+" channels="+changed+" cull="+cullMode+" winding="+winding);
                    check(GL46C.glIsEnabled(GL46C.GL_CULL_FACE),"cull restored");
                    check(GL46C.glGetUniformi(batch.getMainProgram(),GL46C.glGetUniformLocation(batch.getMainProgram(),"MmdBatchEnabled"))==0,"batch disabled after draw");
                }
            }
            // Preserve host SSBO ranges, texture/sampler bindings, and active unit.
            int host=GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER,host);
            int alignment=GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
            GL46C.glBufferData(GL46C.GL_SHADER_STORAGE_BUFFER,alignment+128,GL46C.GL_STATIC_DRAW);
            GL46C.glBindBufferRange(GL46C.GL_SHADER_STORAGE_BUFFER,2,host,alignment,64);
            GL46C.glBindBufferRange(GL46C.GL_SHADER_STORAGE_BUFFER,3,host,alignment,128);
            int sampler=GL46C.glGenSamplers(); GL46C.glBindSampler(4,sampler);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE4); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,textures[2]);
            setup(batch); submission.draw(runs,COUNT,mats,morphs,i->mats[i].tex,(i,a)->a);
            check(GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE)==GL46C.GL_TEXTURE4,"active texture restored");
            check(GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D)==textures[2],"texture restored");
            check(GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING,4)==sampler,"sampler restored");
            check(GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_START,2)==alignment,"SSBO offset restored");
            check(GL46C.glGetInteger64i(GL46C.GL_SHADER_STORAGE_BUFFER_SIZE,3)==128,"SSBO size restored");
            check(GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING)==host,"generic SSBO restored");
            GL46C.glBindSampler(0,sampler);
            check(submission.draw(runs,COUNT,mats,morphs,i->mats[i].tex,(i,a)->a)==-1,"custom sampler fallback");
            GL46C.glBindSampler(0,0); GL46C.glBindSampler(4,0); GL46C.glDeleteSamplers(sampler); GL46C.glDeleteBuffers(host);
            legacy.useMain(); check(submission.draw(runs,COUNT,mats,morphs,i->mats[i].tex,(i,a)->a)==-1,"legacy program fallback");
            setup(batch); System.setProperty("mmdskin.translucentBatch","false");
            check(submission.draw(runs,COUNT,mats,morphs,i->mats[i].tex,(i,a)->a)==-1,"runtime compatibility switch");
            System.clearProperty("mmdskin.translucentBatch");
            submission.close(); submission.close(); check(submission.ramBytes()==0&&submission.vramBytes()==0,"idempotent cleanup");
        }
        // Full production entry, including opaque pixels, sorted pass and depth seal.
        ByteBuffer positionsOnly=buffer(36),uvs=buffer(24);
        for(int i=0;i<3;i++) {
            positionsOnly.putFloat(positions[i][0]).putFloat(positions[i][1]).putFloat(-.2f);
            uvs.putFloat(i==1?3:0).putFloat(i==2?3:0);
        }
        FloatBuffer view=buffer(64).asFloatBuffer(); new Matrix4f().get(view);
        // Six texture objects fit in the GPU preparation path's one texture window.
        for(int i=0;i<mats.length;i++) { mats[i].tex=textures[i%4]; if(mats[i].sphereTex>0)mats[i].sphereTex=textures[4+i%2]; }
        int positionSsbo=GL46C.glGenBuffers(); GL46C.glBindBuffer(GL46C.GL_COPY_WRITE_BUFFER,positionSsbo);
        positionsOnly.flip(); GL46C.glBufferData(GL46C.GL_COPY_WRITE_BUFFER,positionsOnly,GL46C.GL_STATIC_DRAW);
        ByteBuffer[] images=new ByteBuffer[3]; FloatBuffer[] depths=new FloatBuffer[3];
        GL46C.glCullFace(GL46C.GL_BACK); GL46C.glFrontFace(GL46C.GL_CCW);
        // Earlier checks deliberately use raw GL. Resynchronize the Blaze3D caches before
        // exercising production code, whose state changes go through RenderSystem.
        RenderSystem.activeTexture(GL46C.GL_TEXTURE4); RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,0); RenderSystem.bindTexture(0);
        RenderSystem.disableCull(); RenderSystem.enableCull();
        RenderSystem.depthMask(false); RenderSystem.depthMask(true);
        for(int path=0;path<3;path++) {
            var shader=path==0?legacy:batch; setup(shader);
            RenderSystem.enableCull(); RenderSystem.depthMask(true);
            GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT|GL46C.GL_DEPTH_BUFFER_BIT);
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,ebo);
            try(var order=new TranslucentDrawOrder()) {
                var geometry=path==2
                        ? TranslucentDrawOrder.Inputs.gpu(positionSsbo,3,view,null,1,false,uvs,0,0,false)
                        : TranslucentDrawOrder.Inputs.cpu(positionsOnly,0,3,view,null,1,false,uvs,0,false);
                SubMeshDrawHelper.draw(runs,COUNT,4,GL46C.GL_UNSIGNED_INT,i->mats[i].tex,(i,a)->a,
                        mats,morphs,false,i->shader.setEyeMaterialMask(mats[i].eyeEmissionMaskMode()),order,geometry);
                check(GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING)==ebo,"production EBO restored");
                if(path==2)check(order.drawCount()==0 && order.indexBuffer()!=0,"production uses GPU indirect preparation");
            }
            images[path]=buffer(SIZE*SIZE*4); depths[path]=buffer(SIZE*SIZE*4).asFloatBuffer();
            GL46C.glReadPixels(0,0,SIZE,SIZE,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,images[path]);
            GL46C.glReadPixels(0,0,SIZE,SIZE,GL46C.GL_DEPTH_COMPONENT,GL46C.GL_FLOAT,depths[path]);
        }
        int productionMax=0;
        for(int path=1;path<3;path++)for(int i=0;i<images[0].capacity();i++) productionMax=Math.max(productionMax,Math.abs((images[0].get(i)&255)-(images[path].get(i)&255)));
        check(productionMax<=1,"production color mismatch max="+productionMax);
        for(int path=1;path<3;path++)for(int i=0;i<depths[0].capacity();i++) check(depths[0].get(i)==depths[path].get(i),"production depth mismatch");
        filteredWindow(legacy,batch,ebo,positionSsbo,positionsOnly,view,textures);
        check(GL46C.glGetError()==GL46C.GL_NO_ERROR,"final GL error");
        legacy.cleanup(); batch.cleanup(); GL46C.glDeleteBuffers(vbo); GL46C.glDeleteBuffers(ebo); GL46C.glDeleteBuffers(positionSsbo);
        GL46C.glDeleteVertexArrays(vao); GL46C.glDeleteTextures(textures);
        System.out.println("BatchGlProbe PASS: 36 runs -> "+draws[0]+" draws; mixed materials, shared vertices, sphere/morph/eyes, texture overflow, culling, fallback, state, cleanup");
    }

    static void filteredWindow(ToonShaderCpu legacy, ToonShaderCpu batch, int ebo, int positionSsbo,
                               ByteBuffer positions, FloatBuffer view, int[] textures) {
        ModelMaterial[] mats=new ModelMaterial[10]; ByteBuffer runs=buffer(10*20),uvs=buffer(24);
        ByteBuffer opaque=buffer(8*8*4),partial=buffer(8*8*4);
        for(int i=0;i<64;i++){opaque.put(i*4+3,(byte)255);partial.put(i*4+3,(byte)130);}
        for(int i=0;i<10;i++) {
            var mat=mats[i]=new ModelMaterial(); mat.tex=textures[i]; mat.hasAlpha=true; mat.hasTranslucentPixels=true;
            // Nine atlases are opaque at this triangle; only one translucent candidate survives.
            mat.alphaCoverage=TextureAlphaCoverage.fromRgba(i==9?partial:opaque,8,8);
            RenderSystem.activeTexture(GL46C.GL_TEXTURE0); RenderSystem.bindTexture(mat.tex);
            GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D,0,0,0,8,8,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,i==9?partial:opaque);
            GL46C.glGenerateMipmap(GL46C.GL_TEXTURE_2D);
            int b=i*20; runs.putInt(b,i).putInt(b+4,i*3).putInt(b+8,3).putFloat(b+12,1)
                    .put(b+16,(byte)1).put(b+17,(byte)1);
        }
        for(int i=0;i<3;i++)uvs.putFloat(i*8,.3f).putFloat(i*8+4,.3f);
        ByteBuffer[] images=new ByteBuffer[2]; FloatBuffer[] depths=new FloatBuffer[2];
        for(int path=0;path<2;path++) {
            var shader=path==0?legacy:batch; setup(shader); RenderSystem.depthMask(true);
            GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT|GL46C.GL_DEPTH_BUFFER_BIT);
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,ebo);
            try(var order=new TranslucentDrawOrder()) {
                var input=(path==0?TranslucentDrawOrder.Inputs.cpu(positions,0,3,view,null,1,false,uvs,0,false)
                        :TranslucentDrawOrder.Inputs.gpu(positionSsbo,3,view,null,1,false,uvs,0,0,false));
                SubMeshDrawHelper.draw(runs,10,4,GL46C.GL_UNSIGNED_INT,i->mats[i].tex,(i,a)->a,
                        mats,null,false,i->shader.setEyeMaterialMask(0),order,input);
                check(order.candidateTriangleCount()==1,"coverage-filtered candidate count");
                if(path==1)check(order.drawCount()==0&&order.indexBuffer()!=0,"filtered ten-texture pass uses GPU preparation");
            }
            images[path]=buffer(SIZE*SIZE*4);depths[path]=buffer(SIZE*SIZE*4).asFloatBuffer();
            GL46C.glReadPixels(0,0,SIZE,SIZE,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,images[path]);
            GL46C.glReadPixels(0,0,SIZE,SIZE,GL46C.GL_DEPTH_COMPONENT,GL46C.GL_FLOAT,depths[path]);
        }
        for(int i=0;i<images[0].capacity();i++)check(Math.abs((images[0].get(i)&255)-(images[1].get(i)&255))<=1,"filtered window pixel oracle");
        for(int i=0;i<depths[0].capacity();i++)check(depths[0].get(i)==depths[1].get(i),"filtered window depth oracle");
        System.out.println("PASS ten raw textures filtered to one candidate window, CPU/GPU color and depth");
    }
}
