// Ray 材质参数的宿主实现；不使用原 MME 的 G-buffer 编码和渲染脚本。
uniform bool FxMaterialEnabled;
uniform float FxMaterialPower;
uniform vec3 FxMaterialAmbient, FxMaterialSpecular;
uniform int FxSources[12];
// xyz: channel value. w: custom invert flag; slot 6: emission intensity.
// Batches pack evaluated PMX ambient/specular into slots 1/3 yzw and power into slot 2 w.
uniform vec4 FxValues[12];
uniform ivec4 FxOptions[12]; // UV flip、type (CUSTOM_A: shading model)、swizzle、scale
uniform vec2 FxRepeat[12];
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
uniform sampler2D FxMap10;
uniform sampler2D FxMap11;
#ifdef MMD_FX_GLASS
uniform sampler2D FxSceneColor;
uniform bool FxSceneEnabled;
uniform vec4 FxSceneViewport;
#endif

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

vec3 mmdFxPmxColor(bool specular) {
#if defined(MMD_BATCH_MATERIALS) && defined(MMD_FX_COMBINED)
    if (MmdBatchEnabled) return mmdFxChannels[specular ? 3 : 1].value.yzw;
#endif
    return specular ? FxMaterialSpecular : FxMaterialAmbient;
}

vec4 mmdFxSample(int slot, vec2 uv) {
    int flip = mmdFxOption(slot).x;
    if (flip == 1 || flip == 3) uv.x = 1.0 - uv.x;
    if (flip == 2 || flip == 3) uv.y = 1.0 - uv.y;
    uv *= mmdFxLoop(slot);
    if (mmdFxSource(slot) == 0) return vec4(mmdFxValue(slot).rgb, 1.0);
    if (mmdFxSource(slot) == 7 || mmdFxSource(slot) == 8) return vec4(mmdFxPmxColor(mmdFxSource(slot) == 8), 1.0);
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
    if (slot == 9) return texture(FxMap9, uv);
    if (slot == 10) return texture(FxMap10, uv);
    return texture(FxMap11, uv);
}

float mmdFxAlpha(float original, float materialAlpha, vec2 uv) {
    if (!mmdFxRayEnabled() || mmdFxSource(9) < 0) return original;
    if (mmdFxSource(9) == 0) return clamp(mmdFxValue(9).x * materialAlpha, 0.0, 1.0);
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
        if (mmdFxOption(0).y != 0) color *= MaterialColor.rgb;
        color = pow(max(abs(color), vec3(0.00001)), vec3(2.2));
        if (mmdFxOption(0).w == 1) color *= mmdFxValue(0).rgb;
        if (mmdFxOption(0).w == 2) color = pow(max(color, vec3(0.00001)), mmdFxValue(0).rgb);
    } else if (mmdFxOption(0).y != 0) {
        color *= pow(max(abs(MaterialColor.rgb), vec3(0.00001)), vec3(2.2));
    }
    int subMode = mmdFxOption(7).y;
    if (subMode != 0) {
        vec4 sub = mmdFxSample(7, uv);
        if (mmdFxSource(7) != 0 && mmdFxOption(7).w == 1) sub.rgb *= mmdFxValue(7).rgb;
        if (mmdFxSource(7) != 0 && mmdFxOption(7).w == 2) sub.rgb = pow(max(sub.rgb, vec3(0.00001)), mmdFxValue(7).rgb);
        if (subMode == 1) color *= sub.rgb;
        if (subMode == 2) color = pow(max(color, vec3(0.0)), sub.rgb);
        if (subMode == 3) color += sub.rgb;
        if (subMode == 4) {
            vec3 melanin = color * dot(color, vec3(0.299, 0.587, 0.114));
            color *= mix(vec3(1.0), melanin, sub.rgb);
        }
        if (subMode == 5) color = mix(color, sub.rgb, sub.a);
        color = clamp(color, 0.0, 1.0);
    }
    // Ray albedo constants and composition operate in linear space; the host lighting consumes sRGB.
    return pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2));
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

vec3 mmdFxSrgbToLinear(vec3 color) {
    return mix(pow((max(color, vec3(0.0)) + 0.055) / 1.055, vec3(2.4)),
               color / 12.92, lessThan(color, vec3(0.04045)));
}

float mmdFxCustomA(vec2 uv) {
    float weight = mmdFxSource(10) == 0 ? mmdFxValue(10).x : mmdFxSample(10, uv)[mmdFxOption(10).z];
    if (mmdFxSource(10) != 0) {
        if (mmdFxOption(10).w == 1) weight *= mmdFxValue(10).x;
        if (mmdFxOption(10).w == 2) weight = pow(max(weight, 0.0), mmdFxValue(10).x);
    }
    if (mmdFxValue(10).w != 0.0) weight = mmdFxOption(10).y == 6
        ? 1.0 - sqrt(max(weight, 0.0)) : 1.0 - weight;
    return clamp(weight, 0.0, 1.0);
}

