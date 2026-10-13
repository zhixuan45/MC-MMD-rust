#version 330 core

in vec2 texCoord0;
in vec3 viewNormal;
in vec3 viewPos;
in vec3 viewLightDir;
in float eyeMask;

uniform sampler2D Sampler0;
uniform float AlphaCutoff;
uniform vec4 MaterialColor;
uniform vec3 MaterialSpecular;
uniform float MaterialSpecularPower;
uniform int AlphaPass; // 0: 全部（阴影），1: 实心，2: 半透明
#define TOON_MATERIAL_LIGHTING
uniform float EyeEmission;
uniform float EyeMaterialMask;

/* TOON_OUTPUT_DECLARATIONS */
/* TOON_OUTPUT_WRITER */
/* TOON_SURFACE_LIGHTING */

void main() {
    vec4 texColor = texture(Sampler0, texCoord0);
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
