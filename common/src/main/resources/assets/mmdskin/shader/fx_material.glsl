// Ray 材质参数的宿主实现；不使用原 MME 的 G-buffer 编码和渲染脚本。
uniform bool FxMaterialEnabled;
uniform int FxSources[7];
uniform vec4 FxValues[7];
uniform ivec4 FxOptions[7]; // UV flip、type、swizzle、scale
uniform vec2 FxRepeat[7];
uniform float FxAlpha;
uniform sampler2D FxMap0;
uniform sampler2D FxMap1;
uniform sampler2D FxMap2;
uniform sampler2D FxMap3;
uniform sampler2D FxMap4;
uniform sampler2D FxMap5;
uniform sampler2D FxMap6;

vec4 mmdFxSample(int slot, vec2 uv) {
    int flip = FxOptions[slot].x;
    if (flip == 1 || flip == 3) uv.x = 1.0 - uv.x;
    if (flip == 2 || flip == 3) uv.y = 1.0 - uv.y;
    uv *= FxRepeat[slot];
    if (FxSources[slot] == 0) return FxValues[slot];
    if (FxSources[slot] == 3) {
#ifdef MMD_BATCH_MATERIALS
        return mmdDiffuse(uv);
#else
        return texture(Sampler0, uv);
#endif
    }
    if (slot == 0) return texture(FxMap0, uv);
    if (slot == 1) return texture(FxMap1, uv);
    if (slot == 2) return texture(FxMap2, uv);
    if (slot == 3) return texture(FxMap3, uv);
    if (slot == 4) return texture(FxMap4, uv);
    if (slot == 5) return texture(FxMap5, uv);
    return texture(FxMap6, uv);
}

float mmdFxScalar(int slot, vec2 uv) {
    if (FxSources[slot] == 0) return clamp(FxValues[slot].x, 0.0, 1.0);
    vec4 sampled = mmdFxSample(slot, uv);
    float result = sampled[FxOptions[slot].z];
    if (slot == 2) {
        if (FxOptions[slot].y == 1) result = 1.0 - sqrt(max(result, 0.0));
        if (FxOptions[slot].y == 2) result = 1.0 - result;
    }
    if (slot == 5 && FxOptions[slot].y == 1) result = pow(max(result, 0.0), 2.2);
    if (FxSources[slot] != 0) {
        if (FxOptions[slot].w == 1) result *= FxValues[slot].x;
        if (FxOptions[slot].w == 2) result = pow(max(result, 0.00001), FxValues[slot].x);
    }
    return clamp(result, 0.0, 1.0);
}

vec3 mmdFxAlbedo(vec3 original, vec2 uv) {
    if (!FxMaterialEnabled) return original;
    vec3 color = mmdFxSample(0, uv).rgb;
    if (FxSources[0] != 0) {
        if (FxOptions[0].w == 1) color *= FxValues[0].rgb;
        if (FxOptions[0].w == 2) color = pow(max(color, vec3(0.00001)), FxValues[0].rgb);
    }
    return max(color * MaterialColor.rgb, vec3(0.0));
}

vec3 mmdFxNormal(vec3 n, vec3 p, vec2 uv) {
    if (!FxMaterialEnabled || FxSources[1] == 0) return n;
    vec3 tangent = mmdFxSample(1, uv).xyz * 2.0 - 1.0;
    if (FxOptions[1].y == 1) tangent.z = sqrt(max(0.0, 1.0 - dot(tangent.xy, tangent.xy)));
    tangent.xy *= FxValues[1].x;
    tangent /= max(length(tangent), 0.00001);
    // 从实际蒙皮后的位置和 UV 构造切线基；CPU/GPU 路径无需新增切线顶点属性。
    vec3 dp1 = dFdx(p), dp2 = dFdy(p);
    vec2 duv1 = dFdx(uv), duv2 = dFdy(uv);
    vec3 q1 = cross(dp2, n), q2 = cross(n, dp1);
    vec3 t = q1 * duv1.x + q2 * duv2.x;
    vec3 b = q1 * duv1.y + q2 * duv2.y;
    float magnitude = max(dot(t, t), dot(b, b));
    if (magnitude < 1e-16) return n;
    float scale = inversesqrt(magnitude);
    vec3 result = mat3(t * scale, b * scale, n) * tangent;
    return length(result) > 0.00001 ? normalize(result) : n;
}

vec3 mmdFxMaterialLight(vec3 lit, vec3 albedo, vec3 n, vec3 p, vec3 light, vec2 uv, vec3 illumination) {
    if (!FxMaterialEnabled) return lit;
    float smoothness = mmdFxScalar(2, uv);
    float metalness = mmdFxScalar(3, uv);
    float ao = mmdFxScalar(5, uv);
    vec3 specular = clamp(mmdFxSample(4, uv).rgb, 0.0, 1.0);
    if (FxSources[4] != 0 && FxOptions[4].w == 1) specular *= FxValues[4].rgb;
    if (FxSources[4] != 0 && FxOptions[4].w == 2) specular = pow(max(specular, vec3(0.00001)), FxValues[4].rgb);
    vec3 v = -p / max(length(p), 0.00001);
    vec3 h = light + v;
    h /= max(length(h), 0.00001);
    float nv = max(dot(n, v), 0.001), nl = max(dot(n, light), 0.0), nh = max(dot(n, h), 0.0);
    float roughness = max(1.0 - smoothness, 0.06);
    float a = roughness * roughness, a2 = a * a;
    float denominator = nh * nh * (a2 - 1.0) + 1.0;
    float distribution = a2 / max(3.14159265 * denominator * denominator, 0.00001);
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
    float geometry = (nv / (nv * (1.0 - k) + k)) * (nl / max(nl * (1.0 - k) + k, 0.00001));
    vec3 base = pow(max(albedo, vec3(0.0)), vec3(2.2));
    vec3 f0 = mix(specular * 0.08, base, metalness);
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - max(dot(v, h), 0.0), 5.0);
    vec3 reflection = distribution * geometry * fresnel / max(4.0 * nv * max(nl, 0.001), 0.0001);
    vec3 diffuse = pow(max(lit, vec3(0.0)), vec3(2.2)) * (1.0 - metalness) * ao;
    vec3 emission = mmdFxSample(6, uv).rgb;
    if (FxSources[6] != 0) emission *= FxValues[6].rgb;
    return pow(max(diffuse + reflection * nl * max(illumination, vec3(0.0)) + emission, vec3(0.0)), vec3(1.0 / 2.2));
}