vec3 mmdFxCustomB(vec2 uv) {
    vec3 color = mmdFxSample(11, uv).rgb;
    if (mmdFxSource(11) == 0 || mmdFxOption(11).y == 0) color = mmdFxSrgbToLinear(color);
    // Ray only applies B's invert/scale options when sampling a map.
    if (mmdFxSource(11) != 0) {
        if (mmdFxValue(11).w != 0.0) color = 1.0 - color;
        if (mmdFxOption(11).w == 1) color *= mmdFxValue(11).rgb;
        if (mmdFxOption(11).w == 2) color = pow(max(color, vec3(0.0)), mmdFxValue(11).rgb);
    }
    return clamp(color, 0.0, 1.0);
}

// Preserve the cloth probe helpers while all shading models share the same input protocol.
float mmdFxClothWeight(vec2 uv) { return mmdFxCustomA(uv); }
vec3 mmdFxClothColor(vec2 uv) { return mmdFxCustomB(uv); }

vec3 mmdFxSpecular(vec2 uv) {
    vec3 color = mmdFxSample(4, uv).rgb;
    int type = mmdFxOption(4).y;
    if (mmdFxSource(4) != 0 && (type == 2 || type == 3)) color = vec3(mmdFxSample(4, uv)[mmdFxOption(4).z]);
    if (type == 1 || type == 3) color = 0.16 * color * color;
    else if (type != 4) color *= 0.08;
    if (mmdFxSource(4) != 0) {
        if (mmdFxOption(4).w == 1) color *= mmdFxValue(4).rgb;
        if (mmdFxOption(4).w == 2) color = pow(max(color, vec3(0.0)), mmdFxValue(4).rgb);
        return clamp(color, 0.01, 1.0);
    }
    return clamp(color, 0.0, 1.0);
}

vec3 mmdFxEmissionColor(vec2 uv) {
    if (mmdFxValue(6).w == 0.0 && all(equal(mmdFxValue(6).rgb,vec3(0.0)))) return vec3(0.0);
    vec3 color = mmdFxSample(6, uv).rgb;
    if (mmdFxSource(6) >= 1 && mmdFxSource(6) <= 6) color *= mmdFxSample(6, uv).a;
    color = mmdFxSrgbToLinear(color);
    if (mmdFxOption(6).w != 0) color *= mmdFxValue(6).rgb;
    return color;
}
vec3 mmdFxEmission(vec2 uv) { return mmdFxEmissionColor(uv) * mmdFxValue(6).w; }

vec3 mmdFxAnisotropic(vec3 n, vec3 h, float nl, float nv, float vh, float roughness,
                      float anisotropy, vec3 shift, vec3 f0) {
    vec3 x = cross(n, vec3(0,1,0)) + n * shift.r;
    if (dot(x,x) < 1e-10) x = cross(n, vec3(1,0,0));
    x = normalize(x);
    vec3 y = cross(n,x) + n * shift.r;
    y /= max(length(y), 0.00001);
    float aspect = inversesqrt(max(1.0 - anisotropy * 0.9, 0.1));
    float ax = 1.0 / (roughness * aspect), ay = aspect / roughness;
    float d = pow(dot(x,h) * ax, 2.0) + pow(dot(y,h) * ay, 2.0) + pow(dot(n,h), 2.0);
    float distribution = ax * ay / max(d*d, 1e-10);
    float gv = nl * (nv * (1.0-roughness) + roughness);
    float gl = nv * (nl * (1.0-roughness) + roughness);
    vec3 fresnel = f0 + (1.0-f0) * pow(1.0-vh, 5.0);
    return distribution * 0.5 / max(gv+gl, 0.00001) * fresnel;
}

