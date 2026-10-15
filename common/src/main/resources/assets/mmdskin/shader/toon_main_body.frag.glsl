#version 330 core

in vec2 texCoord0;
in vec3 viewNormal;
in vec3 viewPos;
in vec3 viewLightDir;
in float eyeMask;

uniform sampler2D Sampler0;
uniform sampler2D SphereTexture;
uniform int SphereMode;
uniform vec4 SphereMul;
uniform vec4 SphereAdd;
uniform float AlphaCutoff;
uniform vec4 MaterialColor;
uniform vec3 MaterialSpecular;
uniform float MaterialSpecularPower;
uniform int AlphaPass; // 0: 全部（阴影），1: 实心，2: 半透明
#define TOON_MATERIAL_LIGHTING
uniform float EyeEmission;
uniform float EyeMaterialMask;

/* TOON_BATCH_MATERIALS */
/* TOON_OUTPUT_DECLARATIONS */
/* TOON_OUTPUT_WRITER */
/* TOON_SURFACE_LIGHTING */

void main() {
#ifdef MMD_BATCH_MATERIALS
    mmdLoadMaterial();
    if (MmdBatchEnabled) mmdPrepareSphere(normalize(viewNormal));
    vec4 texColor = mmdDiffuse(texCoord0);
    if (mmdCull) discard;
#else
    vec4 texColor = texture(Sampler0, texCoord0);
#endif
    float finalAlpha = texColor.a * MaterialColor.a;
    if (finalAlpha < AlphaCutoff) {
        discard;
    }

    if (AlphaPass == 1 && finalAlpha < 0.999) discard;
    if (AlphaPass == 2 && finalAlpha >= 0.999) discard;

    vec3 albedo = texColor.rgb * MaterialColor.rgb;
    vec3 normal = normalize(viewNormal);
    vec3 lightDir = normalize(viewLightDir);
    float ramp = toonDiffuseRamp(saturate(dot(normal, lightDir)));
    vec3 finalColor;
    if (UsePackLighting) {
        finalColor = applyPackLighting(albedo, normal, viewPos);
    } else if (ToonGenericEnvironment) {
        finalColor = applyGenericEnvironment(albedo, normal, viewPos, lightDir);
    } else {
        finalColor = shadeToonSurface(albedo, normal, lightDir, viewPos,
                vec3(max(LightIntensity, 0.0)), ramp);
    }
    if (SphereMode == 1 || SphereMode == 2) {
        // 纹理解码已经翻转 Y；视空间法线映射让球面效果随视角变化。
#ifdef MMD_BATCH_MATERIALS
        vec2 halfTexel = 0.5 / vec2(mmdSphereSize());
#else
        vec2 halfTexel = 0.5 / vec2(textureSize(SphereTexture, 0));
#endif
        vec2 sphereUv = clamp(normal.xy * 0.5 + 0.5, halfTexel, vec2(1.0) - halfTexel);
#ifdef MMD_BATCH_MATERIALS
        vec3 sphereColor = mmdSphere(sphereUv).rgb;
#else
        vec3 sphereColor = texture(SphereTexture, sphereUv).rgb;
#endif
        sphereColor = mix(vec3(1.0), sphereColor * SphereMul.rgb, SphereMul.a);
        sphereColor = clamp(sphereColor + (sphereColor - vec3(1.0)) * SphereAdd.a, 0.0, 1.0) + SphereAdd.rgb;
        // SPA 可叠加在黑色主贴图上，贴图 alpha 不改变 PMX 表面透明度。
        finalColor = SphereMode == 1 ? finalColor * sphereColor : finalColor + sphereColor;
    }
    // 合并眼瞳遮罩与线性空间发光。
    float emissionMask = EyeMaterialMask < 0.0 ? 0.0 : max(saturate(eyeMask), saturate(EyeMaterialMask));
    float emission = emissionMask * clamp(EyeEmission, 0.0, 3.0);
    if (emission > 0.0) {
        // 发光在线性空间相加，避免被输出的 gamma 转换再次指数放大，影响自动曝光。
        vec3 linearColor = pow(max(finalColor, vec3(0.0)), vec3(2.2));
        linearColor += pow(max(albedo, vec3(0.0)), vec3(2.2)) * emission;
        finalColor = pow(linearColor, vec3(1.0 / 2.2));
    }
    if (finalAlpha < 0.001) {
        discard;
    }

    writeToonOutputs(finalColor, albedo, normal, finalAlpha);
}
