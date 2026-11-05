package com.shiroha.mmdskin.render.shader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Synthetic shadow contracts run through the real adapters, without Minecraft dependencies. */
public final class ToonRotationFixtures {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        String vertex = """
                void main() {
                    float timeAngleM = timeAngle;
                    const vec2 sunRotationData = vec2(1.0, 0.0);
                    sunVec = normalize(vec3(0.8, 0.6, 0.0));
                    upVec = normalize(vec3(0.0, 1.0, 0.0));
                    eastVec = normalize(vec3(1.0, 0.0, 0.0));
                }
                """;
        String inputs = """
                uniform bool TestNight;
                uniform mat4 gbufferModelViewInverse;
                varying vec2 texCoord, lmCoord;
                varying vec3 normal, sunVec, upVec, eastVec;
                varying vec4 color;
                vec3 lightVec = TestNight ? -sunVec : sunVec;
                """;
        String bsl = inputs + """
                vec3 ToWorld(vec3 p) { return p; }
                vec3 GetShadow(vec3 p, vec3 n, float nl, float s, float sky) { return vec3(TestVisibility); }
                void GetLighting(inout vec3 albedo, out vec3 shadow, vec3 viewPos, vec3 worldPos, vec3 normal,
                        vec2 lightmap, float smoothLighting, float NoL, float vanillaDiffuse,
                        float parallaxShadow, float emission, float subsurface, float basicSubsurface) {
                    if (NoL > 0.0 || basicSubsurface > 0.0) {
                        shadow = GetShadow(worldPos, normal, NoL, basicSubsurface, lightmap.y);
                    }
                    NoL = clamp(NoL * 1.01 - 0.01, 0.0, 1.0);
                    vec3 fullShadow = max(shadow * NoL, vec3(0.0));
                    albedo *= pow((vec3(0.15) + 0.85 * fullShadow) * TestEnergy, vec3(2.2));
                }
                void main() {}
                """;
        String complementary = inputs + """
                uniform float timeAngle;
                vec3 ScreenToView(vec3 p) { return p; }
                vec3 GetShadow(vec3 p, vec3 offset, float water, bool subsurface) { return vec3(TestVisibility); }
                void GetLighting(inout vec3 albedo, out vec3 shadow, out vec3 lightAlbedo, vec3 p, float len,
                        vec3 world, vec2 lm, float smoothLighting, float NdotL, float vanillaDiffuse,
                        float parallax, float emission, float subsurface, float basicSubsurface, float ao) {
                    shadow = vec3(0.0);
                    if ((NdotL > 0.0 || subsurface > 0.001)) {
                        float NdotLm = clamp(dot(normal, lightVec), 0.0, 1.0);
                        shadow = GetShadow(world, normal * NdotLm, 0.0, false);
                    }
                    vec3 fullShadow = shadow * max(NdotL, subsurface);
                    albedo *= pow((vec3(0.15) + 0.85 * fullShadow) * TestEnergy, vec3(2.2));
                    lightAlbedo = vec3(0.0);
                }
                void main() {}
                """;
        String modern = """
                uniform bool TestNight;
                uniform mat4 gbufferModelView, gbufferModelViewInverse;
                vec2 texCoord;
                vec2 lmCoord;
                vec3 normal, sunVec, upVec, northVec, eastVec;
                vec4 glColor;
                vec3 lightVec = TestNight ? -sunVec : sunVec;
                vec3 ViewToPlayer(vec3 p) { return p; }
                vec3 GetShadow(vec3 p, float lm, vec3 offset, int samples, bool water, vec3 playerPos) {
                    return vec3(TestVisibility);
                }
                void DoLighting(inout vec4 albedo, inout vec3 shadowMult, vec3 playerPos, vec3 p, float len,
                        vec3 n, vec3 face, float ao, vec3 worldNormal, vec2 lightmap,
                        bool a, bool b, bool c, bool d, int material, float emission, float subsurface, float e) {
                    float NdotLM = clamp(dot(n, lightVec), 0.0, 1.0);
                    float shadowTime = 1.0;
                    vec3 shadowPos = playerPos, offset = vec3(0.0);
                    int samples = 1;
                    shadowMult = vec3(0.0);
                    if (NdotLM > 0.0001) {
                        shadowMult = vec3(1.0);
                        shadowMult *= GetShadow(shadowPos, lightmap.y, offset, samples, false, playerPos);
                    }
                    shadowMult *= max(NdotLM * shadowTime, subsurface);
                    albedo.rgb *= (vec3(0.15) + 0.85 * shadowMult) * TestEnergy;
                }
                void main() {}
                """;
        Files.writeString(output.resolve("bsl.glsl"), BslToonLighting.create(bsl, vertex, false));
        Files.writeString(output.resolve("complementary4.glsl"), ComplementaryToonLighting.create(complementary, vertex));
        Files.writeString(output.resolve("complementary5.glsl"), ComplementaryToonLighting.createModern(modern, vertex));
        Files.writeString(output.resolve("vanilla.glsl"), """
                vec3 applyPackLighting(vec3 raw, vec3 n, vec3 p) {
                    return shadeToonSurface(raw, n, viewLightDir, p, vec3(TestEnergy),
                            toonDiffuseRamp(clamp(dot(n, viewLightDir), 0.0, 1.0)));
                }
                """);
    }
}
