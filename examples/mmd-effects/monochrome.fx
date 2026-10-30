// 单 pass、逐像素 MME 颜色效果示例，可导入人物、场景或世界目标。
float Script : STANDARDSGLOBAL < string ScriptClass = "scene"; > = 0.8;
texture Screen : RENDERCOLORTARGET;
sampler ScreenSampler = sampler_state { Texture = <Screen>; };

float4 PS(float2 uv : TEXCOORD0) : COLOR0 {
    float4 color = tex2D(ScreenSampler, uv);
    float gray = dot(color.rgb, float3(0.299, 0.587, 0.114));
    color.rgb = float3(gray, gray, gray);
    return color;
}

technique Color { pass P { PixelShader = compile ps_3_0 PS(); } }
