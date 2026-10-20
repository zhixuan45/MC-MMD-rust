#version 330 core

in vec3 viewNormal;
in vec3 viewPos;
in vec3 surfaceViewPos;
in vec3 viewLightDir;
in vec2 texCoord0;

uniform vec3 OutlineColor;
uniform float OutlineAlpha;
uniform mat4 ProjMat;
uniform sampler2D Sampler0;
uniform bool UseTextureAlpha;
uniform float AlphaCutoff;
uniform bool Premultiplied;
uniform bool OcclusionPass;
uniform bool FollowLighting;
uniform bool DisableTransparency;

/* TOON_OUTPUT_DECLARATIONS */
/* TOON_OUTPUT_WRITER */
/* TOON_SURFACE_LIGHTING */

void main() {
    // 纹理只提供遮罩，描边颜色仍然独立。
    float coverage = UseTextureAlpha ? texture(Sampler0, texCoord0).a : 1.0;
    if (coverage < AlphaCutoff) discard;
    if (OcclusionPass) {
        float alpha = DisableTransparency ? 1.0 : clamp(coverage * OutlineAlpha, 0.0, 1.0);
        // Four subpixels in the private 2x target approximate alpha coverage, not an opaque hair sheet.
        ivec2 p = ivec2(gl_FragCoord.xy) & ivec2(1);
        int rank = p.y == 0 ? p.x * 2 : 3 - p.x * 2;
        if (alpha <= (float(rank) + 0.5) * 0.25) discard;
        outlineColor = vec4(0.0);
        return;
    }
    if (DisableTransparency) coverage = 1.0;
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
    if (FollowLighting) {
        // 使用原表面的位置采样阴影，避免外扩外壳造成光照漏边。
        vec3 lightDir = normalize(viewLightDir);
        if (UsePackLighting) {
            finalOutline = applyPackLighting(OutlineColor, normal, surfaceViewPos);
        } else if (ToonGenericEnvironment) {
            finalOutline = applyGenericEnvironment(OutlineColor, normal, surfaceViewPos, lightDir);
        } else {
            finalOutline = shadeToonSurface(OutlineColor, normal, lightDir, surfaceViewPos,
                    vec3(max(LightIntensity, 0.0)), toonDiffuseRamp(saturate(dot(normal, lightDir))));
        }
    }

    // OutlineAlpha 已包含全局透明度，避免在描边中重复相乘。
    // 仅取消材质透明度，轮廓边缘的覆盖率仍保留。
    float materialAlpha = DisableTransparency && OutlineAlpha >= 0.001 ? 1.0 : OutlineAlpha;
    float finalAlpha = materialAlpha * edge * coverage;
    if (finalAlpha < 0.001) {
        discard;
    }

    writeToonOutputs(finalOutline, finalOutline, normal, finalAlpha);
}
