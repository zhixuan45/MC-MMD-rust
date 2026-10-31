// Ray 材质参数的宿主实现；不使用原 MME 的 G-buffer 编码和渲染脚本。
uniform bool FxMaterialEnabled;
uniform float FxMaterialPower;
uniform int FxSources[10];
uniform vec4 FxValues[10];
uniform ivec4 FxOptions[10]; // UV flip、type、swizzle、scale
uniform vec2 FxRepeat[10];
uniform sampler2D FxMap0;
uniform sampler2D FxMap1;
uniform sampler2D FxMap2;
uniform sampler2D FxMap3;
uniform sampler2D FxMap4;
uniform sampler2D FxMap5;
uniform sampler2D FxMap6;
uniform sampler2D FxMap7;
uniform sampler2D FxMap8;
uniform sampler2D FxMap9;

bool mmdFxRayEnabled() {
#if defined(MMD_BATCH_MATERIALS) && defined(MMD_FX_COMBINED)
    if (MmdBatchEnabled) return mmdFxKind == 1;
#endif
#ifdef MMD_FX_COMBINED
    return FxMaterialEnabled && FxSelection > 0;
#else
    return FxMaterialEnabled;
#endif
}
int mmdFxSource(int slot) {
#if defined(MMD_BATCH_MATERIALS) && defined(MMD_FX_COMBINED)
    if (MmdBatchEnabled) return int(mmdFxChannels[slot].uvSource.z);
#endif
    return FxSources[slot];
}
vec4 mmdFxValue(int slot) {
#if defined(MMD_BATCH_MATERIALS) && defined(MMD_FX_COMBINED)
    if (MmdBatchEnabled) return mmdFxChannels[slot].value;
#endif
    return FxValues[slot];
}
ivec4 mmdFxOption(int slot) {
#if defined(MMD_BATCH_MATERIALS) && defined(MMD_FX_COMBINED)
    if (MmdBatchEnabled) return ivec4(mmdFxChannels[slot].options);
#endif
    return FxOptions[slot];
}
vec2 mmdFxLoop(int slot) {
#if defined(MMD_BATCH_MATERIALS) && defined(MMD_FX_COMBINED)
    if (MmdBatchEnabled) return mmdFxChannels[slot].uvSource.xy;
#endif
    return FxRepeat[slot];
}

vec4 mmdFxSample(int slot, vec2 uv) {
    int flip = mmdFxOption(slot).x;
    if (flip == 1 || flip == 3) uv.x = 1.0 - uv.x;
    if (flip == 2 || flip == 3) uv.y = 1.0 - uv.y;
    uv *= mmdFxLoop(slot);
    if (mmdFxSource(slot) == 0) return mmdFxValue(slot);
    if (mmdFxSource(slot) == 3) {
#ifdef MMD_BATCH_MATERIALS
        return mmdDiffuse(uv);
#else
        return texture(Sampler0, uv);
#endif
    }
#if defined(MMD_BATCH_MATERIALS) && defined(MMD_FX_COMBINED)
    if (MmdBatchEnabled) return mmdTexture(int(mmdFxChannels[slot].uvSource.w), uv, dFdx(uv), dFdy(uv));
#endif
    if (slot == 0) return texture(FxMap0, uv);
    if (slot == 1) return texture(FxMap1, uv);
    if (slot == 2) return texture(FxMap2, uv);
    if (slot == 3) return texture(FxMap3, uv);
    if (slot == 4) return texture(FxMap4, uv);
    if (slot == 5) return texture(FxMap5, uv);
    if (slot == 6) return texture(FxMap6, uv);
    if (slot == 7) return texture(FxMap7, uv);
    if (slot == 8) return texture(FxMap8, uv);
    return texture(FxMap9, uv);
}

float mmdFxAlpha(float original, float materialAlpha, vec2 uv) {
    if (!mmdFxRayEnabled() || mmdFxSource(9) != 1) return original;
    return clamp(mmdFxSample(9, uv)[mmdFxOption(9).z] * materialAlpha, 0.0, 1.0);
}

