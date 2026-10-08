#version 330 core

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 Normal;

uniform mat4 ProjMat;
uniform mat4 ModelViewMat;
uniform float OutlineWidth;

out vec3 viewNormal;
out vec3 viewPos;

void main() {
    mat3 normalMatrix = mat3(ModelViewMat);
    vec3 transformedNormal = normalize(normalMatrix * Normal);
    vec4 vPos = ModelViewMat * vec4(Position, 1.0);

    bool orthographic = abs(ProjMat[2][3]) < 0.000001 && abs(ProjMat[3][3] - 1.0) < 0.000001;
    float expansion;
    if (orthographic) {
        // GUI 的 -11000 是图层深度；宽度已由独立 pass 按人物显示缩放换算。
        expansion = OutlineWidth;
    } else {
        float viewDepth = max(-vPos.z, 0.5);
        float outlineScale = mix(0.8, 1.2, clamp((viewDepth - 1.0) / 12.0, 0.0, 1.0));
        float distanceFade = clamp(1.0 - (viewDepth - 25.0) / 15.0, 0.0, 1.0);
        expansion = OutlineWidth * outlineScale * distanceFade;
    }
    vPos.xyz += transformedNormal * expansion;

    gl_Position = ProjMat * vPos;
    viewNormal = transformedNormal;
    viewPos = vPos.xyz;
}
