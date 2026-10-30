package com.shiroha.mmdskin.render.effectpack;

import com.shiroha.mmdskin.util.AssetsUtil;

/** 无效果的变体不声明额外 sampler，不占用原有光影包的纹理预算。 */
public final class FxShaderSource {
    public static final String DISABLED = """
            vec3 mmdFxAlbedo(vec3 a, vec2 uv) { return a; }
            vec3 mmdFxNormal(vec3 n, vec3 p, vec2 uv) { return n; }
            vec3 mmdFxMaterialLight(vec3 lit, vec3 a, vec3 n, vec3 p, vec3 l, vec2 uv, vec3 e) { return lit; }
            """;
    public static String material(FxEffect effect) {
        return effect != null && effect.kind() == FxEffect.Kind.RAY_MATERIAL
                ? "#define MMD_FX_RAY\n" + AssetsUtil.getAssetsAsString("shader/fx_material.glsl") : DISABLED;
    }
    private FxShaderSource() { }
}
