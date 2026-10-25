#version 330 core
uniform float Time;
in vec3 veilPosition;
in vec3 viewPosition;
in vec3 viewNormal;
layout(location = 0) out vec4 fragColor;
void main() {
    vec3 p = normalize(veilPosition);
    float longitude = atan(p.z, p.x);
    // 周向周期必须是整数，避免 atan 接缝处水纹断开。
    float wave = sin(p.y * 22.0 + sin(longitude * 4.0 + Time * 0.8) * 1.6 - Time * 2.2);
    float ripple = pow(0.5 + 0.5 * wave, 9.0);
    float caustic = pow(0.5 + 0.5 * sin(longitude * 8.0 + p.y * 15.0 + Time * 1.1), 18.0);
    float rim = pow(1.0 - abs(dot(normalize(viewNormal), normalize(-viewPosition))), 2.0);
    // 按视线轮廓渐透明，避免从正下方观察时落在低透明度的南极。
    float edge = smoothstep(0.35, 1.0, rim);
    vec3 water = mix(vec3(0.08, 0.46, 0.64), vec3(0.43, 0.84, 0.94), rim * 0.65);
    water = mix(water, vec3(0.77, 0.97, 1.0), clamp(ripple * 0.6 + caustic * 0.28, 0.0, 1.0));
    float alpha = mix(0.82, 0.32, edge) + ripple * 0.10 + rim * 0.07;
    fragColor = vec4(water, min(alpha, 0.94));
}
