package probe;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.shiroha.mmdskin.render.material.ModelMaterial;
import com.shiroha.mmdskin.render.material.SubMeshDrawHelper;
import com.shiroha.mmdskin.render.material.MaterialRenderState;
import com.shiroha.mmdskin.render.material.MaterialTextureLoader;
import com.shiroha.mmdskin.render.material.TranslucentDrawOrder;
import com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridge;
import com.shiroha.mmdskin.bridge.runtime.NativeTextureBridge;
import com.shiroha.mmdskin.texture.runtime.TextureRepository;
import com.shiroha.mmdskin.NativeFunc;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL46C;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/** 用 Minecraft 1.21.1 的完整实体 shader，验证共享贴图中的实心表面遮挡。 */
final class VanillaEntityProbe {
    static void run() throws Exception {
        PackResources pack=(PackResources)Proxy.newProxyInstance(PackResources.class.getClassLoader(),
                new Class<?>[]{PackResources.class},(object,method,args)->method.getName().equals("packId")?"minecraft-core-probe":null);
        ResourceProvider provider=location -> {
            String path="assets/"+location.getNamespace()+"/"+location.getPath();
            return Optional.of(new Resource(pack,()-> {
                var input=VanillaEntityProbe.class.getClassLoader().getResourceAsStream(path);
                if(input==null)throw new java.io.FileNotFoundException(path);
                return input;
            }));
        };
        ShaderInstance previous=RenderSystem.getShader();
        try (ShaderInstance shader=new ShaderInstance(provider,"rendertype_entity_translucent",DefaultVertexFormat.NEW_ENTITY)) {
            RenderSystem.setShader(()->shader);
            int vao=GL46C.glGenVertexArrays(); GL46C.glBindVertexArray(vao);
            int positions=GL46C.glGenBuffers(), uvs=GL46C.glGenBuffers(), indices=GL46C.glGenBuffers();
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,positions);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,new float[]{-1,-1,0, 3,-1,0, -1,3,0,
                    -1,-1,.5f, 3,-1,.5f, -1,3,.5f},GL46C.GL_STATIC_DRAW);
            int position=GL46C.glGetAttribLocation(shader.getId(),"Position");
            GL46C.glEnableVertexAttribArray(position); GL46C.glVertexAttribPointer(position,3,GL46C.GL_FLOAT,false,0,0);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,uvs);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,new float[]{.25f,.5f,.25f,.5f,.25f,.5f,
                    .75f,.5f,.75f,.5f,.75f,.5f},GL46C.GL_STATIC_DRAW);
            int uv=GL46C.glGetAttribLocation(shader.getId(),"UV0");
            GL46C.glEnableVertexAttribArray(uv); GL46C.glVertexAttribPointer(uv,2,GL46C.GL_FLOAT,false,0,0);
            int normal=GL46C.glGetAttribLocation(shader.getId(),"Normal");
            GL46C.glDisableVertexAttribArray(normal); GL46C.glVertexAttrib3f(normal,0,0,1);
            int uv1=GL46C.glGetAttribLocation(shader.getId(),"UV1"), uv2=GL46C.glGetAttribLocation(shader.getId(),"UV2");
            GL46C.glDisableVertexAttribArray(uv1); GL46C.glVertexAttribI2i(uv1,0,10);
            GL46C.glDisableVertexAttribArray(uv2); GL46C.glVertexAttribI2i(uv2,240,240);
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,indices);
            GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER,new int[]{0,1,2,3,4,5},GL46C.GL_STATIC_DRAW);
            int atlas=texture(2,1,new byte[]{(byte)255,0,0,(byte)255, 0,0,(byte)255,(byte)128});
            byte[] white=new byte[16*16*4]; java.util.Arrays.fill(white,(byte)255);
            int overlay=texture(16,16,white), lightmap=texture(16,16,white);
            shader.setSampler("Sampler0",atlas);shader.setSampler("Sampler1",overlay);shader.setSampler("Sampler2",lightmap);
            shader.MODEL_VIEW_MATRIX.set(new Matrix4f());shader.PROJECTION_MATRIX.set(new Matrix4f());
            shader.COLOR_MODULATOR.set(1f,1f,1f,1f);
            shader.LIGHT0_DIRECTION.set(0f,0f,1f);shader.LIGHT1_DIRECTION.set(0f,0f,1f);
            shader.FOG_START.set(1000f);shader.FOG_END.set(2000f);shader.FOG_COLOR.set(0f,0f,0f,0f);shader.FOG_SHAPE.set(0);
            shader.apply(); RenderSystem.enableBlend();RenderSystem.blendFuncSeparate(770,771,1,771);
            RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL46C.GL_LEQUAL);RenderSystem.depthMask(true);
            int fbo=GL46C.glGenFramebuffers();GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER,fbo);
            int output=texture(1,1,new byte[4]);
            GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER,GL46C.GL_COLOR_ATTACHMENT0,GL46C.GL_TEXTURE_2D,output,0);
            int depth=GL46C.glGenRenderbuffers();GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER,depth);
            GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER,GL46C.GL_DEPTH_COMPONENT24,1,1);
            GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER,GL46C.GL_DEPTH_ATTACHMENT,GL46C.GL_RENDERBUFFER,depth);
            GL46C.glViewport(0,0,1,1);GL46C.glClearColor(0,0,0,0);GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT|GL46C.GL_DEPTH_BUFFER_BIT);
            ByteBuffer submesh=ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());
            submesh.putInt(0).putInt(0).putInt(6).putFloat(1).put((byte)1).put((byte)1).putShort((short)0).flip();
            ModelMaterial material=new ModelMaterial();material.tex=atlas;material.hasAlpha=true;material.hasTranslucentPixels=true;
            SubMeshDrawHelper.draw(submesh,1,4,GL46C.GL_UNSIGNED_INT,i->atlas,(i,a)->a,new ModelMaterial[]{material},null,false,i->{});
            float[] pixel=new float[4];GL46C.glReadPixels(0,0,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,pixel);
            System.out.println("Vanilla mixed atlas front opaque red / rear translucent blue="+java.util.Arrays.toString(pixel));
            if(Math.abs(pixel[0]-1)>.01f || pixel[2]>.01f || Math.abs(pixel[3]-1)>.01f)
                throw new AssertionError("1.21 vanilla solid texel was overwritten by rear triangles: "+java.util.Arrays.toString(pixel));
            // 保留原本有意的材质透明度；不能靠强制 alpha=1 掩盖遮挡问题。
            GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT|GL46C.GL_DEPTH_BUFFER_BIT);
            submesh.putFloat(12,.5f);
            SubMeshDrawHelper.draw(submesh,1,4,GL46C.GL_UNSIGNED_INT,i->atlas,(i,a)->a,new ModelMaterial[]{material},null,false,i->{});
            GL46C.glReadPixels(0,0,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,pixel);
            expect(pixel[3],.625f,"intentional material alpha still multiplies texture alpha");
            GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT|GL46C.GL_DEPTH_BUFFER_BIT);
            submesh.putFloat(12,.05f);
            SubMeshDrawHelper.draw(submesh,1,4,GL46C.GL_UNSIGNED_INT,i->atlas,(i,a)->a,new ModelMaterial[]{material},null,false,i->{});
            GL46C.glReadPixels(0,0,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,pixel);
            expect(pixel[3],.05f+128/255f*.05f*.95f,"authored material alpha below .1 remains visible");
            if(pixel[3]<.06f)throw new AssertionError("low material alpha discarded");
            submesh.putFloat(12,1);
            // The solid pass must not write depth for a fractional texel. Otherwise
            // a translucent front triangle hides the opaque surface behind it.
            int inverseAtlas=texture(2,1,new byte[]{(byte)255,0,0,(byte)128, 0,0,(byte)255,(byte)255});
            material.tex=inverseAtlas;shader.setSampler("Sampler0",inverseAtlas);shader.apply();
            GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT|GL46C.GL_DEPTH_BUFFER_BIT);
            SubMeshDrawHelper.draw(submesh,1,4,GL46C.GL_UNSIGNED_INT,i->inverseAtlas,(i,a)->a,new ModelMaterial[]{material},null,false,i->{});
            GL46C.glReadPixels(0,0,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,pixel);
            expect(pixel[0],128/255f,"fractional front texel blends once");
            expect(pixel[2],127/255f,"opaque rear texel remains visible through fractional front");
            expect(pixel[3],1,"opaque rear provides full coverage");
            System.out.println("PASS full vanilla mixed atlas front translucent / rear opaque="+java.util.Arrays.toString(pixel));
            GL46C.glDeleteTextures(inverseAtlas);
            int softAtlas=texture(2,1,new byte[]{(byte)255,0,0,(byte)5, 0,0,(byte)255,(byte)255});
            material.tex=softAtlas;shader.setSampler("Sampler0",softAtlas);shader.apply();
            GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT|GL46C.GL_DEPTH_BUFFER_BIT);
            SubMeshDrawHelper.draw(submesh,1,4,GL46C.GL_UNSIGNED_INT,i->softAtlas,(i,a)->a,new ModelMaterial[]{material},null,false,i->{});
            GL46C.glReadPixels(0,0,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,pixel);
            if(Math.abs(pixel[0]-5/255f)>.004f)throw new AssertionError("low texture alpha discarded: "+pixel[0]);
            expect(pixel[2],250/255f,"soft front texel blends with rear opaque surface");
            GL46C.glDeleteTextures(softAtlas);
            System.out.println("PASS authored low material/texture alpha survives vanilla entity cutoff");
            checkNativeMaterials(shader,uvs,submesh);
            checkTriangleOrder(shader,positions,uvs);
            if(GL46C.glGetError()!=GL46C.GL_NO_ERROR)throw new AssertionError("full vanilla probe GL error");
            shader.clear();GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER,0);
            GL46C.glDeleteTextures(new int[]{atlas,overlay,lightmap,output});GL46C.glDeleteRenderbuffers(depth);
            GL46C.glDeleteFramebuffers(fbo);GL46C.glDeleteBuffers(new int[]{positions,uvs,indices});GL46C.glDeleteVertexArrays(vao);
        } finally { RenderSystem.setShader(()->previous); }
        System.out.println("PASS full Minecraft 1.21.1 vanilla entity shader opaque texels keep their occlusion");
    }

    private static void checkNativeMaterials(ShaderInstance shader,int uvs,ByteBuffer submesh) throws Exception {
        String modelPath=System.getProperty("mmd.probe.model");
        if(modelPath==null)return;
        NativeFunc nf=NativeFunc.GetInst();
        java.nio.file.Path path=java.nio.file.Path.of(modelPath);
        long model=nf.LoadModelPMX(path.toString(),path.getParent().toString(),1);
        if(model==0)throw new AssertionError("native model load failed");
        nf.SetPhysicsEnabled(model,false);nf.UpdateModel(model,0);
        NativeRuntimeBridge port=new NativeRuntimeBridge();NativeTextureBridge textures=new NativeTextureBridge();
        TextureRepository.Init();TextureRepository.configureRuntimeCollaborators(textures);
        var keys=new java.util.ArrayList<String>();int checked=0;
        try {
            for(int i=0;i<nf.GetMaterialCount(model);i++) {
                String name=nf.GetMaterialName(model,i);
                if(!java.util.Set.of("顔","Skin","hair00","hair01").contains(name))continue;
                if(nf.GetMaterialAlpha(model,i)<.999f)throw new AssertionError(name+" should be opaque in PMX");
                String texturePath=port.getMaterialTexturePath(model,i);
                long handle=textures.loadTexture(texturePath);
                if(handle==0)throw new AssertionError("texture decode failed: "+texturePath);
                int width=textures.textureWidth(handle),height=textures.textureHeight(handle);
                ByteBuffer pixels=ByteBuffer.allocateDirect(width*height*4);
                textures.copyTextureData(pixels,textures.textureData(handle),pixels.capacity());
                textures.deleteTexture(handle);
                int solid=-1,transparent=-1;
                for(int p=0;p<width*height;p++) {
                    int alpha=pixels.get(p*4+3)&255;
                    if(alpha==255&&solid<0)solid=p;
                    if(alpha>25&&alpha<230&&transparent<0)transparent=p;
                }
                if(solid<0||transparent<0)throw new AssertionError(name+" needs mixed texture pixels");
                ModelMaterial material=MaterialTextureLoader.loadMaterial(port,model,i,keys);
                if(!material.hasTranslucentPixels)throw new AssertionError("native texture classification mismatch");
                GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,uvs);
                float sx=((solid%width)+.5f)/width,sy=((solid/width)+.5f)/height;
                float tx=((transparent%width)+.5f)/width,ty=((transparent/width)+.5f)/height;
                GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,new float[]{sx,sy,sx,sy,sx,sy,tx,ty,tx,ty,tx,ty},GL46C.GL_STATIC_DRAW);
                shader.setSampler("Sampler0",material.tex);shader.apply();RenderSystem.enableBlend();RenderSystem.blendFuncSeparate(770,771,1,771);
                GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT|GL46C.GL_DEPTH_BUFFER_BIT);
                SubMeshDrawHelper.draw(submesh,1,4,GL46C.GL_UNSIGNED_INT,id->material.tex,(id,a)->a,new ModelMaterial[]{material},null,false,id->{});
                float[] output=new float[4];GL46C.glReadPixels(0,0,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,output);
                MaterialRenderState state=new MaterialRenderState();state.resolve(material,null,0,1);
                for(int c=0;c<3;c++)expect(output[c],(pixels.get(solid*4+c)&255)/255f*state.color[c],name+" native opaque texel occlusion");
                expect(output[3],1,name+" native opaque alpha");
                checked++;
            }
            if(checked!=4)throw new AssertionError("expected 4 native material cases, got "+checked);
            System.out.println("PASS original PMX Face/Skin/hair00/hair01 textures, native material alpha=1, full 1.21 entity shader");
        } finally { TextureRepository.Cleanup();nf.DeleteModel(model); }
    }

    private static void expect(float actual,float expected,String name) {
        if(Math.abs(actual-expected)>.012f)throw new AssertionError(name+": "+actual+" != "+expected);
    }

    private static void checkTriangleOrder(ShaderInstance shader,int positions,int uvs) {
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,positions);
        float[] frontRed={-1,-1,0,3,-1,0,-1,3,0,-1,-1,-.5f,3,-1,-.5f,-1,3,-.5f};
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,frontRed,GL46C.GL_STATIC_DRAW);
        shader.PROJECTION_MATRIX.set(new Matrix4f().scaling(1,1,-1));
        int atlas=texture(2,1,new byte[]{(byte)255,0,0,(byte)128,0,0,(byte)255,(byte)128});
        int red=texture(1,1,new byte[]{(byte)255,0,0,(byte)255});
        int blue=texture(1,1,new byte[]{0,0,(byte)255,(byte)128});
        int originalIndices=GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        int oldRead=GL46C.glGetInteger(GL46C.GL_COPY_READ_BUFFER_BINDING);
        ByteBuffer views=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        new Matrix4f().get(views.asFloatBuffer());
        ByteBuffer draws=ByteBuffer.allocateDirect(40).order(ByteOrder.nativeOrder());
        draws.putInt(0).putInt(0).putInt(6).putFloat(1).put((byte)1).put((byte)1).putShort((short)0);
        ModelMaterial[] materials={new ModelMaterial(),new ModelMaterial()};
        for(var material:materials) {material.hasAlpha=true;material.hasTranslucentPixels=true;}
        materials[0].tex=atlas;
        try(TranslucentDrawOrder order=new TranslucentDrawOrder()) {
            for(int mode=0;mode<6;mode++) {
                if(mode==0) {
                    GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,uvs);
                    GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,new float[]{.25f,.5f,.25f,.5f,.25f,.5f,.75f,.5f,.75f,.5f,.75f,.5f},GL46C.GL_STATIC_DRAW);
                    materials[0].tex=atlas;shader.setSampler("Sampler0",atlas);
                } else if(mode==1) {
                    // Distinct materials and a uniform alpha combine with texture alpha.
                    materials[0].tex=red;materials[1].tex=blue;
                    draws.putInt(8,3).putFloat(12,.5f);
                    draws.position(20).putInt(1).putInt(3).putInt(3).putFloat(1).put((byte)1).put((byte)1).putShort((short)0);
                    shader.setSampler("Sampler0",red);
                } else if(mode==2) {
                    // Read new GPU-buffer geometry at a new revision; blue is now in front.
                    float[] frontBlue=frontRed.clone();
                    for(int v=0;v<6;v++)frontBlue[v*3+2]=v<3?-.5f:0;
                    GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,positions);
                    GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,frontBlue,GL46C.GL_STATIC_DRAW);
                } else if(mode==3) {
                    GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,positions);
                    GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,frontRed,GL46C.GL_STATIC_DRAW);
                }
                int indexSize=mode==3?2:mode>=4?1:4;
                int indexType=mode==3?GL46C.GL_UNSIGNED_SHORT:mode>=4?GL46C.GL_UNSIGNED_BYTE:GL46C.GL_UNSIGNED_INT;
                if(mode>=3) {
                    ByteBuffer data=ByteBuffer.allocateDirect(6*indexSize).order(ByteOrder.nativeOrder());
                    for(int v=0;v<6;v++) {
                        int id=mode==5?(v+3)%6:v;
                        if(indexSize==2)data.putShort((short)id);else data.put((byte)id);
                    }
                    data.flip();GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,originalIndices);
                    GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER,data,GL46C.GL_STATIC_DRAW);
                }
                shader.apply();RenderSystem.enableBlend();RenderSystem.blendFuncSeparate(770,771,1,771);
                GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT|GL46C.GL_DEPTH_BUFFER_BIT);
                // GPU inputs deliberately have no CPU position/UV mirror.
                SubMeshDrawHelper.draw(draws,mode==0?1:2,indexSize,indexType,i->materials[i].tex,(i,a)->a,
                        materials,null,false,i->{},order,
                        new TranslucentDrawOrder.Inputs(null,positions,6,views.asFloatBuffer(),null,mode>=3?2:mode==2?1:0,mode==5,
                                null,uvs,0));
                float[] pixel=new float[4];GL46C.glReadPixels(0,0,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,pixel);
                float a=128/255f;
                boolean blueFront=mode==2 || mode==5;
                expect(pixel[0],mode==0?a:blueFront?.5f*(1-a):.5f,"sorted red contribution mode="+mode);
                expect(pixel[2],mode==0?a*(1-a):blueFront?a:a*.5f,"sorted blue contribution mode="+mode);
                expect(pixel[3],mode==0?1-(1-a)*(1-a):.5f+a*.5f,"sorted original alpha mode="+mode);
                if(GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING)!=originalIndices
                        || GL46C.glGetInteger(GL46C.GL_COPY_READ_BUFFER_BINDING)!=oldRead
                        || GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM)!=shader.getId())
                    throw new AssertionError("sorted draw leaked GL bindings");
                System.out.println("PASS sorted translucent triangle mode="+mode+" "+java.util.Arrays.toString(pixel));
            }
        } finally {GL46C.glDeleteTextures(new int[]{atlas,red,blue});}
    }

    private static int texture(int w,int h,byte[] pixels) {
        int t=GL46C.glGenTextures(); GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,t);
        ByteBuffer data=ByteBuffer.allocateDirect(pixels.length).put(pixels);data.flip();
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D,0,GL46C.GL_RGBA8,w,h,0,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,data);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MIN_FILTER,GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MAG_FILTER,GL46C.GL_NEAREST);return t;
    }
}
