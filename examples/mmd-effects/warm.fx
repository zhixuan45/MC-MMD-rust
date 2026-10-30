// 曝光和暖色示例；贴图与材质覆盖率由宿主保留。
float Script : STANDARDSGLOBAL < string ScriptClass = "scene"; > = 0.8;
texture Screen : RENDERCOLORTARGET;
sampler ScreenSampler = sampler_state { Texture = <Screen>; };
const float exposure = 1.1;
const float3 tint = float3(1.08, 1.0, 0.92);

float4 PS(float2 uv : TEXCOORD0) : COLOR0 {
    float4 color = tex2D(ScreenSampler, uv);
    color.rgb = saturate(color.rgb * exposure * tint);
    return color;
}

technique Color { pass P { PixelShader = compile ps_3_0 PS(); } }