vec3 mmdFxMaterialLight(vec3 lit, vec3 albedo, vec3 n, vec3 p, vec3 light, vec2 uv, vec3 illumination) {
    if (!mmdFxRayEnabled()) return lit;
    float smoothness = mmdFxScalar(2, uv);
    float metalness = mmdFxScalar(3, uv);
    float ao = mmdFxScalar(5, uv);
    vec3 specular = mmdFxSpecular(uv);
    int model = mmdFxOption(10).y;
    vec3 emission = mmdFxEmission(uv);
    if (any(greaterThan(mmdFxEmissionColor(uv),vec3(2.0/255.0)))) model = 2;
    float customA = mmdFxCustomA(uv);
    vec3 customB = mmdFxCustomB(uv);
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
    vec3 f0 = mix(specular, base, metalness);
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - max(dot(v, h), 0.0), 5.0);
    vec3 reflection = distribution * geometry * fresnel / max(4.0 * nv * max(nl, 0.001), 0.0001);
    if (model == 5) {
        // Ray 1.5.2 ClothShading: customA mixes GGX with Charlie sheen and
        // Neubelt visibility; customB is the linear sheen color, reduced by metalness.
        float clothRoughness = max((1.0 - smoothness) * (1.0 - smoothness), 0.001);
        float vh = clamp(dot(v, h), 0.0, 1.0);
        vec3 sheenColor = customB * (1.0 - metalness);
        vec3 sheenFresnel = sheenColor + (1.0 - sheenColor) * pow(1.0 - vh, 5.0);
        float sheenDistribution = (2.0 + 1.0 / clothRoughness)
            * pow(max(1.0 - nh * nh, 0.0), 0.5 / clothRoughness) * 0.5;
        vec3 sheen = sheenFresnel * sheenDistribution / max(4.0 * (nl + nv - nl * nv), 0.00001);
        reflection = mix(reflection, sheen, customA);
    } else if (model == 3) {
        reflection = mmdFxAnisotropic(n, h, nl, nv, clamp(dot(v,h),0.0,1.0),
            max((1.0-smoothness)*(1.0-smoothness),0.001), customA, customB, f0);
    } else if (model == 6) {
        float coat = max((1.0-customA)*(1.0-customA),0.001);
        float m2 = coat*coat, vh = clamp(dot(v,h),0.0,1.0);
        float d = (nh*m2-nh)*nh+1.0;
        float coatSpecular = m2 / max(d*d*(4.0*vh*vh+1e-5),1e-10);
        float coatFresnel = 0.04+0.96*pow(1.0-vh,5.0);
        reflection = mix(reflection,vec3(coatSpecular),coatFresnel);
    } else if (model == 4) {
        float exponent = exp2(10.0*smoothness+1.0);
        reflection = vec3((exponent+2.0)/8.0 * pow(nh,exponent));
    }
    // Fresnel 反射占据的能量不再进入漫反射；场景照明仍由宿主提供。
    vec3 diffuse = pow(max(lit, vec3(0.0)), vec3(2.2)) * (1.0 - fresnel) * (1.0 - metalness) * ao;
    if (model == 4) {
        float transAngle = 1.0-clamp(dot(h,-light),0.0,1.0);
        vec3 trans = mix(specular,vec3(1.0),transAngle);
        diffuse += customA * customB * trans * max(dot(n,-light),0.0) * max(illumination,vec3(0.0));
    } else if (model == 1 || model == 7) {
        float signedNl = dot(n,light);
        float front = clamp(signedNl,0.0,1.0), back = clamp(-signedNl,0.0,1.0);
        vec3 scattering = customB*(1.0-metalness) * pow(1.0-front,2.0)
            * pow(1.0-back,3.0/(customA+0.001)) * max(customA-0.04,0.0);
        diffuse += scattering * max(illumination,vec3(0.0)) * ao;
    } else if (model == 8 || model == 9) {
        float signedNl = dot(n,light);
        float mask = model == 8 ? smoothstep(customA-0.02,customA,(signedNl*0.5+0.5)*ao)
            : clamp(signedNl*customA+1.0-customA,0.0,1.0)*ao;
        diffuse = base * mix(customB*(1.0-metalness),vec3(1.0),mask)
            * max(illumination,vec3(0.0)) * (1.0-metalness);
        if (model == 9) diffuse *= 1.0-fresnel;
    }
    return pow(max(diffuse + reflection * nl * max(illumination, vec3(0.0)) + emission, vec3(0.0)), vec3(1.0 / 2.2));
}

vec3 mmdFxGlass(vec3 lit, vec3 albedo, vec3 n, vec3 p, vec2 uv, float alpha) {
#ifdef MMD_FX_GLASS
    if (mmdFxRayEnabled() && mmdFxOption(10).y == 4 && FxSceneEnabled && MaterialColor.a < 0.999
            && !any(greaterThan(mmdFxEmissionColor(uv),vec3(2.0/255.0)))) {
        vec2 screen = (gl_FragCoord.xy-FxSceneViewport.xy) / FxSceneViewport.zw;
        vec2 halfPixel = 0.5 / FxSceneViewport.zw;
        vec2 offset = n.xy * mmdFxCustomA(uv) * 0.04;
        vec3 background = texture(FxSceneColor,clamp(screen+offset,halfPixel,1.0-halfPixel)).rgb;
        vec3 view = -p / max(length(p),0.00001);
        float smoothness = mmdFxScalar(2,uv);
        vec3 specular = mmdFxSpecular(uv);
        vec3 fresnel = specular + (1.0-specular) * pow(1.0-clamp(dot(n,view),0.0,1.0),5.0) / (40.0-39.0*smoothness);
        // Host alpha blending keeps coverage and sorting. Refraction uses the pre-model scene snapshot.
        return mix(background * max(albedo,vec3(0.02)),lit,clamp(fresnel*alpha,0.0,1.0));
    }
#endif
    return lit;
}
