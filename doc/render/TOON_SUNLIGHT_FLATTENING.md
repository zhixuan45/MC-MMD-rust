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
- Tangent/back-facing surfaces still require a shadow sample. While sunlight
  fill is active, BSL uses a continuous sampling-only NdotL floor of 0.2 to
  bound its inverse-NdotL depth bias. Its diffuse input is preserved. When
  sunlight fill is inactive, the original shadow-sampling input is retained.
- Classic mode retains self-occlusion as well as building occlusion. Optional
  whole-model mode uses the shared receiver described below. Shadow-map
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

## November 4: grazing self-shadow continuity

The first version used 0.2 only at NdotL <= 0, while arbitrarily small positive
values entered BSL's `sqrt(1 - NdotL * NdotL) / NdotL` shadow bias. Adjacent
normals across the terminator could therefore switch between a finite bias and
an unbounded bias, creating false lit/shadow patches.

The sampling floor now covers the entire grazing interval continuously. It
does not remove real self-occlusion or building shadows, and it leaves the
material diffuse normal unchanged.

Regression on production shaders with real BSL sources:

- Before: BSL 8.4.02.2 produced an RGB L1 jump of 0.555979 for a normal-dot-light
  change of 0.00001.
- After: BSL 8.4.02.2 and 10.1.8 have a maximum jump of about 0.000001 across positive/negative
  grazing normals, at both 20% and 100% style strength, over 201 shadow depths.
- All 30 real-pack variants and existing sunlight/cast-shadow/moonlight/indoor/
  torch/alpha checks pass.
- Merged baseline `20018a1`: 382 common-module tests, zero failures/errors, one
  skipped optional test; Fabric and NeoForge packaging pass.

The PowerShell probe now builds current production classes/resources before
compiling its fixtures, preventing fresh GLSL resources from being tested with
stale Java shader-source constants after a pull.

## November 4: optional whole-model lighting

`toonWholeModelLighting` adds a "Whole-Model Lighting" toggle to the Toon
category on Fabric and NeoForge. It defaults to false, including when loading
older configuration files. Saving the setting applies it to subsequent draws;
no restart is required. Disabling it clears the shared receiver uniform for
both the body and outline, restoring classic per-fragment lighting and
self-shadows. Existing flatness, softness and sphere controls keep their values.

When enabled with a supported BSL or Complementary adapter in a world scene,
each model uses one shadow receiver on the sun-facing edge of its conservative
bounding sphere. Material partitions share this point, reducing internal
self-occlusion. The model still participates in the normal shadow pass and
continues casting shadows on the world. External objects ahead of the receiver
can still shadow the model.

This is a receiver approximation, not shadow-caster identity filtering.
Partially covered models may switch lighting as a whole at building boundaries;
occluders intersecting the proxy volume may differ from classic sampling.
Bounds are cached separately per model from the GPU rest-position buffer or the
first CPU position buffer used while this mode is enabled. Later animation
extents do not resize the cached sphere. CPU models use their valid render
buffer rather than uninitialized native GPU rest-position storage.

This revision is committed without running further tests, builds or in-game
validation, at the user's request. The validation results above belong to the
earlier revisions and do not validate this toggle or the final bounds changes.

## November 5: terrain receiver replaces the shared point

The shared bounds receiver above is superseded by per-fragment terrain shadow
reception. The saved toggle key is retained. See
[TERRAIN_SHADOW_RECEIVER.md](TERRAIN_SHADOW_RECEIVER.md) for capture timing,
fallback behavior, coverage and validation.
