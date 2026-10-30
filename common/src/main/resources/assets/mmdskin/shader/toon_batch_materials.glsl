// 无组合时保持原来的六个 vec4；组合变体追加七个材质通道。
#ifdef MMD_FX_COMBINED
struct MmdFxChannel { vec4 value; vec4 options; vec4 uvSource; };
#endif
struct MmdBatchMaterial {
    vec4 color;
    vec4 specularPower;
    vec4 sphereMul;
    vec4 sphereAdd;
    vec4 shading; // eye mask, sphere mode, diffuse slot, sphere slot
    vec4 flags;   // double sided, hair
#ifdef MMD_FX_COMBINED
    MmdFxChannel effects[7];
#endif
};
layout(std430, binding = 2) readonly buffer MmdBatchMaterials { MmdBatchMaterial mmdMaterials[]; };
layout(std430, binding = 3) readonly buffer MmdBatchTriangles { int mmdTriangleRows[]; };
uniform bool MmdBatchEnabled;
uniform int MmdBatchTriangleOffset;
uniform int MmdBatchCullMode; // 0 back, 1 front, 2 front and back
uniform sampler2D MmdBatchTextures[8];
vec4 mmdColor, mmdSphereMul, mmdSphereAdd;
vec3 mmdSpecular;
float mmdPower, mmdEyeMask;
int mmdSphereMode, mmdDiffuseSlot, mmdSphereSlot;
bool mmdCull, mmdHairMaterial;
#ifdef MMD_FX_COMBINED
int mmdFxKind, mmdFxSelection;
MmdFxChannel mmdFxChannels[7];
#endif
vec2 mmdSphereUv, mmdSphereDx, mmdSphereDy;

// Sampler selection is not dynamically uniform across primitives. Constant sampler
// indices and explicit gradients keep mip/anisotropic filtering defined in every case.
vec4 mmdTexture(int slot, vec2 uv, vec2 dx, vec2 dy) {
    switch (slot) {
        case 0: return textureGrad(MmdBatchTextures[0], uv, dx, dy);
        case 1: return textureGrad(MmdBatchTextures[1], uv, dx, dy);
        case 2: return textureGrad(MmdBatchTextures[2], uv, dx, dy);
        case 3: return textureGrad(MmdBatchTextures[3], uv, dx, dy);
        case 4: return textureGrad(MmdBatchTextures[4], uv, dx, dy);
        case 5: return textureGrad(MmdBatchTextures[5], uv, dx, dy);
        case 6: return textureGrad(MmdBatchTextures[6], uv, dx, dy);
        default: return textureGrad(MmdBatchTextures[7], uv, dx, dy);
    }
}
ivec2 mmdTextureSize(int slot) {
    switch (slot) {
        case 0: return textureSize(MmdBatchTextures[0], 0);
        case 1: return textureSize(MmdBatchTextures[1], 0);
        case 2: return textureSize(MmdBatchTextures[2], 0);
        case 3: return textureSize(MmdBatchTextures[3], 0);
        case 4: return textureSize(MmdBatchTextures[4], 0);
        case 5: return textureSize(MmdBatchTextures[5], 0);
        case 6: return textureSize(MmdBatchTextures[6], 0);
        default: return textureSize(MmdBatchTextures[7], 0);
    }
}
void mmdLoadMaterial() {
    mmdCull = false;
    if (MmdBatchEnabled) {
        MmdBatchMaterial row = mmdMaterials[mmdTriangleRows[MmdBatchTriangleOffset + gl_PrimitiveID]];
        mmdColor = row.color;
        mmdSpecular = row.specularPower.xyz;
        mmdPower = row.specularPower.w;
        mmdSphereMul = row.sphereMul;
        mmdSphereAdd = row.sphereAdd;
        mmdEyeMask = row.shading.x;
        mmdHairMaterial = row.flags.y != 0.0;
#ifdef MMD_FX_COMBINED
        mmdFxKind = int(row.flags.z);
        mmdFxSelection = int(row.flags.w);
        for (int channel = 0; channel < 7; channel++) mmdFxChannels[channel] = row.effects[channel];
#endif
        mmdSphereMode = int(row.shading.y);
        mmdDiffuseSlot = int(row.shading.z);
        mmdSphereSlot = int(row.shading.w);
        mmdCull = row.flags.x == 0.0 && (MmdBatchCullMode == 2
                || (MmdBatchCullMode == 0 ? !gl_FrontFacing : gl_FrontFacing));
    } else {
        mmdColor = MaterialColor;
        mmdSpecular = MaterialSpecular;
        mmdPower = MaterialSpecularPower;
        mmdSphereMul = SphereMul;
        mmdSphereAdd = SphereAdd;
        mmdSphereMode = SphereMode;
        mmdEyeMask = EyeMaterialMask;
        mmdHairMaterial = HairMaterial;
    }
}
vec4 mmdDiffuse(vec2 uv) {
    vec2 dx = dFdx(uv), dy = dFdy(uv);
    return MmdBatchEnabled ? mmdTexture(mmdDiffuseSlot, uv, dx, dy) : texture(Sampler0, uv);
}
ivec2 mmdSphereSize() {
    return MmdBatchEnabled ? mmdTextureSize(mmdSphereSlot) : textureSize(SphereTexture, 0);
}
vec4 mmdSphere(vec2 uv) {
    return MmdBatchEnabled ? mmdTexture(mmdSphereSlot, mmdSphereUv, mmdSphereDx, mmdSphereDy)
            : texture(SphereTexture, uv);
}
void mmdPrepareSphere(vec3 normal) {
    // Compute gradients before per-material branches and alpha/cull discards.
    vec2 halfTexel = 0.5 / vec2(max(mmdTextureSize(max(mmdSphereSlot, 0)), ivec2(1)));
    mmdSphereUv = clamp(normal.xy * 0.5 + 0.5, halfTexel, vec2(1.0) - halfTexel);
    mmdSphereDx = dFdx(mmdSphereUv);
    mmdSphereDy = dFdy(mmdSphereUv);
}
// Defined after the uniform reads; also affects the shared lighting helpers.
#define MaterialColor mmdColor
#define MaterialSpecular mmdSpecular
#define MaterialSpecularPower mmdPower
#define SphereMul mmdSphereMul
#define SphereAdd mmdSphereAdd
#define SphereMode mmdSphereMode
#define EyeMaterialMask mmdEyeMask
#define HairMaterial mmdHairMaterial
