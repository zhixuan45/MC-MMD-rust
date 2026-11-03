# Ray shading models 0–9: host adaptation

2026-11-02. User requested the shading-model table and CustomA/CustomB options
ahead of animation/GIF support, followed by a push to the default branch.
Default branch: `feat/1.21.1-neoforge`; upstream Ray input is tag `1.5.2`, commit
`a425ab6d4219a047f8d64ac7fdc4f73c76c31dc8`.

## Implemented

| ID | Host behavior | CustomA | CustomB |
| --- | --- | --- | --- |
| 0 | Existing default material | Ignored | Ignored |
| 1 | Local Ray Penner skin scattering/transmission | Curvature | sRGB scattering tint |
| 2 | Emission color and separate linear intensity, including Ray emission-model precedence | Ignored | Ignored |
| 3 | Directional anisotropic microfacet reflection, stable pole fallback | Anisotropy | Tangent shift |
| 4 | Glass reflection, local transmission and pre-model background refraction | Curvature/refraction strength | Transmission tint |
| 5 | GGX/sheen mixture | Blend weight | sRGB sheen F0 |
| 6 | Second clear-coat lobe with Fresnel blending | Coat smoothness | Ignored |
| 7 | Local Ray subsurface scattering/transmission | Curvature | sRGB scattering tint |
| 8 | Ray Cel threshold and shadow color | Threshold | sRGB shadow tint |
| 9 | Ray ToonBased ramp and Fresnel attenuation | Hardness | sRGB shadow tint |

Custom maps support constants, external static textures, PMX diffuse textures
and evaluated PMX ambient/specular sources (0/1/3/7/8). UV flip/repeat, scalar
swizzle, linear CustomA, sRGB/linear CustomB map type, color inversion and
multiply/power scaling are preserved. Clear-coat inversion uses
`1 - sqrt(value)`. Inactive inputs do not require missing texture files.
Specular map types 0–4 are converted; active maps on custom models obey Ray's
single-channel type requirement. Common-source hash and bounded constant
expression evaluation remain intact.

Uniform and batch paths share parameters. Batches keep the same channel stride
and pack evaluated PMX ambient/specular in spare scalar-channel components.
Glass shader variants have distinct program keys and a separately allocated
scene sampler. Snapshot capture preserves framebuffer, read buffer, unpack PBO,
scissor, sampler and texture state; resources are reused and released on reload.

## Validation

- Full common Java suite: 376 tests, 0 failures/errors, 1 optional asset test skipped.
- Fabric and NeoForge Java compilation passed. NeoForge's local validation used
  its supported launcher-manifest option with Mojang's `piston-meta` endpoint;
  the temporary environment override is not part of the repository changes.
- Mesa OpenGL 4.5 headless probe: 720 checks passed, including production regular
  and batch shader compilation, independent BRDF reference pixels, parameter
  response, ordinary/batch pixel parity and glass capture/resolve state.
- Original 268-material scan: 88 passed (recorded stage-one baseline: 63).
  Remaining 180: 140 Editor controller dependencies, 34 active blink options,
  1 emissive morph-color option, 3 emissive animated/external-runtime sources,
  and 2 albedo animated/external-runtime sources. No failures remain due to an
  unsupported model ID in the 0–9 range.

## Limits

Lighting and compositing are provided by Minecraft. This adapts material
parameters and local shading, without reproducing Ray's full deferred pipeline,
IBL or screen-space skin diffusion. Skin and subsurface share Ray's local Penner
term; their scene-level IBL differences are outside this renderer.

Glass uses a pre-model scene snapshot in view-space and preserves existing PMX
alpha sorting/blending. PMX material alpha must be below 0.999 for refraction.
Nested transparent surfaces and later scene draws are not equivalent to Ray's
deferred glass pass. No Minecraft/Iris in-game visual acceptance was performed.

Editor CONTROLOBJECT, GIF/video/external render targets, animation/blink and
unsupported sphere/toon-map sources retain explicit import diagnostics. They
were not disabled to increase the supported count. No third-party textures or
temporary build-environment overrides are included in the commit.
