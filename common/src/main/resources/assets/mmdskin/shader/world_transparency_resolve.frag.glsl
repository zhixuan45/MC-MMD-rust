#version 430 core
struct MmdPixel { uint head; uint r; uint g; uint b; uint alpha; uint opacity; uint nearest; };
layout(std430, binding = MMD_META_BINDING) readonly buffer MmdHeads {
    uint mmdAllocated; uint mmdOverflow; uint mmdReserved0; uint mmdReserved1;
    MmdPixel mmdPixels[];
};
layout(std430, binding = MMD_NODE_BINDING) readonly buffer MmdNodes { uvec4 mmdNodes[]; };
uniform ivec4 MmdViewport;
uniform sampler2D MmdOpaqueDepth;
uniform bool MmdWaterResolve;
out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy) - MmdViewport.xy;
    uint pixel = uint(p.y * MmdViewport.z + p.x);
    MmdPixel entry = mmdPixels[pixel];
    // Water nodes were allocated after the per-batch snapshot. Preserve model-only
    // pixels for late composition; overflow retains the original full resolve.
    if (MmdWaterResolve && !(entry.head > mmdReserved0
            || (mmdOverflow != 0u && mmdAllocated != mmdReserved0))) discard;
    if (entry.head == 0u && entry.alpha == 0u) discard;
    float opaque = texelFetch(MmdOpaqueDepth, p, 0).r;
    // Most water pixels contain a single node. Keep dense/overflow pixels on the exact path below.
    if (entry.head != 0u && entry.alpha == 0u) {
        uvec4 node = mmdNodes[entry.head - 1u];
        if (node.w == 0u) {
            float depth = uintBitsToFloat(node.z);
            if (depth > opaque) discard;
            vec4 color = vec4(unpackHalf2x16(node.x), unpackHalf2x16(node.y));
            fragColor = vec4(color.rgb * color.a, color.a);
            gl_FragDepth = depth;
            return;
        }
    }
    float tailDepth = 1.0 - uintBitsToFloat(entry.nearest);
    bool tail = entry.alpha != 0u && tailDepth <= opaque;
    vec4 accumulated = vec4(0.0);
    float previousDepth = -1.0;
    uint previousId = 0u;
    float nearest = 1.0;
    // Selection sort over the linked list avoids truncating dense pixels to a local array size.
    // Equal depth uses node ID to visit each fragment exactly once.
    for (;;) {
        float depth = 2.0;
        uint best = 0u;
        for (uint id = entry.head; id != 0u; id = mmdNodes[id - 1u].w) {
            float d = uintBitsToFloat(mmdNodes[id - 1u].z);
            if (d > opaque || d < previousDepth || (d == previousDepth && id <= previousId)) continue;
            if (d < depth || (d == depth && id < best)) { depth = d; best = id; }
        }
        bool useTail = tail && tailDepth < depth;
        if (best == 0u && !useTail) break;
        vec4 color;
        if (useTail) {
            color = vec4(vec3(entry.r, entry.g, entry.b) / float(entry.alpha),
                         1.0 - exp(-float(entry.opacity) / 4096.0));
            depth = tailDepth;
            tail = false;
        } else {
            uvec4 node = mmdNodes[best - 1u];
            color = vec4(unpackHalf2x16(node.x), unpackHalf2x16(node.y));
            previousDepth = depth;
            previousId = best;
        }
        nearest = min(nearest, depth);
        accumulated.rgb += (1.0 - accumulated.a) * color.a * color.rgb;
        accumulated.a += (1.0 - accumulated.a) * color.a;
        if (accumulated.a >= 0.99999) break;
    }
    if (accumulated.a == 0.0) discard;
    fragColor = accumulated; // premultiplied, including Fabulous's translucent target contract
    gl_FragDepth = nearest;
}
