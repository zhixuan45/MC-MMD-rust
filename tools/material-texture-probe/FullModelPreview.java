package probe;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridge;
import com.shiroha.mmdskin.bridge.runtime.NativeTextureBridge;
import com.shiroha.mmdskin.render.material.MaterialTextureLoader;
import com.shiroha.mmdskin.render.material.ModelMaterial;
import com.shiroha.mmdskin.render.material.SubMeshDrawHelper;
import com.shiroha.mmdskin.render.shader.ToonShaderCpu;
import com.shiroha.mmdskin.texture.runtime.TextureRepository;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;

import static org.lwjgl.egl.EGL14.*;

/** Real full-PMX offscreen rendering with production textures/draw passes and 1.21.1 shaders.
 * No proprietary model assets are bundled. This does not launch Minecraft or a shader pack.
 */
public final class FullModelPreview {
    private static final int SIZE=1024;

    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("FullModelPreview <model.pmx> <output-directory>");
        EGL.getCapabilities();long display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            IntBuffer major=stack.mallocInt(1),minor=stack.mallocInt(1);
            require(eglInitialize(display,major,minor),"EGL init");
            EGL.createDisplayCapabilities(display,major.get(0),minor.get(0));
            require(eglBindAPI(EGL_OPENGL_API),"EGL API");
            PointerBuffer configs=stack.mallocPointer(1);IntBuffer count=stack.mallocInt(1);
            require(eglChooseConfig(display,stack.ints(EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,
                    EGL_RENDERABLE_TYPE,EGL_OPENGL_BIT,EGL_NONE),configs,count),"EGL config");
            long surface=eglCreatePbufferSurface(display,configs.get(0),stack.ints(EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE));
            long context=eglCreateContext(display,configs.get(0),EGL_NO_CONTEXT,stack.ints(EGL_NONE));
            require(eglMakeCurrent(display,surface,surface,context),"EGL current");
            GL.createCapabilities();RenderSystem.initRenderThread();
            System.out.println("Renderer: "+GL46C.glGetString(GL46C.GL_RENDERER));
            render(Path.of(args[0]),Path.of(args[1]));
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            eglDestroyContext(display,context);eglDestroySurface(display,surface);
        } finally {eglTerminate(display);}
    }

    private static void render(Path path,Path output) throws Exception {
        Files.createDirectories(output);
        NativeFunc nf=NativeFunc.GetInst();
        long model=nf.LoadModelPMX(path.toString(),path.getParent().toString(),1);
        require(model!=0,"native PMX load");nf.SetPhysicsEnabled(model,false);nf.UpdateModel(model,0);
        TextureRepository.Init();TextureRepository.configureRuntimeCollaborators(new NativeTextureBridge());
        ToonShaderCpu toon=new ToonShaderCpu();
        try(ShaderInstance vanilla=new ShaderInstance(resources(),"rendertype_entity_translucent",DefaultVertexFormat.NEW_ENTITY)) {
            int vertexCount=(int)nf.GetVertexCount(model),indexCount=(int)nf.GetIndexCount(model);
            int indexSize=(int)nf.GetIndexElementSize(model);
            int indexType=indexSize==2?GL46C.GL_UNSIGNED_SHORT:indexSize==1?GL46C.GL_UNSIGNED_BYTE:GL46C.GL_UNSIGNED_INT;
            ByteBuffer pos=copy(nf,nf.GetPoss(model),vertexCount*12);
            ByteBuffer normal=copy(nf,nf.GetNormals(model),vertexCount*12);
            ByteBuffer uv=copy(nf,nf.GetUVs(model),vertexCount*8);
            ByteBuffer originalIndices=copy(nf,nf.GetIndices(model),indexCount*indexSize);
            float minY=Float.POSITIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY,minZ=minY,maxZ=maxY;
            for(int i=0;i<vertexCount;i++) {
                float y=pos.getFloat(i*12+4),z=pos.getFloat(i*12+8);
                minY=Math.min(minY,y);maxY=Math.max(maxY,y);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);
            }
            float height=maxY-minY, centerY=minY+height*.865f, centerZ=(minZ+maxZ)*.5f;
            System.out.println("PMX vertices="+vertexCount+" indices="+indexCount+" y="+minY+".."+maxY+" z="+minZ+".."+maxZ);
            int positions=buffer(pos),normals=buffer(normal),uvs=buffer(uv);
            int indices=GL46C.glGenBuffers();
            int vao=GL46C.glGenVertexArrays();GL46C.glBindVertexArray(vao);
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,indices);
            GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER,originalIndices,GL46C.GL_STATIC_DRAW);
            ModelMaterial[] materials=new ModelMaterial[(int)nf.GetMaterialCount(model)];
            var keys=new ArrayList<String>();var port=new NativeRuntimeBridge();
            for(int i=0;i<materials.length;i++)materials[i]=MaterialTextureLoader.loadMaterial(port,model,i,keys);
            int count=(int)nf.GetSubMeshCount(model);
            ByteBuffer submeshes=ByteBuffer.allocateDirect(count*20).order(ByteOrder.nativeOrder());
            require(nf.BatchGetSubMeshData(model,submeshes,false)==count,"batch submesh count");
            int fbo=GL46C.glGenFramebuffers();GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER,fbo);
            int color=texture(SIZE,SIZE,null);GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER,GL46C.GL_COLOR_ATTACHMENT0,GL46C.GL_TEXTURE_2D,color,0);
            int depth=GL46C.glGenRenderbuffers();GL46C.glBindRenderbuffer(GL46C.GL_RENDERBUFFER,depth);
            GL46C.glRenderbufferStorage(GL46C.GL_RENDERBUFFER,GL46C.GL_DEPTH_COMPONENT24,SIZE,SIZE);
            GL46C.glFramebufferRenderbuffer(GL46C.GL_FRAMEBUFFER,GL46C.GL_DEPTH_ATTACHMENT,GL46C.GL_RENDERBUFFER,depth);
            require(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER)==GL46C.GL_FRAMEBUFFER_COMPLETE,"preview FBO");
            byte[] white=new byte[16*16*4];java.util.Arrays.fill(white,(byte)255);
            int overlay=texture(16,16,ByteBuffer.allocateDirect(white.length).put(white).flip());
            int lightmap=texture(16,16,ByteBuffer.allocateDirect(white.length).put(white).flip());
            require(toon.init(),"production Toon shader initialization");
            for(boolean isToon:new boolean[]{false,true})for(int angle:new int[]{0,35}) {
                // Front face is toward +Z in the imported runtime mesh.
                float rad=(float)Math.toRadians(angle);
                float distance=height*1.65f;
                Matrix4f view=new Matrix4f().lookAt((float)Math.sin(rad)*distance,centerY,
                        centerZ+(float)Math.cos(rad)*distance,0,centerY,centerZ,0,1,0);
                Matrix4f projection=new Matrix4f().perspective((float)Math.toRadians(12),1,height*.01f,height*5);
                if(Boolean.getBoolean("mmd.preview.sort")) {
                    ByteBuffer sorted=ByteBuffer.allocateDirect(originalIndices.capacity()).order(ByteOrder.nativeOrder());
                    sorted.put(originalIndices.duplicate()).clear();
                    for(int s=0;s<count;s++) {
                        int start=submeshes.getInt(s*20+4),n=submeshes.getInt(s*20+8);
                        Integer[] triangles=new Integer[n/3];float[] z=new float[n/3];
                        for(int t=0;t<triangles.length;t++) {
                            triangles[t]=t;
                            for(int v=0;v<3;v++) {
                                int p=(start+t*3+v)*indexSize;
                                int id=indexSize==4?originalIndices.getInt(p):indexSize==2?originalIndices.getShort(p)&65535:originalIndices.get(p)&255;
                                z[t]+=view.m02()*pos.getFloat(id*12)+view.m12()*pos.getFloat(id*12+4)+view.m22()*pos.getFloat(id*12+8)+view.m32();
                            }
                        }
                        java.util.Arrays.sort(triangles,java.util.Comparator.comparingDouble(t->z[t]));
                        for(int t=0;t<triangles.length;t++)for(int b=0;b<3*indexSize;b++)
                            sorted.put((start+t*3)*indexSize+b,originalIndices.get((start+triangles[t]*3)*indexSize+b));
                    }
                    GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER,indices);
                    GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER,sorted,GL46C.GL_STATIC_DRAW);
                }
                GL46C.glViewport(0,0,SIZE,SIZE);RenderSystem.depthMask(true);
                GL46C.glClearColor(.72f,.79f,.87f,1);GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT|GL46C.GL_DEPTH_BUFFER_BIT);
                RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL46C.GL_LEQUAL);
                RenderSystem.enableBlend();RenderSystem.blendFuncSeparate(770,771,1,771);
                RenderSystem.setShaderColor(1,1,1,1);
                // The native loader reverses PMX triangles for OpenGL's CCW front faces.
                GL46C.glFrontFace(GL46C.GL_CCW);GL46C.glCullFace(GL46C.GL_BACK);
                int program;
                if(isToon) {
                    RenderSystem.setShader(()->null);toon.useMain();program=toon.getMainProgram();
                    try(MemoryStack stack=MemoryStack.stackPush()) {
                        toon.setModelViewMatrix(view.get(stack.mallocFloat(16)));
                        toon.setProjectionMatrix(projection.get(stack.mallocFloat(16)));
                    }
                    toon.setSampler0(0);toon.setAlphaCutoff(.001f);toon.setLightIntensity(1);
                    toon.setToonLevels(4);toon.setTransitionSoftness(1.2f);toon.setRimLight(5.6f,.02f);
                    toon.setShadowColor(.78f,.84f,.94f);toon.setSpecular(96,.015f);toon.setLightDirection(-.3f,.6f,1);
                    GL46C.glUniform1f(GL46C.glGetUniformLocation(program,"EyeEmission"),0);
                } else {
                    RenderSystem.setShader(()->vanilla);program=vanilla.getId();
                    vanilla.MODEL_VIEW_MATRIX.set(view);vanilla.PROJECTION_MATRIX.set(projection);
                    vanilla.COLOR_MODULATOR.set(1f,1f,1f,1f);
                    vanilla.LIGHT0_DIRECTION.set(.2f,1f,-.7f);vanilla.LIGHT1_DIRECTION.set(-.2f,1f,.7f);
                    vanilla.FOG_START.set(10000f);vanilla.FOG_END.set(20000f);vanilla.FOG_COLOR.set(0f,0f,0f,0f);vanilla.FOG_SHAPE.set(0);
                    vanilla.setSampler("Sampler0",materials[0].tex);vanilla.setSampler("Sampler1",overlay);vanilla.setSampler("Sampler2",lightmap);
                    vanilla.apply();GL46C.glUseProgram(program);
                }
                GL46C.glBindVertexArray(vao);
                for(int i=0;i<8;i++)GL46C.glDisableVertexAttribArray(i);
                attribute(program,"Position",positions,3);attribute(program,"Normal",normals,3);attribute(program,"UV0",uvs,2);
                int loc=GL46C.glGetAttribLocation(program,"UV1");if(loc>=0)GL46C.glVertexAttribI2i(loc,0,10);
                loc=GL46C.glGetAttribLocation(program,"UV2");if(loc>=0)GL46C.glVertexAttribI2i(loc,240,240);
                loc=GL46C.glGetAttribLocation(program,"EyeMask");if(loc>=0)GL46C.glVertexAttrib1f(loc,0);
                SubMeshDrawHelper.draw(submeshes,count,indexSize,indexType,i->materials[i].tex,(i,a)->a,materials,null,false,i->{});
                GL46C.glFinish();int error=GL46C.glGetError();require(error==0,"preview GL error "+error);
                Path file=output.resolve((isToon?"toon-on":"toon-off")+"-"+(angle==0?"front":"side")+".png");
                save(file);System.out.println("Wrote "+file);
                if(!isToon)vanilla.clear();
            }
            MaterialTextureLoader.releaseOwnedTextures(materials);
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER,0);
            GL46C.glDeleteBuffers(new int[]{positions,normals,uvs,indices});GL46C.glDeleteVertexArrays(vao);
            GL46C.glDeleteTextures(new int[]{color,overlay,lightmap});GL46C.glDeleteRenderbuffers(depth);GL46C.glDeleteFramebuffers(fbo);
        } finally {toon.cleanup();TextureRepository.Cleanup();nf.DeleteModel(model);RenderSystem.setShader(()->null);}
    }

    private static ResourceProvider resources() {
        PackResources pack=(PackResources)Proxy.newProxyInstance(PackResources.class.getClassLoader(),new Class<?>[]{PackResources.class},
                (object,method,args)->method.getName().equals("packId")?"minecraft-preview":null);
        return location->Optional.of(new Resource(pack,()->{
            String name="assets/"+location.getNamespace()+"/"+location.getPath();
            var input=FullModelPreview.class.getClassLoader().getResourceAsStream(name);
            if(input==null)throw new java.io.FileNotFoundException(name);return input;
        }));
    }
    private static ByteBuffer copy(NativeFunc nf,long pointer,int bytes) {
        ByteBuffer result=ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());nf.CopyDataToByteBuffer(result,pointer,bytes);return result;
    }
    private static int buffer(ByteBuffer bytes) {
        int id=GL46C.glGenBuffers();GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,id);GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,bytes,GL46C.GL_STATIC_DRAW);return id;
    }
    private static void attribute(int program,String name,int buffer,int components) {
        int loc=GL46C.glGetAttribLocation(program,name);if(loc<0)return;
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,buffer);GL46C.glEnableVertexAttribArray(loc);GL46C.glVertexAttribPointer(loc,components,GL46C.GL_FLOAT,false,0,0);
    }
    private static int texture(int width,int height,ByteBuffer bytes) {
        int id=GL46C.glGenTextures();GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,id);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D,0,GL46C.GL_RGBA8,width,height,0,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,bytes);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MIN_FILTER,GL46C.GL_LINEAR);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MAG_FILTER,GL46C.GL_LINEAR);return id;
    }
    private static void save(Path file) throws Exception {
        ByteBuffer pixels=ByteBuffer.allocateDirect(SIZE*SIZE*4);GL46C.glReadPixels(0,0,SIZE,SIZE,GL46C.GL_RGBA,GL46C.GL_UNSIGNED_BYTE,pixels);
        BufferedImage image=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++) {
            int p=((SIZE-1-y)*SIZE+x)*4;
            image.setRGB(x,y,(pixels.get(p)&255)<<16|(pixels.get(p+1)&255)<<8|(pixels.get(p+2)&255));
        }
        ImageIO.write(image,"png",file.toFile());
    }
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
}
