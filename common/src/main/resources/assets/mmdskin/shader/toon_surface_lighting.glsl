// 主体与描边共用照明；描边单独关闭高光与边缘光。
uniform float LightIntensity;
uniform int ToonLevels;
uniform float ToonTransitionSoftness;
uniform float ToonShaderFlatness;
uniform float RimPower;
uniform float RimIntensity;
uniform vec3 ShadowColor;
uniform float SpecularPower;
uniform float SpecularIntensity;
uniform bool UsePackLighting;
uniform vec2 ToonLightmap;
uniform bool ToonGenericEnvironment;
uniform bool ToonHasEnvironmentDirection;
uniform vec3 ToonEnvironmentDirection;
uniform vec3 ToonEnvironmentSkyTint;
uniform float ToonEnvironmentWeather;

#ifdef TOON_MATERIAL_LIGHTING
// 复用本片段照明，供 SPA 加色衰减，避免重复阴影采样。
vec3 toonSurfaceIllumination = vec3(1.0);
#endif

float saturate(float value) {
    return clamp(value, 0.0, 1.0);
}

float toonShaderFlatnessAmount() {
    return (UsePackLighting || ToonGenericEnvironment) ? saturate(ToonShaderFlatness) : 0.0;
}

// 100% 内保留色块平坦区，更高柔和度继续扩宽交界。
float toonDiffuseRamp(float ndl) {
#ifdef MMD_FX_RAY
    if (mmdFxRayEnabled()) return ndl;
#endif
    float steps = float(clamp(ToonLevels, 2, 5) - 1);
    float antialiasWidth = clamp(fwidth(ndl), 0.002, 0.015);
    float softness = clamp(ToonTransitionSoftness, 0.0, 2.0) * (1.0 - toonShaderFlatnessAmount());
    float width = max(antialiasWidth, softness * 0.45 / steps);
    float ramp = 0.0;
    for (int i = 0; i < 4; ++i) {
        if (float(i) >= steps) break;
        float boundary = (float(i) + 0.5) / steps;
        // 扩宽越过照度端点时归一化，保留最暗/最亮颜色。
        float start = smoothstep(boundary - width, boundary + width, 0.0);
        float end = smoothstep(boundary - width, boundary + width, 1.0);
        float value = smoothstep(boundary - width, boundary + width, ndl);
        ramp += (value - start) / (end - start) / steps;
    }
    return ramp;
}

vec3 shadeToonSurface(vec3 albedo, vec3 n, vec3 light, vec3 p, vec3 illumination, float ramp) {
#ifdef MMD_FX_RAY
    if (mmdFxRayEnabled()) {
        toonSurfaceIllumination = max(illumination, vec3(0.0));
        return albedo * mix(clamp(ShadowColor, 0.0, 1.0), vec3(1.0), ramp) * toonSurfaceIllumination;
    }
#endif
    vec3 tint = mix(clamp(ShadowColor, 0.0, 1.0), vec3(1.0), ramp);
    vec3 surface = albedo * tint;
    vec3 view = normalize(-p);
    vec3 halfVector = light + view;
    float specAngle = saturate(dot(n, halfVector / max(length(halfVector), 0.0001)));
    // 以半高宽切出高光色块，幂次只控制色块面积。
    float power = max(SpecularPower, 1.0);
#ifdef TOON_MATERIAL_LIGHTING
    power = max(MaterialSpecularPower * power / 96.0, 1.0);
#endif
    float spec = pow(specAngle, power);
    float specWidth = clamp(fwidth(spec), 0.005, 0.05);
    float highlight = smoothstep(0.5 - specWidth, 0.5 + specWidth, spec);
    float litMask = smoothstep(0.0, 0.1, ramp);
    float detailStrength = 1.0 - toonShaderFlatnessAmount();
#ifdef TOON_MATERIAL_LIGHTING
    surface = max(vec3(0.0), surface + MaterialSpecular * highlight * SpecularIntensity * litMask * detailStrength);
#else
    surface = mix(surface, vec3(1.0), saturate(highlight * SpecularIntensity * litMask * detailStrength));
#endif

    float rim = pow(1.0 - saturate(dot(view, n)), max(RimPower, 0.1));
    float rimWidth = clamp(fwidth(rim), 0.005, 0.05);
    float rimBand = smoothstep(0.5 - rimWidth, 0.5 + rimWidth, rim);
#ifdef TOON_MATERIAL_LIGHTING
    surface = mix(surface, MaterialColor.rgb, saturate(rimBand * RimIntensity * detailStrength));
#else
    surface = mix(surface, vec3(1.0), saturate(rimBand * RimIntensity * detailStrength));
#endif
    // 光照只乘一次；高光与边缘光在暗处同样衰减。
    vec3 surfaceIllumination = max(illumination, vec3(0.0));
#ifdef TOON_MATERIAL_LIGHTING
    toonSurfaceIllumination = surfaceIllumination;
#endif
    return surface * surfaceIllumination;
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

