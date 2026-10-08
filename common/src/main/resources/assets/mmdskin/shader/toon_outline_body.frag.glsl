#version 330 core

in vec3 viewNormal;
in vec3 viewPos;

uniform vec3 OutlineColor;
uniform float OutlineAlpha;
uniform mat4 ProjMat;

/* TOON_OUTPUT_DECLARATIONS */
/* TOON_OUTPUT_WRITER */

void main() {
    vec3 normal = normalize(viewNormal);
    // 正交投影的视线不随屏幕位置与 GUI 图层深度改变。
    bool orthographic = abs(ProjMat[2][3]) < 0.000001 && abs(ProjMat[3][3] - 1.0) < 0.000001;
    vec3 viewDir = orthographic ? vec3(0.0, 0.0, 1.0) : normalize(-viewPos);
    float facing = dot(normal, viewDir);

    float silhouette = 1.0 - clamp(facing, 0.0, 1.0);
    float edge = smoothstep(0.08, 0.45, silhouette);

    if (edge < 0.01) {
        discard;
    }

    vec3 finalOutline = OutlineColor;

    // OutlineAlpha 已包含全局透明度，避免在描边中重复相乘。
    float finalAlpha = OutlineAlpha * edge;
    if (finalAlpha < 0.001) {
        discard;
    }

    writeToonOutputs(finalOutline, finalOutline, normal, finalAlpha);
}
