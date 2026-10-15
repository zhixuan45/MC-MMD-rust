#version 430 core
layout(local_size_x = 128) in;
layout(std430, binding = 0) readonly buffer Positions { float positions[]; };
layout(std430, binding = 1) readonly buffer Triangles { uvec4 triangles[]; };
layout(std430, binding = 4) buffer Keys { uvec2 keys[]; };
layout(std430, binding = 5) writeonly buffer Indices { uint indices[]; };
layout(std430, binding = 3) writeonly buffer Rows { uint rows[]; };
layout(std430, binding = 6) buffer Command { uint count; uint instances; uint first; int base; uint instance; };
uniform int Mode; // 0 depth/clip, 1 bitonic compare/exchange, 2 gather
uniform uint TriangleCount, Capacity, SortWidth, SortStride;
uniform vec3 DepthRow;
uniform mat4 ClipMatrix;
uniform bool ClipEnabled;
shared uvec2 tile[128];
vec3 position(uint v) { return vec3(positions[v*3u], positions[v*3u+1u], positions[v*3u+2u]); }
uint clipCode(vec3 p) {
    if (!ClipEnabled) return 0u;
    precise vec4 c = ClipMatrix * vec4(p, 1.0);
    return (c.x < -c.w ? 1u : 0u) | (c.x > c.w ? 2u : 0u)
         | (c.y < -c.w ? 4u : 0u) | (c.y > c.w ? 8u : 0u)
         | (c.z < -c.w ? 16u : 0u) | (c.z > c.w ? 32u : 0u);
}
float depth(vec3 p) { precise float d = DepthRow.x*p.x + DepthRow.y*p.y + DepthRow.z*p.z; return d; }
bool greaterKey(uvec2 a, uvec2 b) { return a.x > b.x || (a.x == b.x && a.y > b.y); }
void main() {
    uint i = gl_GlobalInvocationID.x;
    // Complete the small strides in shared memory: one dispatch per tile/merge,
    // rather than a driver submission for every compare/exchange stride.
    if (Mode == 3 || Mode == 4) {
        uint lane = gl_LocalInvocationID.x;
        tile[lane] = i < Capacity ? keys[i] : uvec2(0xffffffffu);
        barrier();
        uint firstWidth = Mode == 3 ? 2u : SortWidth;
        uint lastWidth = Mode == 3 ? 128u : SortWidth;
        for (uint width = firstWidth; width <= lastWidth; width <<= 1u) {
            uint firstStride = Mode == 3 ? width >> 1u : 64u;
            for (uint stride = firstStride; stride > 0u; stride >>= 1u) {
                uint other = lane ^ stride;
                uvec2 a = tile[lane], b = tile[other];
                bool ascending = (i & width) == 0u;
                bool lower = (lane & stride) == 0u;
                bool swap = ascending == lower ? greaterKey(a, b) : greaterKey(b, a);
                barrier();
                tile[lane] = swap ? b : a;
                barrier();
            }
        }
        if (i < Capacity) keys[i] = tile[lane];
        return;
    }
    if (i >= Capacity) return;
    if (Mode == 0) {
        keys[i] = uvec2(0xffffffffu);
        if (i >= TriangleCount) return;
        uvec3 t = triangles[i].xyz;
        vec3 a = position(t.x), b = position(t.y), c = position(t.z);
        if ((clipCode(a) & clipCode(b) & clipCode(c)) != 0u) return;
        // Match the CPU accumulator, including its initial positive zero.
        precise float z = 0.0;
        z += depth(a); z += depth(b); z += depth(c);
        // Java Float.floatToIntBits canonicalizes NaN and sorts signed ordered bits.
        // Some drivers drop +0 additions despite precise; Java's accumulator makes
        // exact zero positive, so canonicalize it before the author-order tie break.
        int bits = z == 0.0 ? 0 : isnan(z) ? int(0x7fc00000u) : floatBitsToInt(z);
        uint ordered = uint(bits ^ ((bits >> 31) & 0x7fffffff)) ^ 0x80000000u;
        keys[i] = uvec2(ordered, i); // author ordinal is a stable tie breaker
        atomicAdd(count, 3u);
    } else if (Mode == 1) {
        uint other = i ^ SortStride;
        if (other <= i) return;
        uvec2 a = keys[i], b = keys[other];
        bool ascending = (i & SortWidth) == 0u;
        if (ascending ? greaterKey(a, b) : greaterKey(b, a)) { keys[i] = b; keys[other] = a; }
    } else {
        uint ordinal = keys[i].y;
        if (ordinal == 0xffffffffu) return;
        uvec4 t = triangles[ordinal];
        indices[i*3u] = t.x; indices[i*3u+1u] = t.y; indices[i*3u+2u] = t.z;
        rows[i] = t.w;
    }
}
