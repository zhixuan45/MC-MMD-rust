#version 330 core
uniform float Time;
uniform float Opacity;
uniform int VeilStyle;
uniform float LightIntensity;
uniform vec3 TintColor;
uniform int OutputEncoding;
in vec3 veilPosition;
in vec3 viewPosition;
in vec3 viewNormal;
layout(location = 0) out vec4 fragColor;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// 周向使用整数格数，确保 atan(p.z, p.x) 经度接缝处气泡完整连续。
float bubbleField(float longitude, float height, float time, float cols, float rows, float flowSpeed) {
    float u = (longitude / 6.2831853 + 0.5) * cols;
    float v = height * rows + time * flowSpeed;
    vec2 cell = floor(vec2(u, v));
    vec2 local = fract(vec2(u, v)) - 0.5;
    float wrappedCol = mod(cell.x, cols);
    float rnd = hash21(vec2(wrappedCol, cell.y));
    if (rnd < 0.62) return 0.0;
    vec2 jitter = vec2(hash21(vec2(wrappedCol + 7.1, cell.y + 3.7)) - 0.5,
                       hash21(vec2(wrappedCol + 19.3, cell.y + 11.2)) - 0.5) * 0.42;
    float d = length(local - jitter);
    float radius = mix(0.11, 0.22, fract(rnd * 13.7));
    float ring = smoothstep(radius, radius * 0.45, d) * smoothstep(radius * 0.10, radius * 0.78, d);
    float core = smoothstep(radius * 0.72, 0.0, d) * 0.35;
    return ring * 0.75 + core;
}

void main() {
    vec3 p = normalize(veilPosition);
    float horiz = abs(p.x) + abs(p.z);
    float longitude = atan(p.z, horiz < 1e-5 ? 1.0 : p.x);
    float ndotv = abs(dot(normalize(viewNormal), normalize(-viewPosition)));
    float rim = 1.0 - ndotv;

    // 边界扰动与外缘软羽化：核心骨盆区域在满强度下实遮，外轮廓柔和淡出至 0。
    float edgeNoise = sin(longitude * 4.0 + p.y * 6.0 + Time * 1.4) * 0.5
                    + cos(longitude * 7.0 - p.y * 5.0 - Time * 1.1) * 0.5;
    float edgeMask = smoothstep(0.05, 0.36, ndotv + edgeNoise * 0.08);
    float waistFade = 1.0 - smoothstep(0.52, 0.96, p.y);
    float shapeMask = edgeMask * mix(0.45, 1.0, waistFade);

    float lightScale = mix(0.28, 1.0, clamp(LightIntensity, 0.0, 1.0));
    vec3 color;
    float baseAlpha;
    float maxAlpha;

    if (VeilStyle == 1) {
        // 鞘翅滑翔：沿 -p.y 尾流方向高速掠过的青白气流风纹。
        float warp = sin(longitude * 3.0 + p.y * 5.0 + Time * 2.6) * 0.65;
        float streamA = pow(0.5 + 0.5 * sin(longitude * 6.0 + warp * 1.8 + p.y * 4.0 + Time * 5.4), 5.0);
        float streamB = pow(0.5 + 0.5 * sin(longitude * 11.0 - warp * 1.4 + p.y * 8.0 + Time * 7.2), 10.0);
        float mist = 0.5 + 0.5 * sin(p.y * 9.0 + Time * 4.2 + sin(longitude * 4.0 - Time * 1.7));

        vec3 baseWind = mix(TintColor * 0.78, TintColor, mist * 0.55 + rim * 0.35);
        vec3 ribbonHighlight = mix(TintColor, vec3(0.95, 0.99, 1.0), 0.72);
        color = mix(baseWind, ribbonHighlight, clamp(streamA * 0.48 + streamB * 0.52, 0.0, 1.0));
        color *= lightScale;
        baseAlpha = 0.92 + streamA * 0.05 + streamB * 0.03 + mist * 0.02;
        maxAlpha = 0.98;
    } else {
        // 游泳模式：双层域扭曲水波纹 + 焦散亮丝 + 微弱向后漂移气泡。
        float warp = sin(longitude * 4.0 + Time * 0.9 + p.y * 4.5) * 1.35;
        float waveA = sin(p.y * 18.0 + warp + Time * 2.4);
        float waveB = sin(p.y * 11.0 - longitude * 5.0 + Time * 1.8);
        float ripple = pow(0.5 + 0.5 * (waveA * 0.65 + waveB * 0.35), 6.0);
        float caustic = pow(0.5 + 0.5 * sin(longitude * 8.0 + p.y * 15.0 + warp * 0.7 + Time * 1.6), 14.0);
        float bubbles = bubbleField(longitude, p.y, Time, 12.0, 5.0, 0.85) * 0.55
                      + bubbleField(longitude + 1.3, p.y, Time, 18.0, 7.0, 1.25) * 0.35;

        vec3 deepWater = TintColor * vec3(0.42, 0.68, 0.82);
        vec3 shallowWater = mix(TintColor, vec3(0.56, 0.90, 0.98), 0.45);
        color = mix(deepWater, shallowWater, clamp(rim * 0.55 + (0.5 + 0.5 * waveB) * 0.35, 0.0, 1.0));
        color = mix(color, vec3(0.84, 0.98, 1.0), clamp(ripple * 0.48 + caustic * 0.34 + bubbles * 0.42, 0.0, 1.0));
        color *= lightScale;
        baseAlpha = 0.94 + ripple * 0.04 + caustic * 0.02 + bubbles * 0.03;
        maxAlpha = 0.985;
    }

    // 对齐当前 Iris 光影的 colortex0 颜色空间：0=gamma(原版/Comp5), 1=sqrt-linear(BSL), 2=linear(Comp4/BSL-linear)。
    vec3 safeColor = max(color, vec3(0.0));
    vec3 encodedColor = OutputEncoding == 1 ? pow(safeColor, vec3(1.1))
            : OutputEncoding == 2 ? pow(safeColor, vec3(2.2)) : safeColor;
    float finalAlpha = clamp(baseAlpha * shapeMask * clamp(Opacity, 0.0, 1.0), 0.0, maxAlpha);
    fragColor = vec4(encodedColor, finalAlpha);
}
