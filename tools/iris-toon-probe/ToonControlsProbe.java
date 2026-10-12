package com.shiroha.mmdskin.render.shader;

import org.lwjgl.opengl.GL46C;

import java.util.ArrayList;
import java.util.List;

/** 回读生产主体程序，验证色阶平坦区及各项风格参数。 */
public final class ToonControlsProbe {
    public static void run(ToonShaderCpu shader, int source, Object identity, float[] light) {
        int vao = GL46C.glGenVertexArrays();
        GL46C.glBindVertexArray(vao);
        int positions = buffer(0, 3, new float[]{-1,-1,-.5f, 3,-1,-.5f, -1,3,-.5f});
        int normals = buffer(1, 3, new float[9]);
        int uvs = buffer(2, 2, new float[6]);
        int albedo = texture(0, 1, new float[]{.4f,.5f,.7f,.8f});
        int output = texture(1, 16, null);
        int fbo = GL46C.glGenFramebuffers();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, output, 0);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "参数探针 FBO");
        try {
            shader.useMain();
            float[] matrix = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(shader.getMainProgram(), "ProjMat"), false, matrix);
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(shader.getMainProgram(), "ModelViewMat"), false, matrix);
            shader.setLightDirection(light[0], light[1], light[2]);
            shader.setLightIntensity(.7f);
            shader.setSampler0(0);
            shader.setGlobalAlpha(.5f);
            shader.setAlphaCutoff(.1f);
            shader.setEyeEmission(0);
            shader.setEyeMaterialMask(0);
            shader.setTransitionSoftness(0);
            shader.setShadowColor(.6f,.7f,.8f);
            shader.setSpecular(32, 0);
            shader.setRimLight(3, 0);
            boolean copied = shader.setEnvironmentLighting(source, identity, 0, 15);
            check(copied == (source > 0), "参数探针必须使用指定的光照路径");
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, albedo);
            GL46C.glViewport(0,0,16,16);
            GL46C.glDisable(GL46C.GL_BLEND);
            GL46C.glDisable(GL46C.GL_DEPTH_TEST);
            GL46C.glDisable(GL46C.GL_CULL_FACE);
            // 离开阈值的相邻法线必须得到同一色块；档数必须等于设置值。
            for (int levels = 2; levels <= 5; levels++) {
                shader.setToonLevels(levels);
                List<float[]> colors = new ArrayList<>();
                for (int i = 0; i < levels; i++) {
                    float d = i / (float)(levels - 1);
                    float[] a = sample(normals, withDot(light, Math.max(.025f, d - .03f)));
                    float[] b = sample(normals, withDot(light, Math.min(.99f, d + .03f)));
                    check(distance(a,b) < .0002f, "色块内部不能恢复连续法线渐变: " + levels + "/" + i);
                    check(colors.stream().noneMatch(c -> distance(a,c) < .001f), "色阶重复: " + levels + "/" + i);
                    colors.add(a);
                }
                System.out.println("PASS toon plateaus: path=" + (source > 0 ? "Complementary" : "vanilla") + " levels=" + colors.size());
            }

            checkTransitions(shader, normals, light);
            shader.setToonLevels(3);
            float[] darkNormal = withDot(light, .1f);
            float[] baseline = sample(normals, darkNormal);
            shader.setShadowColor(.2f,.9f,.3f);
            changed(baseline, sample(normals,darkNormal), "阴影 RGB");
            shader.setShadowColor(.6f,.7f,.8f);

            float[] view = normalize(new float[]{-.0625f,-.0625f,.5f});
            float[] half = normalize(new float[]{light[0]+view[0],light[1]+view[1],light[2]+view[2]});
            shader.setSpecular(32,0);
            float[] noSpec = sample(normals,half);
            shader.setSpecular(32,.7f);
            changed(noSpec,sample(normals,half),"高光强度");
            float[] broadNormal = normalize(new float[]{half[0]+.2f,half[1],half[2]});
            shader.setSpecular(8,.7f);
            float[] broad = sample(normals,broadNormal);
            shader.setSpecular(128,.7f);
            changed(broad,sample(normals,broadNormal),"高光幂次");
            shader.setSpecular(32,0);

            float[] rimNormal = normalize(new float[]{1,0,.4f});
            shader.setRimLight(1,0);
            float[] noRim = sample(normals,rimNormal);
            shader.setRimLight(1,.6f);
            float[] rim = sample(normals,rimNormal);
            changed(noRim,rim,"边缘光强度");
            shader.setRimLight(10,.6f);
            changed(rim,sample(normals,rimNormal),"边缘光幂次");

            shader.setSpecular(8,1);
            shader.setRimLight(1,1);
            shader.setLightIntensity(0);
            shader.setEnvironmentLighting(source,identity,0,0);
            float[] unlit = sample(normals,half);
            check(Math.max(unlit[0],Math.max(unlit[1],unlit[2])) < .01f, "黑暗处不能靠高光变成白色");
            check(Math.abs(unlit[3] - .4f) < .0001f, "纹理和全局 alpha 各乘一次");
            int eyeMask = GL46C.glGetAttribLocation(shader.getMainProgram(), "EyeMask");
            check(eyeMask >= 0, "主体必须接入眼瞳顶点遮罩");
            GL46C.glDisableVertexAttribArray(eyeMask);
            GL46C.glVertexAttrib1f(eyeMask, 1);
            shader.setEyeEmission(.5f);
            float[] glow = sample(normals,half);
            changed(unlit, glow, "眼睛发光滑块");
            shader.setEyeEmission(2);
            float[] strongerGlow = sample(normals,half);
            check(distance(strongerGlow,unlit) > distance(glow,unlit), "眼睛发光强度必须单调增加");
            check(Math.abs(strongerGlow[3]-.4f)<.0001f, "发光不能改变透明度");
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,albedo);
            GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D,0,0,0,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,new float[]{1,1,1,.8f});
            GL46C.glVertexAttrib1f(eyeMask, 0);
            float[] sclera = sample(normals,half);
            check(Math.max(sclera[0],Math.max(sclera[1],sclera[2])) < .01f, "眼白不能自发光");
            GL46C.glVertexAttrib1f(eyeMask, 1);
            float[] eyeHighlight = sample(normals,half);
            check(eyeHighlight[0] > 1, "眼内白色高光必须参与发光");
            GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D,0,0,0,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,new float[]{.01f,.02f,.01f,.8f});
            shader.setEyeEmission(0);
            float[] pupilUnlit = sample(normals,half);
            shader.setEyeEmission(2);
            float[] pupil = sample(normals,half);
            // 亮度在线性空间核对，兼容光影输出编码。
            check(Math.abs(linear(pupil[1], source) - linear(pupilUnlit[1], source) - Math.pow(.02, 2.2) * 2) < .00001,
                    "黑瞳必须按原贴图发光，不能被色度/亮度阈值裁掉");
            GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D,0,0,0,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,new float[]{.25f,.25f,.25f,.8f});
            shader.setEyeEmission(0);
            float[] greyUnlit = sample(normals,half);
            shader.setEyeEmission(2);
            float[] greyIris = sample(normals,half);
            check(Math.abs(linear(greyIris[0], source) - linear(greyUnlit[0], source) - Math.pow(.25, 2.2) * 2) < .00001,
                    "灰色眼瞳不能被色度阈值排除");
            // 头骨绑定的独立瞳材质也须发光；排除材质即使有眼骨也不发光。
            GL46C.glVertexAttrib1f(eyeMask, 0);
            shader.setEyeMaterialMask(1);
            float[] materialIris = sample(normals,half);
            check(distance(materialIris,greyIris) < .00001f, "独立瞳材质须补足无眼骨权重");
            GL46C.glVertexAttrib1f(eyeMask, 1);
            shader.setEyeMaterialMask(-1);
            float[] excluded = sample(normals,half);
            check(distance(excluded,greyUnlit) < .00001f, "镜片/眼白排除须覆盖眼骨遮罩");
            shader.setEyeMaterialMask(0);
            GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D,0,0,0,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,new float[]{.4f,.5f,.7f,.8f});
            shader.setEyeEmission(0);
            GL46C.glVertexAttrib1f(eyeMask, 0);
            shader.useOutline();
            int outline = shader.getOutlineProgram();
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(outline,"ProjMat"),false,matrix);
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(outline,"ModelViewMat"),false,matrix);
            shader.setOutlineWidth(0);
            shader.setOutlineAlpha(.5f);
            shader.setOutlineAlphaCutoff(.1f);
            shader.setOutlineUseTextureAlpha(true);
            shader.setOutlineColor(1,1,1);
            float[] white = sample(normals,new float[]{0,0,-1});
            for (int i = 0; i < 3; i++) check(Math.abs(white[i]-1)<.0001f,"白色描边设置");
            check(Math.abs(white[3]-.4f)<.0001f,"描边 alpha 不能重复合成");
            shader.setOutlineColor(0,0,0);
            changed(white,sample(normals,new float[]{0,0,-1}),"描边 RGB");
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR,"参数探针 GL 错误");
            System.out.println("PASS toon controls: shadow RGB, specular strength/power, rim strength/power, dark highlights, full eye emission/black pupil/white highlight/grey iris/sclera exclusion/material fallback/lens exclusion, alpha, white outline");
        } finally {
            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER,0);
            GL46C.glBindVertexArray(0);
            GL46C.glDeleteFramebuffers(fbo);
            GL46C.glDeleteTextures(albedo);
            GL46C.glDeleteTextures(output);
            GL46C.glDeleteBuffers(positions);
            GL46C.glDeleteBuffers(normals);
            GL46C.glDeleteBuffers(uvs);
            GL46C.glDeleteVertexArrays(vao);
            shader.useMain();
            shader.setEyeMaterialMask(0);
            shader.setTransitionSoftness(0);
        }
    }

    private static void checkTransitions(ToonShaderCpu shader, int normals, float[] light) {
        for (int levels = 2; levels <= 5; levels++) {
            shader.setToonLevels(levels);
            float steps = levels - 1;
            float boundary = .5f / steps;
            float delta = .06f / steps;
            shader.setTransitionSoftness(0);
            float[] sharpLow = sample(normals, withDot(light, boundary - delta));
            float[] sharpHigh = sample(normals, withDot(light, boundary + delta));
            float[] darkEndpoint = sample(normals, withDot(light, 0));
            float[] brightEndpoint = sample(normals, withDot(light, 1));
            float sharpJump = distance(sharpLow, sharpHigh);
            shader.setTransitionSoftness(.35f);
            float[] softLow = sample(normals, withDot(light, boundary - delta));
            float[] softHigh = sample(normals, withDot(light, boundary + delta));
            float softJump = distance(softLow, softHigh);
            check(softJump > .0001f && softJump < sharpJump * .85f, "35% 柔化须减小交界跳变: " + levels);
            check(Math.abs(softLow[3] - sharpLow[3]) < .0001f, "柔化不能改变透明度");
            shader.setTransitionSoftness(1);
            float previousJump = distance(sample(normals, withDot(light, boundary - delta)),
                    sample(normals, withDot(light, boundary + delta)));
            check(previousJump < softJump, "100% 须进一步扩宽过渡: " + levels);
            // 100% 内仍保留各档的平坦区。
            for (int i = 0; i < levels; i++) {
                float center = i / steps;
                float[] a = sample(normals, withDot(light, Math.max(.0001f, center - .001f)));
                float[] b = sample(normals, withDot(light, Math.min(.9999f, center + .001f)));
                check(distance(a, b) < .0002f, "柔化不能抹掉色块平坦区: " + levels + "/" + i);
            }
            for (float softness : new float[]{1.2f, 2}) {
                shader.setTransitionSoftness(softness);
                float[] lower = sample(normals, withDot(light, boundary - delta));
                float jump = distance(lower, sample(normals, withDot(light, boundary + delta)));
                check(jump < previousJump, "超过 100% 须继续柔化，不能被截断: " + levels + "/" + softness);
                check(distance(darkEndpoint, sample(normals, withDot(light, 0))) < .0002f,
                        "扩宽过渡须保留最暗端点: " + levels);
                check(distance(brightEndpoint, sample(normals, withDot(light, 1))) < .0002f,
                        "扩宽过渡须保留最亮端点: " + levels);
                check(Math.abs(lower[3] - sharpLow[3]) < .0001f, "扩展柔和度不能改变透明度");
                previousJump = jump;
            }
            shader.setTransitionSoftness(0);
            check(distance(sharpLow, sample(normals, withDot(light, boundary - delta))) < .0001f,
                    "柔和度归零须恢复原像素: " + levels);
        }
        System.out.println("PASS toon transitions: 2-5 levels, 0/35/100/120/200 softness, plateaus through 100, endpoints/alpha, zero restores pixels");
    }

    private static int texture(int unit,int size,float[] data) {
        int id = GL46C.glGenTextures();
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0+unit);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D,id);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D,0,GL46C.GL_RGBA32F,size,size,0,GL46C.GL_RGBA,GL46C.GL_FLOAT,data);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MIN_FILTER,GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D,GL46C.GL_TEXTURE_MAG_FILTER,GL46C.GL_NEAREST);
        return id;
    }

    private static int buffer(int location,int size,float[] data) {
        int id = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,id);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,data,GL46C.GL_DYNAMIC_DRAW);
        GL46C.glEnableVertexAttribArray(location);
        GL46C.glVertexAttribPointer(location,size,GL46C.GL_FLOAT,false,0,0);
        return id;
    }

    private static float[] sample(int normals,float[] n) {
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER,normals);
        GL46C.glBufferSubData(GL46C.GL_ARRAY_BUFFER,0,new float[]{n[0],n[1],n[2], n[0],n[1],n[2], n[0],n[1],n[2]});
        GL46C.glClearColor(0,0,0,0);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES,0,3);
        float[] pixel = new float[4];
        GL46C.glReadPixels(8,8,1,1,GL46C.GL_RGBA,GL46C.GL_FLOAT,pixel);
        for (float value : pixel) check(Float.isFinite(value),"参数变化不能产生非有限值");
        return pixel;
    }

    private static float[] withDot(float[] light,float dot) {
        // 测试太阳在 YZ 平面，切向量为 X 轴。
        return new float[]{(float)Math.sqrt(1-dot*dot),light[1]*dot,light[2]*dot};
    }

    private static float[] normalize(float[] v) {
        float length = (float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);
        return new float[]{v[0]/length,v[1]/length,v[2]/length};
    }

    private static float distance(float[] a,float[] b) {
        return Math.abs(a[0]-b[0])+Math.abs(a[1]-b[1])+Math.abs(a[2]-b[2]);
    }

    private static void changed(float[] a,float[] b,String option) {
        check(distance(a,b) > .002f,"设置没有改变实际像素: " + option);
    }

    private static double linear(float value, int source) {
        return source > 0 ? Math.max(value, 0) : Math.pow(Math.max(value, 0), 2.2);
    }

    private static void check(boolean value,String message) {
        if (!value) throw new AssertionError(message);
    }
}
