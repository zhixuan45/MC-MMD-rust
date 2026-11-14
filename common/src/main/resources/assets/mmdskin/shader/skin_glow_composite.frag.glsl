#version 330 core
in vec2 uv;
uniform sampler2D Glow;
uniform sampler2D SceneDepth;
uniform sampler2D VisibleEmission;
uniform int OutputEncoding;
layout(location = 0) out vec4 Color;
void main() {
    vec4 sampleGlow = texture(Glow, uv);
    vec3 glow = sampleGlow.rgb;
    float weight = dot(glow, vec3(0.2126, 0.7152, 0.0722));
    if (weight <= 0.000001) discard;
    float sourceDepth = sampleGlow.a / weight;
    ivec2 pixel = ivec2(uv * vec2(textureSize(VisibleEmission, 0)));
    // The emitter already passed the depth test during capture. Apply the blurred
    // depth only outside its visible coverage, where the halo can cross other objects.
    vec3 emission = texelFetch(VisibleEmission, pixel, 0).rgb;
    if (!any(greaterThan(emission, vec3(0.0))) && texelFetch(SceneDepth, pixel, 0).r + 0.00002 < sourceDepth) discard;
    vec3 nonnegativeGlow = max(glow, vec3(0.0));
    vec3 encodedGlow = OutputEncoding == 0 ? pow(nonnegativeGlow, vec3(1.0 / 2.2))
            : OutputEncoding == 1 ? sqrt(nonnegativeGlow) : nonnegativeGlow;
    Color = vec4(encodedGlow * 0.75, 0.0);
}
