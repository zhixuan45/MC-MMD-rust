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
uniform bool AutoMaterialColor;
uniform vec3 MaterialDiffuse;
uniform bool OutlineSkinMaterial;
uniform int OutlineRegion; // 0: 全部；1: 主体覆盖；2: 外描边；3: 内描边
uniform sampler2D OutlineBodyMask;
uniform ivec2 OutlineMaskOrigin;

/* TOON_OUTPUT_DECLARATIONS */
/* TOON_OUTPUT_WRITER */
/* TOON_SURFACE_LIGHTING */

vec3 calculateAutoEdgeColor(vec3 surfaceColor) {
    // 各通道同比例压暗，保留采样色相，避免浅色被全局描边色染灰。
    return clamp(surfaceColor * 0.65, 0.0, 1.0);
}

void main() {
    bool flippedSkinTriangle = false;
    if (OutlineSkinMaterial && OutlineRegion != 1 && !OcclusionPass) {
        // 皮肤外扩可能翻转嘴角的小三角形。检查外扩前的屏幕绕序，剔除这些假背面。
        vec4 originalClip = ProjMat * vec4(surfaceViewPos, 1.0);
        vec2 originalNdc = originalClip.xy / max(originalClip.w, 0.000001);
        vec2 dx = dFdx(originalNdc);
        vec2 dy = dFdy(originalNdc);
        flippedSkinTriangle = originalClip.w > 0.0 && dx.x * dy.y - dx.y * dy.x < 0.0;
    }
    vec4 texSample = UseTextureAlpha ? texture(Sampler0, texCoord0) : vec4(1.0);
    float coverage = UseTextureAlpha ? texSample.a : 1.0;
    if (flippedSkinTriangle || coverage < AlphaCutoff) discard;
    if (OutlineRegion == 1) {
        outlineColor = vec4(1.0);
        return;
    }
    if (OutlineRegion >= 2) {
        bool inside = texelFetch(OutlineBodyMask, ivec2(gl_FragCoord.xy) - OutlineMaskOrigin, 0).r > 0.5;
        if (inside != (OutlineRegion == 3)) discard;
    }
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

    vec3 baseColor = OutlineColor;
    if (AutoMaterialColor) {
        vec3 surfaceRgb = MaterialDiffuse * texSample.rgb;
        baseColor = calculateAutoEdgeColor(surfaceRgb);
        if (OutlineSkinMaterial) {
            baseColor *= vec3(0.84, 0.60, 0.58);
        }
    }

    vec3 finalOutline = baseColor;
    if (FollowLighting) {
        // 使用原表面的位置采样阴影，避免外扩外壳造成光照漏边。
        vec3 lightDir = normalize(viewLightDir);
        if (UsePackLighting) {
            finalOutline = applyPackLighting(baseColor, normal, surfaceViewPos);
        } else if (ToonGenericEnvironment) {
            finalOutline = applyGenericEnvironment(baseColor, normal, surfaceViewPos, lightDir);
        } else {
            finalOutline = shadeToonSurface(baseColor, normal, lightDir, surfaceViewPos,
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
