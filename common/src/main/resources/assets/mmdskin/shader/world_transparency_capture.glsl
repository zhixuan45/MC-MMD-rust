layout(early_fragment_tests) in;
struct MmdPixel { uint head; uint r; uint g; uint b; uint alpha; uint opacity; uint nearest; };
layout(std430, binding = MMD_META_BINDING) buffer MmdHeads {
    uint mmdAllocated; uint mmdOverflow; uint mmdReserved0; uint mmdReserved1;
    MmdPixel mmdPixels[];
};
layout(std430, binding = MMD_NODE_BINDING) buffer MmdNodes { uvec4 mmdNodes[]; };
uniform ivec4 MmdViewport;
uniform uint MmdCapacity;

void mmdStore(vec4 color) {
    float alpha = clamp(color.a, 0.0, 1.0);
    if (alpha < 0.001) return;
    ivec2 p = ivec2(gl_FragCoord.xy) - MmdViewport.xy;
    if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, MmdViewport.zw))) return;
    uint pixel = uint(p.y * MmdViewport.z + p.x);
    uint node = atomicAdd(mmdAllocated, 1u);
    if (node < MmdCapacity) {
        uint previous = atomicExchange(mmdPixels[pixel].head, node + 1u);
        mmdNodes[node] = uvec4(packHalf2x16(color.rg), packHalf2x16(vec2(color.b, alpha)),
                             floatBitsToUint(gl_FragCoord.z), previous);
    } else {
        // Preserve coverage on pool exhaustion. Only the overflow tail uses weighted blending;
        // the normal path retains exact depths and HDR colors. Never silently drop water/glass.
        atomicAdd(mmdOverflow, 1u);
        atomicAdd(mmdPixels[pixel].r, uint(clamp(color.r, 0.0, 8.0) * alpha * 4096.0));
        atomicAdd(mmdPixels[pixel].g, uint(clamp(color.g, 0.0, 8.0) * alpha * 4096.0));
        atomicAdd(mmdPixels[pixel].b, uint(clamp(color.b, 0.0, 8.0) * alpha * 4096.0));
        atomicAdd(mmdPixels[pixel].alpha, uint(alpha * 4096.0));
        atomicAdd(mmdPixels[pixel].opacity, uint(-log(max(1.0 - alpha, 0.000001)) * 4096.0));
        atomicMax(mmdPixels[pixel].nearest, floatBitsToUint(1.0 - gl_FragCoord.z));
    }
}
