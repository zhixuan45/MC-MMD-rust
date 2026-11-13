package com.shiroha.mmdskin.render.shader;

import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** 在实际 Toon 程序中验证包内环境色、阴影与 uniform 同步。 */
public final class LightingProbe {
    private record Uniform(String name, String type) {}
    private record LightingCase(String name, float angle, float rain, int block, int sky, float shadowDepth) {}

    /** 原包编译后的 uniform 不能因优化缺失，否则游戏中会整条回退。 */
    public static void checkPackProgram(ToonShaderCpu shader, ToonOutputProfile profile, String vertex, String fragment) {
        int program = ShaderCompiler.compileRenderProgram(vertex, fragment, "Complementary 原包光照来源");
        check(program > 0, "原包程序编译");
        try {
            shader.useMain();
            List<String> missing = activePackUniforms(shader.getMainProgram(), profile.lightingSource()).stream()
                    .filter(uniform -> GL46C.glGetUniformLocation(program, uniform.name()) < 0)
                    .map(Uniform::name).toList();
            check(shader.setEnvironmentLighting(program, 15, 15), "原包优化后的光照 uniform 不完整: " + profile.id() + " " + missing);
            check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "原包 uniform 类型同步");
        } finally {
            GL46C.glDeleteProgram(program);
        }
    }

    public static void run(ToonShaderCpu shader, ToonOutputProfile profile) {
        int target = shader.getMainProgram();
        List<Uniform> uniforms = activePackUniforms(target, profile.lightingSource());
        List<LightingCase> scenes = List.of(
                new LightingCase("day", .25f, 0, 0, 15, 1),
                new LightingCase("night", .75f, 0, 0, 15, 1),
                new LightingCase("cave", .25f, 0, 0, 0, 0),
                new LightingCase("torch", .25f, 0, 15, 0, 0),
                new LightingCase("rain", .25f, 1, 0, 15, 1),
                new LightingCase("shadow", .25f, 0, 0, 15, 0));
        // 位置由另一程序独立分配，不能直接复用 Iris 的 location。
        int source = uniformSource(uniforms);
        Object sourceIdentity = new Object();
        int vao = GL46C.glGenVertexArrays();
        GL46C.glBindVertexArray(vao);
        int positions = buffer(0, 3, new float[]{-1,-1,-.5f, 3,-1,-.5f, -1,3,-.5f});
        int normals = buffer(1, 3, new float[]{0,1,0, 0,1,0, 0,1,0});
        int uvs = buffer(2, 2, new float[]{0,0, 0,0, 0,0});
        int albedo = colorTexture(0);
        List<Integer> packTextures = new ArrayList<>();
        int shadowTexture = 0;
        int opaqueShadowTexture = 0;
        GL46C.glUseProgram(source);
        int unit = 4;
        for (Uniform uniform : uniforms) {
            int location = GL46C.glGetUniformLocation(source, uniform.name());
            switch (uniform.type()) {
                case "mat4" -> GL46C.glUniformMatrix4fv(location, false, identity());
                case "ivec2" -> GL46C.glUniform2i(location, 240, 240);
                case "vec3" -> GL46C.glUniform3f(location, .5f, .5f, .5f);
                case "int" -> GL46C.glUniform1i(location, 0);
                case "float" -> GL46C.glUniform1f(location, switch (uniform.name()) {
                    case "screenBrightness" -> .5f;
                    case "shadowFade" -> 1;
                    case "viewWidth", "viewHeight" -> 16;
                    case "far" -> 128;
                    case "near" -> .1f;
                    default -> 0;
                });
                case "sampler2D", "sampler2DShadow" -> {
                    int texture = uniform.type().equals("sampler2DShadow") ? depthTexture(unit) : colorTexture(unit);
                    GL46C.glUniform1i(location, unit++);
                    packTextures.add(texture);
                    if (uniform.name().equals("shadowtex0")) shadowTexture = texture;
                    if (uniform.name().equals("shadowtex1")) opaqueShadowTexture = texture;
                }
                default -> throw new AssertionError("探针缺少 uniform 类型: " + uniform);
            }
        }
        int fbo = GL46C.glGenFramebuffers();
        int output = colorTexture(1);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA32F, 16, 16, 0, GL46C.GL_RGBA, GL46C.GL_FLOAT, (float[]) null);
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, fbo);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, output, 0);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "光照 FBO");
        GL46C.glViewport(0, 0, 16, 16);
        GL46C.glDisable(GL46C.GL_BLEND);
        GL46C.glDisable(GL46C.GL_DEPTH_TEST);
        GL46C.glDisable(GL46C.GL_CULL_FACE);
        shader.useMain();
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(target, "ProjMat"), false, identity());
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(target, "ModelViewMat"), false, identity());
        shader.setSampler0(0);
        shader.setToonLevels(3);
        shader.setShadowColor(.65f, .7f, .8f);
        shader.setGlobalAlpha(1);
        // 生产材质现在显式上传颜色，探针也必须给出相同输入。
        GL46C.glUniform4f(GL46C.glGetUniformLocation(target, "MaterialColor"), 1, 1, 1, 1);
        shader.setAlphaCutoff(.1f);
        shader.setTransitionSoftness(1.2f);
        shader.setLightDirection(.35f, .85f, -.4f);
        shader.setLightIntensity(.7f);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, albedo);
        float[][] bodyPixels = new float[scenes.size()][];
        float[][] outlinePixels = new float[scenes.size()][];
        for (int i = 0; i < scenes.size(); i++) {
            LightingCase scene = scenes.get(i);
            if (shadowTexture > 0) setShadowDepth(source, "shadowtex0", shadowTexture, scene.shadowDepth());
            if (opaqueShadowTexture > 0) setShadowDepth(source, "shadowtex1", opaqueShadowTexture, scene.shadowDepth());
            bodyPixels[i] = sample(shader, source, sourceIdentity, scene);
            outlinePixels[i] = sampleOutline(shader, source, sourceIdentity, scene);
            System.out.printf("Lighting %s profile=%s body=%s outline=%s%n", scene.name(), profile.id(),
                    Arrays.toString(bodyPixels[i]), Arrays.toString(outlinePixels[i]));
        }
        float[] day = bodyPixels[0], night = bodyPixels[1], cave = bodyPixels[2];
        float[] torch = bodyPixels[3], rain = bodyPixels[4], shadow = bodyPixels[5];
        float[] outlineDay = outlinePixels[0], outlineNight = outlinePixels[1], outlineCave = outlinePixels[2];
        float[] outlineTorch = outlinePixels[3], outlineRain = outlinePixels[4], outlineShadow = outlinePixels[5];
        check(luminance(day) > luminance(night) * 1.5f, "白天应明显亮于夜晚");
        check(luminance(torch) > luminance(cave) * 2, "火把应照亮洞穴");
        check(torch[0] > torch[2], "火把应保留暖色");
        check(luminance(day) > luminance(shadow), "阴影图应降低直射光");
        check(luminance(day) > luminance(rain), "降雨应影响直射光");
        check(luminance(outlineDay) > luminance(outlineNight) * 1.5f, "描边应随昼夜照明变化");
        check(luminance(outlineTorch) > luminance(outlineCave) * 2, "描边应随洞穴火把光变化");
        check(outlineTorch[0] > outlineTorch[2], "描边火把光应保留暖色");
        check(luminance(outlineDay) > luminance(outlineShadow), "描边应受阴影图影响");
        check(luminance(outlineDay) > luminance(outlineRain), "描边应受降雨影响");
        if (shadowTexture > 0) setShadowDepth(source, "shadowtex0", shadowTexture, 1);
        if (opaqueShadowTexture > 0) setShadowDepth(source, "shadowtex1", opaqueShadowTexture, 1);
        GL46C.glUseProgram(source);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "sunAngle"), .25f);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "timeAngle"), .25f);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "rainStrengthS"), 0);
        var rotation = Pattern.compile("sunPathRotation\\s*=\\s*([-0-9.]+)").matcher(profile.lightingSource());
        check(rotation.find(), "太阳旋转参数");
        double angle = Math.toRadians(Double.parseDouble(rotation.group(1)));
        checkSunlightFill(shader, source, sourceIdentity, normals, shadowTexture, opaqueShadowTexture,
                new float[]{0, (float) Math.cos(angle), -(float) Math.sin(angle)});
        checkTerrainShadowReceiver(shader, profile, source, sourceIdentity, normals, shadowTexture,
                opaqueShadowTexture, new float[]{0, (float) Math.cos(angle), -(float) Math.sin(angle)});
        checkBaseBrightness(shader, profile, source, sourceIdentity);
        if (profile.id().startsWith("bsl:")) {
            checkGrazingShadowContinuity(shader, source, sourceIdentity, normals, shadowTexture,
                    opaqueShadowTexture, new float[]{0, (float) Math.cos(angle), -(float) Math.sin(angle)});
        }
        if (profile.id().startsWith("complementary:"))
            ToonControlsProbe.run(shader, source, sourceIdentity,
                    new float[]{0, (float) Math.cos(angle), -(float) Math.sin(angle)});
        else ToonControlsProbe.runShaderStyle(shader, source, sourceIdentity, new float[]{0,(float)Math.cos(angle),-(float)Math.sin(angle)});
        shader.useMain();
        check(!shader.setEnvironmentLighting(0, 15, 15), "GUI/无 Iris 应关闭包内光照");
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, 0);
        GL46C.glDeleteFramebuffers(fbo);
        for (int texture : packTextures) GL46C.glDeleteTextures(texture);
        GL46C.glDeleteTextures(albedo);
        GL46C.glDeleteTextures(output);
        GL46C.glDeleteBuffers(positions);
        GL46C.glDeleteBuffers(normals);
        GL46C.glDeleteBuffers(uvs);
        GL46C.glDeleteVertexArrays(vao);
        GL46C.glDeleteProgram(source);
        check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "光照探针 GL 状态");
        System.out.println("PASS lighting: production body/outline shaders, profile encoding, daylight/night/cave/torch/rain/shadow, GUI reset");
    }

    private static void checkSunlightFill(ToonShaderCpu shader, int source, Object identity, int normals,
            int shadowTexture, int opaqueShadowTexture, float[] light) {
        shader.useMain();
        shader.setSpecular(96, 0);
        shader.setRimLight(5, 0);
        shader.setTransitionSoftness(1.2f);
        shader.setToonLevels(4);
        var day = new LightingCase("sunlight-fill", .25f, 0, 0, 15, 1);
        prepareScene(source, day);
        shader.useMain();
        check(shader.setEnvironmentLighting(source, identity, 0, 15), "直射光测试须使用真实包照明");
        float[] low = normalWithDot(light, .08f), high = normalWithDot(light, .95f);
        shader.setShaderStyle(0, 0);
        float[] originalLow = sampleNormal(shader, normals, low);
        float[] originalHigh = sampleNormal(shader, normals, high);
        float originalDifference = distance(originalLow, originalHigh);
        check(originalDifference > .01f, "基线须有太阳朝向明暗差");
        shader.setShaderStyle(.7f, 0);
        float[] filledLow = sampleNormal(shader, normals, low);
        float[] filledHigh = sampleNormal(shader, normals, high);
        check(distance(filledLow, filledHigh) < originalDifference, "70% 补光可减弱照度差");
        check(distance(filledLow, filledHigh) > .01f, "70% 补光必须保留表面色阶");
        check(luminance(filledLow) > luminance(originalLow), "阳光下暗朝向须获得补光");
        shader.setShaderStyle(1, 0);
        float[] flatLow = sampleNormal(shader, normals, low);
        float[] flatHigh = sampleNormal(shader, normals, high);
        check(distance(flatLow, flatHigh) > .01f, "100% 阳光补光也必须保留表面色阶");
        float[] tangent = sampleNormal(shader, normals, normalWithDot(light, 0));
        check(luminance(tangent) <= luminance(flatLow) + .0001f, "切向表面不能比低照度档位更亮");
        check(distance(tangent, flatHigh) > .01f, "切向表面必须保留 Toon 阴影并采样遮挡");
        check(Math.abs(flatLow[3] - originalLow[3]) < .00001f, "补光不得改变 alpha");
        if (shadowTexture > 0) setShadowDepth(source, "shadowtex0", shadowTexture, 0);
        if (opaqueShadowTexture > 0) setShadowDepth(source, "shadowtex1", opaqueShadowTexture, 0);
        shader.useMain();
        for (float[] n : new float[][]{low, high, normalWithDot(light, 0)}) {
            shader.setShaderStyle(0, 0);
            float[] originalShadow = sampleNormal(shader, normals, n);
            shader.setShaderStyle(1, 0);
            float[] filledShadow = sampleNormal(shader, normals, n);
            check(distance(originalShadow, filledShadow) < .0001f, "实际遮挡阴影内部不得补光");
        }
        if (shadowTexture > 0) setShadowDepth(source, "shadowtex0", shadowTexture, 1);
        if (opaqueShadowTexture > 0) setShadowDepth(source, "shadowtex1", opaqueShadowTexture, 1);
        prepareScene(source, new LightingCase("moonlight-fill", .75f, 0, 0, 15, 1));
        shader.useMain();
        check(shader.setEnvironmentLighting(source, identity, 0, 15), "月光输入同步");
        shader.setShaderStyle(0, 0);
        float[] originalNight = sampleNormal(shader, normals, low);
        shader.setShaderStyle(1, 0);
        check(distance(originalNight, sampleNormal(shader, normals, low)) < .0001f, "月光不得使用太阳补光");
        prepareScene(source, day);
        shader.useMain();
        for (int block : new int[]{0, 15}) {
            check(shader.setEnvironmentLighting(source, identity, block, 0), "室内照明输入同步");
            shader.setShaderStyle(0, 0);
            float[] originalIndoor = sampleNormal(shader, normals, low);
            shader.setShaderStyle(1, 0);
            check(distance(originalIndoor, sampleNormal(shader, normals, low)) < .0001f,
                    "低天空光室内/火把不得因阴影采样回退为无遮挡而补光");
        }
        prepareScene(source, day);
        shader.useMain();
        shader.setEnvironmentLighting(source, identity, 0, 15);
        shader.setShaderStyle(0, 0);
        check(distance(originalLow, sampleNormal(shader, normals, low)) < .0001f, "归零须恢复原像素");
        System.out.printf("PASS sunlight fill: baseline spread=%.6f, 70%%=%.6f, 100%%=%.6f; cast shadow/moonlight/indoor/torch/alpha/zero restore%n",
                originalDifference, distance(filledLow, filledHigh), distance(flatLow, flatHigh));
    }

    private static float[] normalWithDot(float[] light, float dot) {
        return new float[]{(float) Math.sqrt(1 - dot * dot), light[1] * dot, light[2] * dot};
    }

    private static void checkBaseBrightness(ToonShaderCpu shader, ToonOutputProfile profile, int source,
                                           Object identity) {
        var cave = new LightingCase("base-brightness-cave", .25f, 0, 0, 0, 0);
        shader.useMain();
        shader.setSpecular(96, 0);
        shader.setRimLight(5, 0);
        shader.setBaseBrightness(0);
        float[] original = sample(shader, source, identity, cave), previous = original;
        float[] originalOutline = sampleOutline(shader, source, identity, cave);
        float exponent = profile.id().contains(":gamma:") ? 1
                : profile.id().contains(":sqrt-linear:") ? 1.1f : 2.2f;
        try {
            for (float floor : new float[]{.15f, .35f, .6f}) {
                shader.useMain();
                shader.setBaseBrightness(floor);
                float[] body = sample(shader, source, identity, cave);
                for (int c = 0; c < 3; c++) {
                    check(body[c] >= previous[c] - .00001f, "Base brightness must increase monotonically");
                    check(Math.abs(body[c] - Math.max(original[c], Math.pow(floor, exponent))) < .0001f,
                            "Material base floor must match the profile's color encoding");
                }
                check(body[3] == original[3], "Base brightness must preserve alpha");
                float[] outline = sampleOutline(shader, source, identity, cave);
                for (int c = 0; c < 3; c++)
                    check(Math.abs(outline[c] - Math.max(originalOutline[c],
                            Math.pow(new float[]{.8f,.7f,.6f}[c] * floor, exponent))) < .0001f,
                            "Outline must use the same material-colored floor");
                shader.useOutline();
                shader.setOutlineColor(0, 0, 0);
                draw(shader.getOutlineProgram());
                check(luminance(readPixel()) == 0, "Base brightness must not turn black outlines white");
                previous = body;
            }
            shader.useMain();
            shader.setBaseBrightness(0);
            check(distance(original, sample(shader, source, identity, cave)) < .0001f,
                    "Zero base brightness must restore the original dark output");
            var day = new LightingCase("base-brightness-day", .25f, 0, 0, 15, 1);
            float[] lit = sample(shader, source, identity, day);
            shader.setBaseBrightness(.15f);
            check(distance(lit, sample(shader, source, identity, day)) < .0001f,
                    "Base floor must not add exposure to existing bright pixels");
            // The same uniform must remain available without an Iris lighting bridge.
            shader.useMain();
            shader.setEnvironmentLighting(0, identity, 0, 0);
            shader.setLightIntensity(0);
            shader.setBaseBrightness(.35f);
            draw(shader.getMainProgram());
            float[] fallback = readPixel();
            for (int c = 0; c < 3; c++)
                check(Math.abs(fallback[c] - Math.pow(.35, exponent)) < .0001f, "Base floor in lighting fallback");
            System.out.println("PASS base brightness: 0/15/35/60%, body/outline, encoding, alpha, bright pixels, black outline, fallback, zero restore");
        } finally {
            shader.useMain();
            shader.setBaseBrightness(0);
            shader.setLightIntensity(.7f);
            prepareScene(source, new LightingCase("restore-day", .25f, 0, 0, 15, 1));
            shader.useMain();
            shader.setEnvironmentLighting(source, identity, 0, 15);
        }
    }

    private static void checkGrazingShadowContinuity(ToonShaderCpu shader, int source, Object identity,
            int normals, int shadowTexture, int opaqueShadowTexture, float[] light) {
        prepareScene(source, new LightingCase("grazing-self-shadow", .25f, 0, 0, 15, 1));
        shader.useMain();
        check(shader.setEnvironmentLighting(source, identity, 0, 15), "Grazing shadow pack inputs");
        shader.setSpecular(96, 0);
        shader.setRimLight(5, 0);
        for (float strength : new float[]{.2f, 1}) {
            shader.setShaderStyle(strength, 0);
            float maximumJump = 0, worstDepth = 0;
            for (int step = 0; step <= 200; step++) {
                float depth = .4f + step * .0005f;
                if (shadowTexture > 0) setShadowDepth(source, "shadowtex0", shadowTexture, depth);
                if (opaqueShadowTexture > 0) setShadowDepth(source, "shadowtex1", opaqueShadowTexture, depth);
                shader.useMain();
                float[] tangent = sampleNormal(shader, normals, normalWithDot(light, 0));
                float[] nearTangent = sampleNormal(shader, normals, normalWithDot(light, .00001f));
                float[] backTangent = sampleNormal(shader, normals, normalWithDot(light, -.00001f));
                float jump = Math.max(distance(tangent, nearTangent), distance(tangent, backTangent));
                if (jump > maximumJump) {
                    maximumJump = jump;
                    worstDepth = depth;
                }
            }
            System.out.printf("Grazing shadow continuity: strength=%.2f, normal delta=0.00001, max RGB jump=%.6f, shadow depth=%.6f%n",
                    strength, maximumJump, worstDepth);
            check(maximumJump < .001f, "Near-identical grazing normals must not switch between shadowed and lit");
        }
        if (shadowTexture > 0) setShadowDepth(source, "shadowtex0", shadowTexture, 1);
        if (opaqueShadowTexture > 0) setShadowDepth(source, "shadowtex1", opaqueShadowTexture, 1);
        shader.useMain();
        shader.setShaderStyle(0, 0);
    }

    private static void checkTerrainShadowReceiver(ToonShaderCpu shader, ToonOutputProfile profile, int source, Object identity,
            int normals, int shadowTexture, int opaqueShadowTexture, float[] light) {
        prepareScene(source, new LightingCase("terrain-shadow", .25f, 0, 0, 15, 1));
        int outputFbo = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousTexture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        int terrainTexture = GL46C.glGenTextures();
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, terrainTexture);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_DEPTH_COMPONENT, 16, 16, 0,
                GL46C.GL_DEPTH_COMPONENT, GL46C.GL_UNSIGNED_INT, 0L);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, previousTexture);
        int terrainFbo = GL46C.glGenFramebuffers();
        ShadowSourceFixture.Renderer renderer = new ShadowSourceFixture.Renderer(terrainFbo, terrainTexture, 16);
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, terrainFbo);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_TEXTURE_2D, terrainTexture, 0);
        GL46C.glDrawBuffer(GL46C.GL_NONE);
        GL46C.glReadBuffer(GL46C.GL_NONE);
        check(GL46C.glCheckFramebufferStatus(GL46C.GL_FRAMEBUFFER) == GL46C.GL_FRAMEBUFFER_COMPLETE, "Terrain shadow FBO");
        GL46C.glDepthMask(true);
        GL46C.glClearDepth(1);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glEnable(GL46C.GL_SCISSOR_TEST);
        GL46C.glScissor(0, 0, 8, 16);
        GL46C.glClearDepth(0);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        // A deliberately narrow scissor must not crop the copy.
        GL46C.glScissor(0, 0, 1, 1);
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, outputFbo);
        com.shiroha.mmdskin.compat.iris.IrisTerrainShadow.beginFrame(renderer, true);
        com.shiroha.mmdskin.compat.iris.IrisTerrainShadow.capture(renderer);
        check(com.shiroha.mmdskin.compat.iris.IrisTerrainShadow.texture() != 0, "Terrain snapshot");
        check(GL46C.glIsEnabled(GL46C.GL_SCISSOR_TEST), "Snapshot scissor restoration");
        check(GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING) == outputFbo, "Snapshot framebuffer restoration");
        GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
        // Later entity submissions may darken the full host map without changing the snapshot.
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, terrainFbo);
        GL46C.glClear(GL46C.GL_DEPTH_BUFFER_BIT);
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, outputFbo);
        if (shadowTexture > 0) setShadowDepth(source, "shadowtex0", shadowTexture, 0);
        if (opaqueShadowTexture > 0) setShadowDepth(source, "shadowtex1", opaqueShadowTexture, 0);
        shader.setTerrainShadowReceiver(false);
        shader.useMain();
        check(shader.setEnvironmentLighting(source, identity, 0, 15), "Terrain shadow inputs");
        shader.setShaderStyle(.2f, 0);
        shader.setSpecular(96, 0); shader.setRimLight(5, 0);
        float[] normal = normalWithDot(light, .8f);
        float[] selfShadow = sampleNormal(shader, normals, normal);
        int[] originalBindings = textureBindings();
        shader.setTerrainShadowReceiver(true);
        check(shader.setEnvironmentLighting(source, identity, 0, 15), "Terrain receiver sync");
        sampleNormal(shader, normals, normal);
        float[] left = readPixel(3, 8), right = readPixel(12, 8);
        check(luminance(right) > luminance(selfShadow) * 1.2f, "Terrain receiver must ignore later entity self-occlusion");
        check(luminance(left) < luminance(right) * .8f, "One draw must contain both shadowed and lit terrain regions");
        float maximumDifference = 0;
        for (float[] angle : new float[][]{{.3f,0}, {-.3f,.22f}, {.15f,-.3f}, {0, .3f}}) {
            var view = new org.joml.Matrix4f().rotateY(angle[0]).rotateX(angle[1]);
            var inverse = new org.joml.Matrix4f(view).invert();
            GL46C.glUseProgram(source);
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(source, "gbufferModelView"), false, view.get(new float[16]));
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(source, "gbufferModelViewInverse"), false,
                    inverse.get(new float[16]));
            shader.useMain();
            check(shader.setEnvironmentLighting(source, identity, 0, 15), "Camera rotation shadow inputs");
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(shader.getMainProgram(), "ModelViewMat"), false, view.get(new float[16]));
            // Hold rasterized world points fixed while changing the view-space representation.
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(shader.getMainProgram(), "ProjMat"), false, inverse.get(new float[16]));
            sampleNormal(shader, normals, normal);
            maximumDifference = Math.max(maximumDifference,
                    Math.max(distance(left, readPixel(3, 8)), distance(right, readPixel(12, 8))));
        }
        check(maximumDifference < .001f, "Terrain shadow must remain stable under camera rotation");
        GL46C.glUseProgram(source);
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(source, "gbufferModelView"), false, identity());
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(source, "gbufferModelViewInverse"), false, identity());
        shader.useMain(); shader.setEnvironmentLighting(source, identity, 0, 15);
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(shader.getMainProgram(), "ModelViewMat"), false, identity());
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(shader.getMainProgram(), "ProjMat"), false, identity());
        sampleOutline(shader, source, identity, new LightingCase("terrain-outline", .25f, 0, 0, 15, 0));
        check(luminance(readPixel(3, 8)) < luminance(readPixel(12, 8)) * .8f, "Outline must follow the same terrain split");
        checkTerrainMaskIgnoresMeshNormals(shader, profile, source, identity, normals, normal, light);
        checkMixedLightSurface(shader, source, identity, light);
        checkWorldShadowSoftness(shader, source, identity, left, right);
        checkTerrainColorIsolation(shader, source, identity, shadowTexture, opaqueShadowTexture);
        if (profile.lightingSource().contains("toonBslFilteredTransmission")
                || profile.lightingSource().contains("toonComplementaryFilteredTransmission"))
            checkGlassReception(shader, profile, source, identity, normals, normal, light, terrainFbo, terrainTexture);
        shader.useMain();
        com.shiroha.mmdskin.compat.iris.IrisTerrainShadow.beginFrame(renderer, true);
        check(com.shiroha.mmdskin.compat.iris.IrisTerrainShadow.texture() == 0, "New frame must invalidate a missing capture");
        shader.setEnvironmentLighting(source, identity, 0, 15);
        check(distance(selfShadow, sampleNormal(shader, normals, normal)) < .001f, "Missing snapshot must restore classic self-shadow");
        shader.setTerrainShadowReceiver(false);
        shader.releaseTerrainShadowBinding();
        check(Arrays.equals(originalBindings, textureBindings()), "Body/outline terrain samplers must restore all texture and sampler bindings");
        com.shiroha.mmdskin.compat.iris.IrisTerrainShadow.destroy(renderer);
        GL46C.glDeleteFramebuffers(terrainFbo);
        GL46C.glDeleteTextures(terrainTexture);
        GL46C.glClearDepth(1);
        if (shadowTexture > 0) setShadowDepth(source, "shadowtex0", shadowTexture, 1);
        if (opaqueShadowTexture > 0) setShadowDepth(source, "shadowtex1", opaqueShadowTexture, 1);
        shader.useMain(); shader.setEnvironmentLighting(source, identity, 0, 15); shader.setShaderStyle(0, 0);
        System.out.printf("PASS terrain shadows: classic=%.6f, shadow=%.6f, lit=%.6f, camera delta=%.6f%n",
                luminance(selfShadow), luminance(left), luminance(right), maximumDifference);
    }

    private static void checkTerrainMaskIgnoresMeshNormals(ToonShaderCpu original, ToonOutputProfile profile,
            int source, Object identity, int normals, float[] originalNormal, float[] light) {
        original.releaseTerrainShadowBinding();
        float[] depth = new float[16 * 16];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) depth[y * 16 + x] = x < 8 ? .447f : 1;
        uploadTerrainDepth(depth);
        var mask = new ToonShaderCpu() {
            @Override protected int compileProgram(String vertex, String fragment, String name) {
                return super.compileProgram(vertex, fragment.replace(
                        "writeToonOutputs(finalColor, albedo, normal, finalAlpha);",
                        "toonOutput0 = vec4(vec3(toonSunVisibility), finalAlpha);"), name);
            }
        };
        check(mask.selectOutputProfile(profile), "Terrain visibility diagnostic shader");
        try {
            mask.useMain();
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(mask.getMainProgram(), "ProjMat"), false, identity());
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(mask.getMainProgram(), "ModelViewMat"), false, identity());
            GL46C.glUniform4f(GL46C.glGetUniformLocation(mask.getMainProgram(), "MaterialColor"), 1, 1, 1, 1);
            mask.setSampler0(0);
            mask.setAlphaCutoff(.001f);
            mask.setShaderStyle(1, 0);
            mask.setTerrainShadowReceiver(true);
            float maximum = 0;
            for (float softness : new float[]{0, 1.5f, 4}) {
                mask.useMain();
                mask.setWorldShadowSoftness(softness);
                check(mask.setEnvironmentLighting(source, identity, 0, 15), "Terrain diagnostic environment");
                float[] reference = null;
                for (float ndl : new float[]{1, .8f, .2f, .05f, .00001f, 0, -.00001f}) {
                    sampleNormal(mask, normals, normalWithDot(light, ndl));
                    float[] pixels = new float[16 * 16 * 4];
                    GL46C.glReadPixels(0, 0, 16, 16, GL46C.GL_RGBA, GL46C.GL_FLOAT, pixels);
                    if (reference == null) {
                        reference = pixels;
                        float minimum = 1, highest = 0;
                        for (int i = 0; i < pixels.length; i += 4) {
                            minimum = Math.min(minimum, pixels[i]);
                            highest = Math.max(highest, pixels[i]);
                        }
                        check(highest - minimum > .1f, "Mesh seam scene must actually contain a world shadow boundary");
                    } else for (int i = 0; i < pixels.length; i++)
                        maximum = Math.max(maximum, Math.abs(reference[i] - pixels[i]));
                }
            }
            check(maximum < .0001f, "World shadow mask must not split along mesh normals: " + maximum);
            System.out.printf("PASS terrain mesh seam: visibility delta=%.6f across grazing/front/back normals and softness 0/1.5/4%n", maximum);
        } finally {
            mask.cleanup();
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) depth[y * 16 + x] = x < 8 ? 0 : 1;
            uploadTerrainDepth(depth);
            original.useMain();
            original.setEnvironmentLighting(source, identity, 0, 15);
            sampleNormal(original, normals, originalNormal);
        }
    }

    private static void uploadTerrainDepth(float[] depth) {
        int previous = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, com.shiroha.mmdskin.compat.iris.IrisTerrainShadow.texture());
        GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, 16, 16,
                GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, depth);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, previous);
    }

    private static void checkGlassReception(ToonShaderCpu shader, ToonOutputProfile profile, int source, Object identity, int normals,
                                           float[] normal, float[] light, int terrainFbo, int terrainDepth) {
        shader.releaseTerrainShadowBinding();
        int draw = GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        int[] bindings = textureBindings();
        int color = colorTexture(3);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA32F, 16, 16, 0, GL46C.GL_RGBA, GL46C.GL_FLOAT, 0L);
        int opaque = GL46C.glGenFramebuffers(), glass = GL46C.glGenFramebuffers();
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, opaque);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_TEXTURE_2D,
                com.shiroha.mmdskin.compat.iris.IrisTerrainShadow.texture(), 0);
        GL46C.glDrawBuffer(GL46C.GL_NONE); GL46C.glReadBuffer(GL46C.GL_NONE);
        GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, glass);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_DEPTH_ATTACHMENT, GL46C.GL_TEXTURE_2D, terrainDepth, 0);
        GL46C.glFramebufferTexture2D(GL46C.GL_FRAMEBUFFER, GL46C.GL_COLOR_ATTACHMENT0, GL46C.GL_TEXTURE_2D, color, 0);
        GL46C.glDrawBuffer(GL46C.GL_COLOR_ATTACHMENT0); GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
        int caster = ShaderCompiler.compileRenderProgram("""
                #version 330 core
                void main() {
                    vec2 p=vec2((gl_VertexID << 1)&2,gl_VertexID&2);
                    gl_Position=vec4(p*2.0-1.0,-0.4,1.0);
                }
                """, """
                #version 330 core
                uniform vec3 Transmission;
                layout(location=0) out vec4 Color;
                void main() { Color=vec4(Transmission,1.0); }
                """, "glass caster for production pack lighting");
        try {
            // Start with terrain only; the original host map can subsequently contain every PMX caster.
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, opaque);
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, terrainFbo);
            GL46C.glDisable(GL46C.GL_SCISSOR_TEST);
            GL46C.glBlitFramebuffer(0,0,16,16,0,0,16,16,GL46C.GL_DEPTH_BUFFER_BIT,GL46C.GL_NEAREST);
            float storageScale = transmissionStorageScale(profile);
            String storageProtocol = storageScale == .2f ? "complementary-modern-r511"
                    : storageScale == .1f ? "complementary-modern" : "bsl";
            for (float[] transmission : new float[][]{{.65f,.65f,.65f}, {.85f,.15f,.1f}}) {
                float[] storedTransmission = Arrays.copyOf(transmission, transmission.length);
                for (int channel = 0; channel < storedTransmission.length; channel++)
                    storedTransmission[channel] *= storageScale;
                GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, draw);
                GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, read);
                check(com.shiroha.mmdskin.compat.iris.IrisTerrainTransmission.capture(
                        terrainFbo, color, opaque, 16, GL46C.GL_DEPTH_COMPONENT24, () -> {
                            GL46C.glBindFramebuffer(GL46C.GL_FRAMEBUFFER, glass);
                            GL46C.glUseProgram(caster);
                            GL46C.glUniform3fv(GL46C.glGetUniformLocation(caster, "Transmission"), storedTransmission);
                            GL46C.glEnable(GL46C.GL_DEPTH_TEST);
                            GL46C.glDepthFunc(GL46C.GL_LESS);
                            GL46C.glDepthMask(true);
                            GL46C.glDisable(GL46C.GL_BLEND);
                            GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
                        }), "Production glass snapshot");
                check(GL46C.glGetError() == GL46C.GL_NO_ERROR, "Production glass replay GL state");
                System.out.printf("Glass storage: source=%s, physical=%s, stored=%s, scale=%.1f%n",
                        storageProtocol, Arrays.toString(transmission),
                        Arrays.toString(storedTransmission), storageScale);
                checkGlassSunCoverage(shader, profile, source, identity, normals, light);
                float[] original = null;
                for (float softness : new float[]{0, 1.5f, 4}) {
                    shader.useMain();
                    shader.setBaseBrightness(0);
                    shader.setWorldShadowSoftness(softness);
                    check(shader.setEnvironmentLighting(source, identity, 0, 15), "Glass pack environment");
                    GL46C.glValidateProgram(shader.getMainProgram());
                    check(GL46C.glGetProgrami(shader.getMainProgram(), GL46C.GL_VALIDATE_STATUS) != 0,
                            "Glass sampler validation: " + GL46C.glGetProgramInfoLog(shader.getMainProgram()));
                    float[] lowNormal = normalWithDot(light, .08f);
                    shader.setShaderStyle(0, 0);
                    sampleNormal(shader, normals, lowNormal);
                    float[] unfilledGlass = readPixel(12, 8), unfilledBlocked = readPixel(3, 8);
                    shader.setShaderStyle(.7f, 0);
                    sampleNormal(shader, normals, lowNormal);
                    float[] filledGlass = readPixel(12, 8), filledBlocked = readPixel(3, 8);
                    System.out.printf("Glass Toon fill: transmission=%s, softness=%.1f, baseline=%.6f, filled=%.6f%n",
                            Arrays.toString(transmission), softness, luminance(unfilledGlass), luminance(filledGlass));
                    check(luminance(filledGlass) > luminance(unfilledGlass) + .005f,
                            "Glass absorption must not disable sunlight Toon fill on a grazing surface");
                    check(distance(unfilledBlocked, filledBlocked) < .0001f,
                            "Opaque terrain must still suppress sunlight fill beside the glass");
                    check(filledGlass[3] == unfilledGlass[3], "Glass Toon fill preserves alpha");
                    sampleNormal(shader, normals, normal);
                    float[] behind = readPixel(12, 8), blocked = readPixel(3, 8);
                    if (original == null) original = behind;
                    check(luminance(behind) > luminance(blocked), "Glass transmits light; opaque terrain still occludes");
                    // Hold the receiver in front of the same glass while leaving XY and all mesh normals unchanged.
                    GL46C.glUseProgram(source);
                    GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(source, "shadowProjection"), false,
                            new org.joml.Matrix4f().translate(0, 0, -2.5f).get(new float[16]));
                    shader.useMain(); shader.setEnvironmentLighting(source, identity, 0, 15);
                    sampleNormal(shader, normals, normal);
                    float[] front = readPixel(12, 8);
                    check(luminance(front) > luminance(behind) + .05f, "Only receivers behind glass may be attenuated");
                    GL46C.glUseProgram(source);
                    GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(source, "shadowProjection"), false, identity());
                    shader.useMain(); shader.setEnvironmentLighting(source, identity, 0, 15);
                    sampleNormal(shader, normals, normal);
                    if (transmission[0] > transmission[2])
                        check(behind[0] / behind[2] > front[0] / front[2] * 1.3f, "Colored glass must tint sunlight");
                    GL46C.glUniform1i(GL46C.glGetUniformLocation(shader.getMainProgram(), "ToonTerrainShadowTransmissionEnabled"), 0);
                    sampleNormal(shader, normals, normal);
                    check(luminance(readPixel(12, 8)) > luminance(behind) + .05f,
                            "Control without the new transmission input must reproduce the missing-glass light");
                    shader.setEnvironmentLighting(source, identity, 0, 15);
                    int hostUnit = GL46C.glGetUniformi(source, GL46C.glGetUniformLocation(source, "shadowtex0"));
                    GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + hostUnit);
                    int hostDepth = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
                    setShadowDepth(source, "shadowtex0", hostDepth, 1);
                    sampleNormal(shader, normals, normal);
                    check(distance(behind, readPixel(12, 8)) < .0001f,
                            "Later PMX depth in the host shadow map must not change glass reception");
                    setShadowDepth(source, "shadowtex0", hostDepth, 0);
                    shader.setBaseBrightness(.3f);
                    sampleNormal(shader, normals, normal);
                    float[] floor = readPixel(12, 8);
                    for (int c = 0; c < 3; c++) check(floor[c] >= behind[c] - .0001f, "Glass and base brightness compose");
                    check(floor[3] == behind[3], "Glass/base brightness preserve alpha");
                    shader.setBaseBrightness(0);
                    sampleOutline(shader, source, identity, new LightingCase("glass-outline", .25f, 0, 0, 15, 0));
                    check(luminance(readPixel(12, 8)) > luminance(readPixel(3, 8)), "Outline receives the same glass light");
                    shader.useMain();
                }
                System.out.printf("PASS glass reception: transmission=%s, behind RGB=%s, softness 0/1.5/4, front/behind, opaque precedence, outline%n",
                        Arrays.toString(transmission), Arrays.toString(original));
            }
        } finally {
            shader.releaseTerrainShadowBinding();
            com.shiroha.mmdskin.compat.iris.IrisTerrainTransmission.release();
            GL46C.glBindFramebuffer(GL46C.GL_DRAW_FRAMEBUFFER, draw);
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, read);
            GL46C.glActiveTexture(GL46C.GL_TEXTURE3);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, bindings[6]);
            GL46C.glBindSampler(3, bindings[7]);
            GL46C.glActiveTexture(active);
            GL46C.glDeleteProgram(caster);
            GL46C.glDeleteFramebuffers(glass);
            GL46C.glDeleteFramebuffers(opaque);
            GL46C.glDeleteTextures(color);
            shader.useMain(); shader.setWorldShadowSoftness(1.5f);
            shader.setEnvironmentLighting(source, identity, 0, 15);
        }
    }

    private static float transmissionStorageScale(ToonOutputProfile profile) {
        String code = ComplementaryToonLighting.clean(profile.lightingSource())
                .replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        boolean legacy = code.contains("colormult=1.2+3.8*lightmapy;")
                && code.contains("colorpow=1.1-0.6*pow2(pow2(pow2(lightmapy)));");
        if (legacy) return .2f;
        boolean modern = code.contains(
                "colormult=2.5+5.5*pow1_5(lightmapy)+2.0*lightmapy2;")
                && code.contains("colorpow=mix(1.5+0.5*float(iseyeinwater==0),0.5,lightmapy4);");
        return modern ? .1f : 1f;
    }

    private static void checkGlassSunCoverage(ToonShaderCpu original, ToonOutputProfile profile, int source,
                                              Object identity, int normals, float[] light) {
        original.releaseTerrainShadowBinding();
        var diagnostic = new ToonShaderCpu() {
            @Override protected int compileProgram(String vertex, String fragment, String name) {
                String traced = fragment.replaceFirst("(?m)^(#version[^\\r\\n]*)",
                        "$1\n#define TOON_LIGHTING_DEBUG 6");
                return super.compileProgram(vertex, traced.replace(
                        "writeToonOutputs(finalColor, albedo, normal, finalAlpha);",
                        "toonOutput0 = vec4(toonSunVisibility, toonDebugDirect, toonSunlightStrength, finalAlpha);"), name);
            }
        };
        check(diagnostic.selectOutputProfile(profile), "Glass sunlight coverage diagnostic shader");
        try {
            diagnostic.useMain();
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(diagnostic.getMainProgram(), "ProjMat"), false, identity());
            GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(diagnostic.getMainProgram(), "ModelViewMat"), false, identity());
            GL46C.glUniform4f(GL46C.glGetUniformLocation(diagnostic.getMainProgram(), "MaterialColor"), 1, 1, 1, 1);
            diagnostic.setSampler0(0);
            diagnostic.setAlphaCutoff(.001f);
            diagnostic.setTerrainShadowReceiver(true);
            diagnostic.setTransitionSoftness(1.2f);
            diagnostic.setToonLevels(4);
            float[] normal = normalWithDot(light, .08f);
            float maximumError = 0;
            int penumbraSamples = 0;
            for (float softness : new float[]{0, 1.5f, 4}) {
                diagnostic.useMain();
                diagnostic.setWorldShadowSoftness(softness);
                for (int sky : new int[]{1, 4, 8, 12, 15}) {
                    diagnostic.useMain();
                    diagnostic.setShaderStyle(0, 0);
                    check(diagnostic.setEnvironmentLighting(source, identity, 0, sky), "Glass coverage environment");
                    sampleNormal(diagnostic, normals, normal);
                    float[] baseline = readPixel(12, 8);
                    diagnostic.setShaderStyle(.7f, 0);
                    sampleNormal(diagnostic, normals, normal);
                    float[] lit = readPixel(12, 8), blocked = readPixel(3, 8);
                    System.out.printf("Glass coverage: sky=%d, softness=%.1f, visibility=%.6f, direct=%.6f, strength=%.6f%n",
                            sky, softness, lit[0], lit[1], lit[2]);
                    // Complementary remaps NdotL before Toon, so the unfilled ramp is
                    // intentionally nonzero. Verify the available fill headroom.
                    check(baseline[1] < .9f && lit[1] > baseline[1] + (1 - baseline[1]) * .6f,
                            "Verified sun coverage behind glass must enable fill even with low block skylight");
                    check(Math.abs(blocked[1] - baseline[1]) < .0001f, "Wall-covered fragments receive no fill");
                    for (int x = 1; x < 15; x++) {
                        float[] pixel = readPixel(x, 8);
                        if (pixel[0] > .0001f && pixel[0] < .9999f) penumbraSamples++;
                        float expected = baseline[1] + (1 - baseline[1]) * .7f * pixel[0];
                        maximumError = Math.max(maximumError, Math.abs(pixel[1] - expected));
                    }
                }
            }
            check(penumbraSamples > 0, "Glass coverage fixture must include actual penumbra fragments");
            check(maximumError < .0001f, "Sunlight fill must follow fragment coverage continuously: " + maximumError);
            diagnostic.useMain();
            diagnostic.setWorldShadowSoftness(0);
            diagnostic.setTerrainShadowReceiver(false);
            diagnostic.setEnvironmentLighting(source, identity, 0, 0);
            diagnostic.setShaderStyle(0, 0);
            sampleNormal(diagnostic, normals, normal);
            float noSampleDirect = readPixel(12, 8)[1];
            diagnostic.setShaderStyle(.7f, 0);
            sampleNormal(diagnostic, normals, normal);
            check(readPixel(12, 8)[2] == 0 && Math.abs(readPixel(12, 8)[1] - noSampleDirect) < .0001f,
                    "A classic receiver without terrain samples cannot claim verified sunlight coverage");
            diagnostic.setTerrainShadowReceiver(true);
            prepareScene(source, new LightingCase("glass-coverage-night", .75f, 0, 0, 15, 1));
            diagnostic.useMain();
            diagnostic.setWorldShadowSoftness(0);
            diagnostic.setEnvironmentLighting(source, identity, 0, 15);
            sampleNormal(diagnostic, normals, normal);
            check(readPixel(12, 8)[2] == 0, "Moonlight behind glass cannot enable sunlight fill");
            System.out.printf("PASS glass sunlight coverage: sky 1/4/8/12/15, softness 0/1.5/4, wall split, continuous coverage, missing sample, moonlight; penumbra=%d, error=%.6f%n",
                    penumbraSamples, maximumError);
        } finally {
            diagnostic.cleanup();
            prepareScene(source, new LightingCase("restore-glass-day", .25f, 0, 0, 15, 1));
            original.useMain();
            original.setEnvironmentLighting(source, identity, 0, 15);
        }
        for (int sky : new int[]{8, 12}) {
            original.useMain();
            original.setBaseBrightness(0);
            original.setWorldShadowSoftness(0);
            original.setEnvironmentLighting(source, identity, 0, sky);
            original.setShaderStyle(0, 0);
            sampleNormal(original, normals, normalWithDot(light, .08f));
            float[] baseline = readPixel(12, 8), blocked = readPixel(3, 8);
            original.setShaderStyle(.7f, 0);
            sampleNormal(original, normals, normalWithDot(light, .08f));
            float[] filled = readPixel(12, 8);
            System.out.printf("Glass final RGB at low skylight: sky=%d, baseline=%.6f, filled=%.6f%n",
                    sky, luminance(baseline), luminance(filled));
            check(luminance(filled) > luminance(baseline) + .001f,
                    "Fragment coverage must affect final glass-lit color at low skylight");
            check(distance(blocked, readPixel(3, 8)) < .0001f,
                    "Low-skylight wall shadow must remain unchanged");
        }
        original.setEnvironmentLighting(source, identity, 0, 15);
    }

    /** Four normal bands on one surface, all crossing the same terrain shadow in one draw. */
    private static void checkMixedLightSurface(ToonShaderCpu shader, int source, Object identity, float[] light) {
        int previousVao = GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING);
        int vao = GL46C.glGenVertexArrays();
        GL46C.glBindVertexArray(vao);
        float[] positions = new float[4 * 6 * 3], normals = new float[positions.length];
        for (int band = 0; band < 4; band++) {
            float bottom = -1 + band * .5f, top = bottom + .5f;
            float[] quad = {-1,bottom,-.5f, 1,bottom,-.5f, 1,top,-.5f,
                    -1,bottom,-.5f, 1,top,-.5f, -1,top,-.5f};
            System.arraycopy(quad, 0, positions, band * 18, 18);
            float[] n = normalWithDot(light, new float[]{.05f,.95f,.2f,.8f}[band]);
            for (int v = 0; v < 6; v++) System.arraycopy(n, 0, normals, band * 18 + v * 3, 3);
        }
        int p = buffer(0, 3, positions), n = buffer(1, 3, normals), uv = buffer(2, 2, new float[4 * 6 * 2]);
        try {
            shader.useMain();
            shader.setShaderStyle(.7f, 0);
            shader.setSpecular(96, 0);
            shader.setRimLight(5, 0);
            shader.setWorldShadowSoftness(0);
            check(shader.setEnvironmentLighting(source, identity, 0, 15), "Mixed-light environment");
            GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT);
            GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 24);
            float[] dark = readPixel(3, 2), lit = readPixel(12, 2);
            float darkSpread = 0, litSpread = 0;
            for (int band = 1; band < 4; band++) {
                darkSpread = Math.max(darkSpread, distance(dark, readPixel(3, 2 + band * 4)));
                litSpread = Math.max(litSpread, distance(lit, readPixel(12, 2 + band * 4)));
            }
            System.out.printf("Mixed-light surface: full-shadow normal spread=%.6f, sunlit Toon spread=%.6f%n",
                    darkSpread, litSpread);
            check(darkSpread < .0001f, "Ambient-only terrain shadow must not acquire sun-facing mesh bands");
            check(litSpread > .01f, "Sunlit surface must retain Toon bands");
            check(luminance(lit) > luminance(dark) * 1.2f, "One surface must still receive the world shadow");
            for (float softness : new float[]{0, 1.5f, 4}) {
                shader.setWorldShadowSoftness(softness);
                shader.setEnvironmentLighting(source, identity, 0, 15);
                for (float floor : new float[]{0, .15f, .35f}) {
                    shader.setBaseBrightness(floor);
                    GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT);
                    GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 24);
                    for (int band = 0; band < 4; band++) {
                        float previous = -1;
                        for (int x = 0; x < 16; x++) {
                            float[] pixel = readPixel(x, band * 4 + 2);
                            float value = luminance(pixel);
                            check(value >= previous - .0001f,
                                    "Shadow edge dark seam: softness=" + softness + ", floor=" + floor
                                            + ", band=" + band + ", x=" + x + ", " + previous + " -> " + value);
                            check(pixel[3] == 1, "Mixed-light floor and filtering must preserve coverage");
                            previous = value;
                        }
                        check(luminance(readPixel(15, band * 4 + 2)) > luminance(readPixel(0, band * 4 + 2)) + .05f,
                                "Moderate base brightness must preserve terrain shadow contrast");
                    }
                }
            }
            System.out.println("PASS mixed-light composition: one draw, four normal bands, softness 0/1.5/4, floor 0/15/35%, monotone shadow edges and retained contrast");
        } finally {
            shader.useMain();
            shader.setBaseBrightness(0);
            shader.setShaderStyle(.2f, 0);
            shader.setWorldShadowSoftness(1.5f);
            GL46C.glBindVertexArray(previousVao);
            GL46C.glDeleteBuffers(p);
            GL46C.glDeleteBuffers(n);
            GL46C.glDeleteBuffers(uv);
            GL46C.glDeleteVertexArrays(vao);
        }
    }

    private static void checkWorldShadowSoftness(ToonShaderCpu shader, int source, Object identity,
                                                float[] dark, float[] light) {
        int projectionLocation = GL46C.glGetUniformLocation(source, "shadowProjection");
        float[] projection = new float[16];
        GL46C.glGetUniformfv(source, projectionLocation, projection);
        GL46C.glUseProgram(source);
        GL46C.glUniformMatrix4fv(projectionLocation, false, new org.joml.Matrix4f().scaling(.08f, 1, 1).get(new float[16]));
        int[] widths = new int[3];
        float[] original = null;
        float minimum = luminance(dark), range = luminance(light) - minimum;
        float[] radii = {0, 1.5f, 4, 0};
        for (int step = 0; step < radii.length; step++) {
            shader.useMain();
            shader.setWorldShadowSoftness(radii[step]);
            shader.setEnvironmentLighting(source, identity, 0, 15);
            draw(shader.getMainProgram());
            float[] row = new float[16 * 4];
            GL46C.glReadPixels(0, 8, 16, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, row);
            if (step == 0) original = row;
            if (step == 3) {
                for (int i = 0; i < row.length; i++)
                    check(Math.abs(row[i] - original[i]) < .0001f, "Zero softness must restore original pack filtering");
                continue;
            }
            for (int pixel = 0; pixel < 16; pixel++) {
                float value = luminance(Arrays.copyOfRange(row, pixel * 4, pixel * 4 + 4));
                if (value > minimum + range * .08f && value < minimum + range * .92f) widths[step]++;
            }
        }
        check(widths[2] > widths[0], "Softness slider must widen the transition instead of merely changing brightness");
        check(widths[1] >= widths[0] && widths[2] >= widths[1], "Increasing softness must not narrow the transition");
        shader.setWorldShadowSoftness(2.75f);
        shader.setEnvironmentLighting(source, identity, 0, 15);
        shader.useOutline();
        check(Math.abs(GL46C.glGetUniformf(shader.getOutlineProgram(),
                GL46C.glGetUniformLocation(shader.getOutlineProgram(), "ToonWorldShadowSoftness")) - 2.75f) < .0001f,
                "Outline must receive the same live softness value");
        GL46C.glUseProgram(source);
        GL46C.glUniformMatrix4fv(projectionLocation, false, projection);
        shader.useMain();
        shader.setWorldShadowSoftness(1.5f);
        shader.setEnvironmentLighting(source, identity, 0, 15);
        System.out.printf("PASS world shadow softness: intermediate pixels at 0/1.5/4 = %s; zero restores, outline updates%n",
                Arrays.toString(widths));
    }

    private static void checkTerrainColorIsolation(ToonShaderCpu shader, int source, Object identity,
                                                   int shadowTexture, int opaqueShadowTexture) {
        int colorLocation = GL46C.glGetUniformLocation(source, "shadowcolor0");
        if (colorLocation < 0 || opaqueShadowTexture <= 0) return;
        int colorUnit = GL46C.glGetUniformi(source, colorLocation);
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + colorUnit);
        int colorTexture = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        if (shadowTexture > 0) setShadowDepth(source, "shadowtex0", shadowTexture, 0);
        setShadowDepth(source, "shadowtex1", opaqueShadowTexture, 1);
        GL46C.glUseProgram(source);
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(source, "shadowModelView"), false,
                new org.joml.Matrix4f().translation(-.0625f, 0, 0).get(new float[16]));
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, com.shiroha.mmdskin.compat.iris.IrisTerrainShadow.texture());
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_LINEAR);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_LINEAR);
        // Restore the host unit before inspecting the complete binding restoration later.
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, opaqueShadowTexture);
        float terrainDifference = 0, classicDifference = 0;
        for (boolean terrain : new boolean[]{false, true}) {
            shader.setTerrainShadowReceiver(terrain);
            shader.useMain();
            shader.setEnvironmentLighting(source, identity, 0, 15);
            float[][] frames = new float[2][16 * 16 * 4];
            for (int tint = 0; tint < 2; tint++) {
                GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + colorUnit);
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, colorTexture);
                GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT,
                        tint == 0 ? new float[]{1, 0, 0, 1} : new float[]{0, 0, 1, 1});
                draw(shader.getMainProgram());
                GL46C.glReadPixels(0, 0, 16, 16, GL46C.GL_RGBA, GL46C.GL_FLOAT, frames[tint]);
            }
            float maximum = 0;
            for (int i = 0; i < frames[0].length; i++) maximum = Math.max(maximum, Math.abs(frames[0][i] - frames[1][i]));
            if (terrain) terrainDifference = maximum;
            else classicDifference = maximum;
        }
        check(terrainDifference < .0001f, "Opaque terrain shadow edges must not inherit host/entity shadow colors");
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + colorUnit);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, colorTexture);
        GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, new float[]{1, 1, 1, 1});
        GL46C.glActiveTexture(active);
        GL46C.glUseProgram(source);
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(source, "shadowModelView"), false, identity());
        if (opaqueShadowTexture > 0) setShadowDepth(source, "shadowtex1", opaqueShadowTexture, 0);
        shader.useMain();
        shader.setEnvironmentLighting(source, identity, 0, 15);
        System.out.printf("PASS terrain shadow color isolation: classic tint delta=%.6f, terrain tint delta=%.6f%n",
                classicDifference, terrainDifference);
    }

    private static float[] sampleNormal(ToonShaderCpu shader, int buffer, float[] n) {
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, buffer);
        GL46C.glBufferSubData(GL46C.GL_ARRAY_BUFFER, 0,
                new float[]{n[0], n[1], n[2], n[0], n[1], n[2], n[0], n[1], n[2]});
        draw(shader.getMainProgram());
        return readPixel();
    }

    private static float distance(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
    }

    private static float[] sample(ToonShaderCpu shader, int source, Object identity, LightingCase scene) {
        prepareScene(source, scene);
        shader.useMain();
        check(shader.setEnvironmentLighting(source, identity, scene.block(), scene.sky()), "实际包光照 uniform 同步失败");
        draw(shader.getMainProgram());
        return readPixel();
    }

    private static float[] sampleOutline(ToonShaderCpu shader, int source, Object identity, LightingCase scene) {
        prepareScene(source, scene);
        shader.useMain();
        check(shader.setEnvironmentLighting(source, identity, scene.block(), scene.sky()), "描边光照 uniform 同步失败");
        shader.useOutline();
        shader.setOutlineFollowLighting(true);
        int program = shader.getOutlineProgram();
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(program, "ProjMat"), false, identity());
        GL46C.glUniformMatrix4fv(GL46C.glGetUniformLocation(program, "ModelViewMat"), false, identity());
        shader.setOutlineWidth(0);
        shader.setOutlineColor(.8f, .7f, .6f);
        shader.setOutlineAlpha(1);
        shader.setOutlineAlphaCutoff(.1f);
        shader.setOutlineUseTextureAlpha(false);
        draw(program);
        return readPixel();
    }

    private static void prepareScene(int source, LightingCase scene) {
        GL46C.glUseProgram(source);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "sunAngle"), scene.angle());
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "timeAngle"), scene.angle());
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "timeBrightness"), scene.angle() < .5f ? 1 : 0);
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "rainStrengthS"), scene.rain());
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "rainStrength"), scene.rain());
        GL46C.glUniform1f(GL46C.glGetUniformLocation(source, "rainFactor"), scene.rain());
    }

    private static void draw(int program) {
        GL46C.glUseProgram(program);
        GL46C.glClearColor(0, 0, 0, 0);
        GL46C.glClear(GL46C.GL_COLOR_BUFFER_BIT);
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, 3);
    }

    private static float[] readPixel() {
        return readPixel(8, 8);
    }

    private static float[] readPixel(int x, int y) {
        float[] pixel = new float[4];
        GL46C.glReadPixels(x, y, 1, 1, GL46C.GL_RGBA, GL46C.GL_FLOAT, pixel);
        for (float value : pixel) check(Float.isFinite(value), "光照不能产生 NaN/Inf");
        return pixel;
    }

    private static int[] textureBindings() {
        int active = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
        int[] result = new int[GL46C.glGetInteger(GL46C.GL_MAX_TEXTURE_IMAGE_UNITS) * 2];
        for (int unit = 0; unit < result.length / 2; unit++) {
            GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
            result[unit * 2] = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
            result[unit * 2 + 1] = GL46C.glGetIntegeri(GL46C.GL_SAMPLER_BINDING, unit);
        }
        GL46C.glActiveTexture(active);
        return result;
    }

    private static void setShadowDepth(int source, String name, int texture, float depth) {
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + GL46C.glGetUniformi(source, GL46C.glGetUniformLocation(source, name)));
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
        GL46C.glTexSubImage2D(GL46C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, new float[]{depth});
    }

    private static List<Uniform> activePackUniforms(int program, String source) {
        List<Uniform> uniforms = new ArrayList<>();
        var matcher = Pattern.compile("uniform\\s+(\\w+)\\s+([^;]+);").matcher(source);
        while (matcher.find()) {
            for (String variable : matcher.group(2).split(",")) {
                String name = variable.trim();
                if (GL46C.glGetUniformLocation(program, name) >= 0) {
                    uniforms.add(new Uniform(name.equals("ToonPackTexture") ? "texture" : name, matcher.group(1)));
                }
            }
        }
        return uniforms;
    }

    private static int uniformSource(List<Uniform> uniforms) {
        StringBuilder fragment = new StringBuilder("#version 330 core\nlayout(location=0) out vec4 c;\n");
        StringBuilder sink = new StringBuilder("void main(){ float s=0.0;\n");
        for (Uniform uniform : uniforms.reversed()) {
            fragment.append("uniform ").append(uniform.type()).append(' ').append(uniform.name()).append(";\n");
            String expression = switch (uniform.type()) {
                case "mat4" -> uniform.name() + "[0][0]";
                case "vec3", "ivec2" -> "float(" + uniform.name() + ".x)";
                case "sampler2D" -> "texture(" + uniform.name() + ",vec2(0.5)).r";
                case "sampler2DShadow" -> "texture(" + uniform.name() + ",vec3(0.5))";
                default -> "float(" + uniform.name() + ")";
            };
            sink.append("s+=").append(expression).append(";\n");
        }
        fragment.append(sink).append("c=vec4(s);}");
        int program = ShaderCompiler.compileRenderProgram("#version 330 core\nvoid main(){gl_Position=vec4(0.0);}", fragment.toString(), "光照 uniform 来源探针");
        check(program > 0, "来源程序编译");
        return program;
    }

    private static int colorTexture(int unit) {
        int id = GL46C.glGenTextures();
        GL46C.glActiveTexture(GL46C.GL_TEXTURE0 + unit);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, id);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA32F, 1, 1, 0, GL46C.GL_RGBA, GL46C.GL_FLOAT, new float[]{1,1,1,1});
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_NEAREST);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
        return id;
    }

    private static int depthTexture(int unit) {
        int id = colorTexture(unit);
        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_DEPTH_COMPONENT32F, 1, 1, 0, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, new float[]{1});
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_MODE, GL46C.GL_COMPARE_REF_TO_TEXTURE);
        GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_COMPARE_FUNC, GL46C.GL_LEQUAL);
        return id;
    }

    private static int buffer(int location, int size, float[] data) {
        int id = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, id);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, data, GL46C.GL_STATIC_DRAW);
        GL46C.glEnableVertexAttribArray(location);
        GL46C.glVertexAttribPointer(location, size, GL46C.GL_FLOAT, false, 0, 0);
        return id;
    }

    private static float[] identity() { return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}; }
    private static float luminance(float[] rgb) { return rgb[0]*.2126f + rgb[1]*.7152f + rgb[2]*.0722f; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