float mmdFxScalar(int slot, vec2 uv) {
    if (mmdFxSource(slot) == 0) return clamp(mmdFxValue(slot).x, 0.0, 1.0);
    float fxPower = FxMaterialPower;
#if defined(MMD_BATCH_MATERIALS) && defined(MMD_FX_COMBINED)
    if (MmdBatchEnabled) fxPower = mmdFxChannels[2].value.w;
#endif
    vec4 sampled = slot == 2 && mmdFxSource(slot) == 9
        ? vec4(1.0 - pow(max(0.0, 2.0 / (fxPower + 2.0)), 0.125))
        : mmdFxSample(slot, uv);
    float result = sampled[mmdFxOption(slot).z];
    if (slot == 2) {
        if (mmdFxOption(slot).y == 1) result = 1.0 - sqrt(max(result, 0.0));
        if (mmdFxOption(slot).y == 2) result = 1.0 - result;
    }
    if (slot == 5 && mmdFxOption(slot).y == 1) result = pow(max(result, 0.0), 2.2);
    if (mmdFxSource(slot) != 0) {
        if (mmdFxOption(slot).w == 1) result *= mmdFxValue(slot).x;
        if (mmdFxOption(slot).w == 2) result = pow(max(result, 0.00001), mmdFxValue(slot).x);
    }
    return clamp(result, 0.0, 1.0);
}

vec3 mmdFxAlbedo(vec3 original, vec2 uv) {
    if (!mmdFxRayEnabled()) return original;
    vec3 color = mmdFxSample(0, uv).rgb;
    if (mmdFxSource(0) != 0) {
        if (mmdFxOption(0).w == 1) color *= mmdFxValue(0).rgb;
        if (mmdFxOption(0).w == 2) color = pow(max(color, vec3(0.00001)), mmdFxValue(0).rgb);
    }
    color *= MaterialColor.rgb;
    int subMode = mmdFxOption(7).y;
    if (subMode != 0) {
        vec4 sub = mmdFxSample(7, uv);
        if (mmdFxSource(7) != 0 && mmdFxOption(7).w == 1) sub.rgb *= mmdFxValue(7).rgb;
        if (mmdFxSource(7) != 0 && mmdFxOption(7).w == 2) sub.rgb = pow(max(sub.rgb, vec3(0.00001)), mmdFxValue(7).rgb);
        if (subMode == 1) color *= sub.rgb;
        if (subMode == 2) color = pow(max(color, vec3(0.0)), sub.rgb);
        if (subMode == 3) color += sub.rgb;
        if (subMode == 5) color = mix(color, sub.rgb, sub.a);
        color = clamp(color, 0.0, 1.0);
    }
    return max(color, vec3(0.0));
}

vec3 mmdFxTangentNormal(int slot, vec2 uv) {
    vec3 tangent = mmdFxSample(slot, uv).xyz * 2.0 - 1.0;
    if (mmdFxOption(slot).y == 1) tangent.z = sqrt(max(0.0, 1.0 - dot(tangent.xy, tangent.xy)));
    tangent.xy *= mmdFxValue(slot).x;
    return tangent / max(length(tangent), 0.00001);
}
vec3 mmdFxNormal(vec3 n, vec3 p, vec2 uv) {
    if (!mmdFxRayEnabled() || (mmdFxSource(1) == 0 && mmdFxSource(8) == 0)) return n;
    vec3 tangent = mmdFxSource(1) == 0 ? vec3(0.0, 0.0, 1.0) : mmdFxTangentNormal(1, uv);
    if (mmdFxSource(8) != 0) {
        vec3 sub = mmdFxTangentNormal(8, uv);
        if (mmdFxSource(1) == 0) tangent = sub;
        else {
            vec3 base = tangent + vec3(0.0, 0.0, 1.0);
            vec3 detail = sub * vec3(-1.0, -1.0, 1.0);
            tangent = base * dot(base, detail) / max(base.z, 0.00001) - detail;
            tangent /= max(length(tangent), 0.00001);
        }
    }
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
    if (!mmdFxRayEnabled()) return lit;
    float smoothness = mmdFxScalar(2, uv);
    float metalness = mmdFxScalar(3, uv);
    float ao = mmdFxScalar(5, uv);
    vec3 specular = clamp(mmdFxSample(4, uv).rgb, 0.0, 1.0);
    if (mmdFxSource(4) != 0 && mmdFxOption(4).w == 1) specular *= mmdFxValue(4).rgb;
    if (mmdFxSource(4) != 0 && mmdFxOption(4).w == 2) specular = pow(max(specular, vec3(0.00001)), mmdFxValue(4).rgb);
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
    // Fresnel 反射占据的能量不再进入漫反射；场景照明仍由宿主提供。
    vec3 diffuse = pow(max(lit, vec3(0.0)), vec3(2.2)) * (1.0 - fresnel) * (1.0 - metalness) * ao;
    vec3 emission = mmdFxSample(6, uv).rgb;
    if (mmdFxSource(6) != 0) emission *= mmdFxValue(6).rgb;
    return pow(max(diffuse + reflection * nl * max(illumination, vec3(0.0)) + emission, vec3(0.0)), vec3(1.0 / 2.2));
}
