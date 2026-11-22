#version 330 core
layout(location = 0) in vec3 Position;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform float Time;
uniform int VeilStyle;
out vec3 veilPosition;
out vec3 viewPosition;
out vec3 viewNormal;
void main() {
    vec3 p = normalize(Position);
    float horiz = abs(p.x) + abs(p.z);
    float lon = atan(p.z, horiz < 1e-5 ? 1.0 : p.x);
    // 周向周期保持整数且在两极收敛到 0，避免 atan(0,0) NaN 或极点缝隙。
    float poleDamp = sqrt(max(0.0, 1.0 - p.y * p.y));
    float speed = VeilStyle == 1 ? 5.2 : 2.1;
    float sway = sin(p.y * 8.0 + Time * speed + sin(lon * 3.0 - Time * 1.1) * 0.9) * 0.022 * poleDamp;
    vec3 displaced = Position * (1.0 + sway);
    veilPosition = Position;
    vec4 view = ModelViewMat * vec4(displaced, 1.0);
    viewPosition = view.xyz;
    viewNormal = transpose(inverse(mat3(ModelViewMat))) * p;
    gl_Position = ProjMat * view;
}
