#version 330 core

in vec2 texCoord0;
in vec3 viewNormal;
in vec3 viewPos;
in vec3 viewLightDir;
in float eyeMask;

uniform sampler2D Sampler0;
uniform float LightIntensity;
uniform int ToonLevels;
uniform float RimPower;
uniform float RimIntensity;
uniform vec3 ShadowColor;
uniform float SpecularPower;
uniform float SpecularIntensity;
uniform float AlphaCutoff;
uniform float GlobalAlpha;
uniform float EyeEmission;
uniform float EyeMaterialMask;
uniform bool UsePackLighting;
uniform vec2 ToonLightmap;
uniform bool ToonGenericEnvironment;
uniform bool ToonHasEnvironmentDirection;
uniform vec3 ToonEnvironmentDirection;
uniform vec3 ToonEnvironmentSkyTint;
uniform float ToonEnvironmentWeather;

/* TOON_OUTPUT_DECLARATIONS */
/* TOON_OUTPUT_WRITER */
float saturate(float value) {
    return clamp(value, 0.0, 1.0);
}

// 每档保留平坦区，仅在色阶交界按屏幕导数平滑。
float toonDiffuseRamp(float ndl) {
    float steps = float(clamp(ToonLevels, 2, 5) - 1);
    float width = clamp(fwidth(ndl), 0.002, 0.015);
    float ramp = 0.0;
    for (int i = 0; i < 4; ++i) {
        if (float(i) >= steps) break;
        float boundary = (float(i) + 0.5) / steps;
        ramp += smoothstep(boundary - width, boundary + width, ndl) / steps;
    }
    return ramp;
}

vec3 shadeToonSurface(vec3 albedo, vec3 n, vec3 light, vec3 p, vec3 illumination, float ramp) {
    vec3 tint = mix(clamp(ShadowColor, 0.0, 1.0), vec3(1.0), ramp);
    vec3 surface = albedo * tint;
    vec3 view = normalize(-p);
    vec3 halfVector = light + view;
    float specAngle = saturate(dot(n, halfVector / max(length(halfVector), 0.0001)));
    // 以半高宽切出高光色块，幂次只控制色块面积。
    float spec = pow(specAngle, max(SpecularPower, 1.0));
    float specWidth = clamp(fwidth(spec), 0.005, 0.05);
    float highlight = smoothstep(0.5 - specWidth, 0.5 + specWidth, spec);
    float litMask = smoothstep(0.0, 0.1, ramp);
    surface = mix(surface, vec3(1.0), saturate(highlight * SpecularIntensity * litMask));

    float rim = pow(1.0 - saturate(dot(view, n)), max(RimPower, 0.1));
    float rimWidth = clamp(fwidth(rim), 0.005, 0.05);
    float rimBand = smoothstep(0.5 - rimWidth, 0.5 + rimWidth, rim);
    surface = mix(surface, vec3(1.0), saturate(rimBand * RimIntensity));
    // 光照只乘一次；高光与边缘光在暗处同样衰减。
    return surface * max(illumination, vec3(0.0));
}

/* TOON_LIGHTING */

vec3 applyGenericEnvironment(vec3 raw, vec3 n, vec3 p, vec3 fallbackDirection) {
    vec3 light = ToonHasEnvironmentDirection ? ToonEnvironmentDirection : fallbackDirection;
    vec2 levels = clamp((ToonLightmap * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float blockWeight = levels.x * levels.x;
    float skyWeight = levels.y * levels.y;
    float total = max(blockWeight + skyWeight, 0.0001);
    vec3 tint = (ToonEnvironmentSkyTint * skyWeight * ToonEnvironmentWeather
              + vec3(1.0, 0.82, 0.65) * blockWeight) / total;
    // 没有有效光照等级时沿用场景的最低亮度，不能把 GUI 或暗处置为未定义颜色。
    if (blockWeight + skyWeight < 0.0001) tint = vec3(1.0);
    return shadeToonSurface(raw, n, light, p, tint * max(LightIntensity, 0.0),
            toonDiffuseRamp(saturate(dot(n, light))));
}

void main() {
    vec4 texColor = texture(Sampler0, texCoord0);
    if (texColor.a < AlphaCutoff) {
        discard;
    }

    vec3 albedo = texColor.rgb;
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
    float finalAlpha = texColor.a * clamp(GlobalAlpha, 0.0, 1.0);
    if (finalAlpha < 0.001) {
        discard;
    }

    writeToonOutputs(finalColor, albedo, normal, finalAlpha);
}
