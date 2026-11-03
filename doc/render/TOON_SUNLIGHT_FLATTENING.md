# Toon sunlight flattening

The existing `toonShaderFlatness` control now reduces normal-dependent shading
in exposed sunlight. It adjusts both the pack's direct diffuse weight and the
model's Toon tint. At 100%, fully exposed fragments approach the same brightness
despite different surface normals.

## Conditions and shadow preservation

- Enabled only in the verified BSL and Complementary shadow adapters. Generic
  lighting retains its existing highlight suppression without inventing a
  sunlight visibility value.
- Sun direction must match the active light direction. The effect fades near
  the horizon and with low Minecraft skylight; moonlight, indoor lighting and
  torches do not receive sunlight fill.
- Visibility comes from the pack's actual shadow result, before multiplication
  by the surface-normal diffuse term. Visibility at or below 0.8 receives no
  fill; smoothstep blends the effect up to fully exposed visibility at 1.0.
  Colored shadows use the minimum RGB visibility.
- Tangent/back-facing surfaces still require a shadow sample. Existing positive
  NdotL shadow bias remains unchanged. BSL uses a finite sampling-only NdotL for
  zero-facing fragments; its original diffuse input is preserved.
- Cast shadows retain self-occlusion as well as building occlusion. Shadow-map
  filtering and colored transmission remain owned by the shader pack.
- Zero strength restores the original sunlight response. Alpha and coverage
  are unchanged.

`toonTransitionSoftness` now works independently of flatness. Raising flatness
no longer silently narrows the user's chosen transition width.
`toonShaderSphereSuppression` still controls material SPA/SPH textures only.
The three tooltips explain these separate roles in Chinese, English and Japanese.

## Validation

Use JDK 21 and the normal shared Gradle cache:

```powershell
.\gradlew.bat -I tools/translucent-performance/validation.gradle `
    :common:test :fabric:compileJava :neoforge:compileJava `
    :common:writeTranslucentProbeClasspath -x :buildRustNative
.\tools\iris-toon-probe\run-sunlight-probe.ps1
```

The optional GL probe reads the instance's shader ZIP files and creates a hidden
GLFW context. It does not launch Minecraft or modify the instance. Its fixtures
provide synthetic light uniforms and shadow textures to the production shaders.

Validated on November 3, 2026:

- 374 common-module tests: zero failures/errors, one skipped test.
- Fabric remapJar and NeoForge shadowJar passed, reusing the existing Windows DLL.
- NVIDIA RTX 5070 Ti Laptop GPU, OpenGL 4.6: 30 real-pack body/outline variants
  compiled across the Overworld, Nether and End, entities and hand programs.
- Packs: BSL 8.4.02.2 and 10.1.8, Complementary 4.7.1, Reimagined 5.9.3 and
  Unbound 5.9.3.
- Pixel checks: 70% substantially reduces normal-dependent sunlight differences;
  100% equalizes the selected exposed normals, including a tangent normal.
  Occluded fragments, moonlight, low-skylight indoor/torch scenes, alpha and
  restoration to zero strength pass.
- Existing shade transitions, material highlights, SPA/SPH suppression and
  body/outline environment-lighting checks pass.

The GL fixtures verify shading behavior and compatibility with actual pack
sources. The user's PMX scene, real shadow-map boundaries and final exposure
still need an in-game visual comparison.
