# Public translucent stage compatibility — Minecraft 1.21.1

## Cause

Commit `327e71f` redirected `VertexBuffer.draw()` from `LevelRenderer.renderSectionLayer`. Embeddium's terrain renderer replaces that internals; Mixin fails before the runtime compatibility check can disable the feature.

## Fix

1. Keep model opaque rendering in the entity pass and defer eligible model translucent fragments in GPU fragment storage.
2. Delete all `renderSectionLayer` Mixin injections and draw-call redirects (Fabric and NeoForge).
3. Compose model fragments **after** vanilla water/glass, using the later NeoForge particles stage to avoid the documented limitations of drawing translucency directly at AFTER_TRANSLUCENT_BLOCKS: `RenderLevelStageEvent.Stage.AFTER_PARTICLES` on NeoForge and `WorldRenderEvents.AFTER_TRANSLUCENT` on Fabric.
4. Begin deferring only after the public stage fired in the preceding frame. If it disappears, return to immediate model transparency on the next frame. Duplicate callbacks are ignored.
5. Retain immediate rendering under active Iris/NeOculus shader packs and Fabulous until distinct framebuffer pipelines can be validated.

## Known limitation

The MMD fragment queue does **not** contain water/glass fragments. Drawing the model composite after water/glass does not yield exact per-fragment water/model intersections: MMD transparency may appear over water even when the model is farther away. Full OIT across different renderers requires a supported shared transparent submission/depth API; do **not** reintroduce internal draw-call redirects.

## Validation checklist

- Run `./gradlew :common:test --tests '*WorldTransparencyStageGateTest' :neoforge:compileJava :fabric:compileJava`.
- Boot NeoForge 1.21.1 with Embeddium 1.0.15 and NeOculus 1.8.7, shader pack disabled. Confirm `captureTranslucentTerrain` Mixin exception is gone.
- Verify first frame and second frame in-world, models with translucent hair, water/glass intersections, a world with no water nearby, and resource reload.
- Recheck Fabric + Sodium, Fabulous, and Iris/NeOculus shader packs; compare opaque depth, outline, and hair alpha behaviour.

## Performance with Sodium/Embeddium and Toon rendering disabled

The original `TranslucentDrawOrder.prepare()` path downloads **animated GPU positions** by `glGetBufferSubData` when it cannot use the Toon batch-shader/GPU sorting path. This can stall the rendering thread each frame. Non-Toon world materials also inherited the depth-only prepass, plus Toon layer capability checks, despite not supporting those shader uniforms.

Fast plain-shader policy:

- Never enter shared fragment-list OIT or Toon depth-peeling with a non-Toon material program.
- Render opaque, then translucent material in two passes without the depth-only prepass or depth seal.
- When model vertices are GPU-only, skip CPU per-triangle sorting by default; retain original per-material order. This can increase hair/glass overlap artifacts but prevents costly GPU-to-CPU synchronization.
- When CPU positions already exist, retain the quality-sorted path without GPU readback.
- Accurate CPU/GPU triangle ordering is restored as the default when Toon is disabled, because skipping it broke overlapping translucent hair and clothing. Explicit opt-out for speed: `-Dmmdskin.fastPlainShaderTransparency=true`.

Measure using the in-mod GPU diagnostic counters `READBACK_CALLS`, `READBACK_BYTES`, and submission timings. The fast path should reduce these specifically for non-Toon GPU-animated models. A severe slowdown still occurring with the feature disabled would require a CPU/GPU profile; no numeric FPS improvement is claimed.

## Shader-pack + chest/block entity occlusion regression

The prior `classicDepthPrepass()` returned true for every world model when the shared queue was unavailable. This occurred with active shader packs, and triggered a **depth-only translucent pass** during entity submission. Later block entities such as chests could then fail their depth test behind nominally transparent MMD hair/clothing. This was **not** a block-entity API problem; it was premature model depth occlusion.

Disable the early depth-only prepass for world scenes entirely. World fallback always uses opaque + translucent color passes with depth writes disabled for translucent fragments, and no subsequent depth seal, even if the public callback has fired. The private Toon depth-peeling mechanism can still select layers without committing transparent pixels to world depth. This fixes the early-depth hazard; exact blending order of independent later block entities remains a separate cross-pass limitation requiring staged deferred compositing.

Regression scenarios: place a chest / sign / banner immediately behind translucent MMD hair, while facing away and moving the camera, both with Toon peeling enabled and disabled and with BSL/NeOculus. Check water/glass and outline masks too. In particular, verify the actual depth mask and depth buffer via a GPU capture, not only the resulting screenshot.

## BSL v10.1.8 / GPU batch-capacity fallback

The user log with BSL v10.1.8 after the shader-pack switch shows 384 model render calls,
768 candidate-set builds, 768 GPU-to-CPU readbacks (295,142,400 bytes), and
`GPU_PREPARE=0`, `GPU_WINDOW_FALLBACK=384`. This is a fallback pattern, not proof
of a bug in BSL itself. The initial GPU plan can fail when a model needs more than
the available batch-texture slots.

If the GPU path has already built a conservative (unpruned) candidate set, let
the CPU path reuse it even when the CPU path would prefer alpha-coverage pruning.
The extra candidates are conservative and are still subject to the fragment
alpha test. Reuse also skips the unnecessary UV buffer readback for that fallback.
When a stricter candidate list is already cached, never reuse it for a pass
that needs unpruned candidates. This changes no GPU capacity limits and
does not pretend to solve the lack of a multi-window GPU submission plan.

BSL official release: https://modrinth.com/shader/bsl-shaders/version/Y68yiql9 .
The user's Google Drive `using models` folder contains PMX/model archives, not
this shaderpack. The official binary could not be downloaded in the current
environment; do not treat third-party older GLSL mirrors as equivalent to v10.1.8.

Validation:
- Compare `CANDIDATE_BUILD`, `CANDIDATE_REUSE`, `READBACK_CALLS`,
  `READBACK_BYTES`, and `GPU_WINDOW_FALLBACK` on the same world and PMX.
- Verify visual blending of complex alpha hair with BSL v10.1.8, including
  4-layer depth peeling, chests and water.
- A proper cross-texture GPU multi-window sort would require further design.

## Shadow-pack block-entity layering / conditional Iris composition

When a shader pack is active the old path always disabled the post-world queue, even
when Toon model-local transparency was correctly depth peeled. The transparent MMD
surface could therefore be drawn in the entity pass and later covered by water,
chests, or other block entities.

The Iris-compatible path is deliberately conservative:

- Only world Toon material programs whose active Iris output profile is verified
  to put colortex0 at GLSL output location zero can use this queue. Arbitrary MRT
  source programs are not cloned by this patch.
- To avoid confusing an Iris FBO wrapper with a color target, compare the actual
  COLOR_ATTACHMENT0 backing object of the entity FBO with the **previous frame's**
  public post-particle-stage color attachment and verify the viewport dimensions.
- Capture MMD translucent fragments without writing depth. At the current world
  stage, prefer the stage's (compatible) later depth for block-entity occlusion.
- Composite after world transparents. Touch only COLOR_ATTACHMENT0; restore the
  full GL draw-buffer mapping afterwards.
- If the current stage unexpectedly switches target, preserve the already queued
  fragments by resolving back into their original source FBO. Future frames
  automatically use the immediate fallback until compatible attachments appear.
- If a shader pack composites entities and transparent world in different
  attachments, *do not* pretend they are interchangeable. That case still needs
  an explicit Iris-supported composite API.

For verification, compare vanilla, NeoForge/Embeddium, BSL v10.1.8 and Complementary.
Observe a chest both in front of and behind translucent MMD hair, water crossings,
and changing shader settings. A successful Java CI does not verify GL framebuffer
correctness: test the resulting in-game pixels and capture FBO attachments.

## BSL v10.1.8: distinguish opaque depth from water depth

Validated against the user's exact BSL_v10.1.8.zip. Its
`gbuffers_entities_translucent` and `gbuffers_water` programs use DRAWBUFFERS:01,
and the composite program samples **both** `depthtex0` and `depthtex1`.
A late copy of the currently-bound world depth is not necessarily opaque:
water/glass may have changed it, so using it as a hard cutoff can make the
chest/water layer appear in front of every MMD transparent fragment.

New stage split:

- The entity draw captures MMD transparent fragments but does not seal main depth.
- At NeoForge `AFTER_BLOCK_ENTITIES` (Fabric `BEFORE_DEBUG_RENDER`),
  and before translucent terrain, snapshot the opaque+block-entity depth into
  MMD's private depth texture. Only do this when the same primary color and
  depth attachment are verified as the original entity target.
- At `AFTER_PARTICLES` (Fabric `AFTER_TRANSLUCENT`), resolve against the
  saved depth; do **not** overwrite it from the water-stage depth. If the later
  primary color target differs, the existing safety fallback applies.
- The previous-frame verification must see both the post-block-entity target
  and the final translucent target. Otherwise shader-pack deferred capture
  remains disabled.

Limitations: transparent water and MMD fragments are not interleaved into a
shared exact OIT structure, and color-only composition does not populate BSL's
auxiliary colortex1 material channel. Further pack-specific integration would
be needed for fully correct refractive effects and every shader configuration.

Regression: verify BSL/NeOculus with transparent hair in front of and behind a
chest, water, and glass, while orbiting the camera. Compare translucent
silhouettes at the water boundary to a no-shader baseline.

### Shader-pack stage-mismatch fallback

When the entity/post-block-entity/post-particle framebuffer targets diverge mid-frame,
never copy opaque depth from the late source FBO: it might already contain water
(`depthtex0` semantics). Capture a conservative pre-water snapshot when the first
MMD model enters the queue. The `AFTER_BLOCK_ENTITIES` stage replaces that snapshot
with more complete opaque + chest depth when validated. If the late stage no longer
matches the original color backing object, composite queued fragments to the
original MMD color backing using the preserved early depth; the next frame must
revalidate color compatibility. A failed initial depth copy disables deferral
for the frame, keeping the normal immediate alpha renderer.

### Diagnostic flag for field tests

Add JVM option `-Dmmdskin.debugTransparencyStages=true` to print a concise
(once per 120 frames) trace of Iris entity, post-block-entity and post-particle
color attachments and opaque-depth snapshot status. The trace distinguishes
incompatible FBO targets from a depth copy that failed. Include these log lines
and a screenshot if BSL still shows a chest over translucent MMD hair.

### Stable Iris colortex0 when public events bind another FBO

The previous-frame attachment check can reject every model when a public loader
event runs with Minecraft's main FBO bound instead of the entity gbuffer. Immediate
alpha blending then happens before later chest draws and the pale clothing layer
is overwritten. This is reproducible with the production draw helper in a GL probe.

The queue now also accepts a verified Toon primary-color profile when Iris's public
`getFlippedAfterPrepare()` and `getFlippedAfterTranslucent()` agree for colortex0.
This establishes that entity rendering and the following composite use the same
side of the color storage, without guessing from the event's current FBO. At the
opaque stage, inspect the saved entity FBO, verify its color/depth storage identity,
and snapshot depth including chests. At the post-particle stage, resolve into that
saved color target when the callback binds a different FBO. Restore both FBOs'
draw-buffer mappings and the caller's GL state. If the public Iris access is absent
or colortex0 flips, retain the existing attachment checks and immediate fallback.

Checked against the local BSL v10.1.8 sources: `gbuffers_block` writes colortex0;
`deferred` writes colortex4 (AO); `composite` samples colortex0 and writes its color
onward. Resolve remains before that composite, so the corrected color participates
in BSL post-processing. This is source/bytecode inspection, not an in-game capture.

Alpha review: Toon discards below `max(0.001, configured cutoff)`; node capture
discards below 0.001; resolve only discards zero accumulated alpha. The local
reported instance has both configured cutoffs at 0.001. No threshold is changed.

Run the focused regression after generating the normal runtime classpath:

```powershell
./gradlew.bat --offline -I tools/translucent-performance/validation.gradle `
  :common:classes :common:writeTranslucentProbeClasspath -x :prepareNativeResources
./tools/translucent-performance/run-block-entity-probe.ps1
```

`BlockEntityTransparencyGlProbe` fails on the pre-fix classes because a stable
entity target cannot enter the queue with an unrelated event FBO. With the fix it
checks actual RGBA16F pixels for alpha 0.002/0.01/0.12/0.5, late chest occlusion in
both depth orders, alpha holes, shared-storage FBO wrappers, pre-water depth,
untouched auxiliary MRT output, restored state, subsequent color sampling, and
fallback for unverified targets. It uses synthetic shaders and an Iris verification
fixture; exact BSL lighting/refraction and the user's model still require in-game
comparison.

### Per-model rejection must not re-enable world depth sealing

The first stable-target test build still allowed a model rejected by `prepareModel()`
to enter the three-pass local depth-seal path. `worldFallback()` checked the frame's
`queueActive` flag, which can be true even when the current model has an incompatible
FBO, unverified color output, unsupported depth attachment, or insufficient SSBO
capacity. The rejected model then wrote translucent coverage into world depth and
could prevent a subsequently submitted chest or water surface from rendering.

`worldFallback()` now protects every world-scene fallback; its caller already
excludes models accepted by the shared queue. Inventory/GUI depth policy is unchanged.
The GL regression now actually draws a rejected model with depth testing enabled,
reads the host depth, and then draws the late chest. Pre-fix host depth is 0.4 at the
translucent veil; fixed depth remains 1.0 and the chest renders. The earlier test
only checked `prepareModel()` returning false and did not exercise this draw path.

The extended probe also compiles the production Toon shader with an actual
32/255-alpha texture, with both accepted and rejected capture. Optional `-BslPack`
uses the existing Iris probe dependencies to select the real local BSL output
profile. It verifies depth preservation for this real shader path as well as the
synthetic FBO/occlusion cases. This still does not identify which eligibility check
fails in a particular running game or constitute full BSL scene acceptance.

The new build logs the first successful shader-pack capture and each distinct
per-model rejection reason once per resource lifecycle, without requiring a JVM
debug option. These lines distinguish the actual in-game path on the next run.
Rejected immediate draws can still lose the pale layer to later opaque draws;
this fix specifically prevents those draws from making chests/water disappear.

### Distinguish stable-target failures and select the actual world entity shader

The r2 game log records `verified=true, stable=false, opaqueMatch=false,
lateMatch=false`, without a successful capture. That log cannot retrospectively
distinguish an API exception, a different pipeline class, a colortex0 flip, or the
additional short circuit where `RenderSystem.getShader()` is not an Iris shader.
In the latter case the old code selects the vanilla output profile, calls it
verified, and never even queries the Iris pipeline.

World-scene preparation now resolves the public Iris
`ShaderMap.ENTITIES_TRANSLUCENT` when the current shader is not an Iris shader.
Both CPU and GPU Toon rendering apply the same selected source whose profile was
verified, rather than checking one source and rendering with another. GUI and
other non-world callers keep their existing source shader. A real colortex0 flip
still requires matching attachments; it never silently bypasses target validation.

`Iris target verification` diagnostics contain the current and selected shader
classes/names, selection path, actual profile ID, and these distinct target statuses:

| Status | Meaning |
| --- | --- |
| `API_LOOKUP_FAILED` | Class/method discovery failed; exception class and message are included. |
| `API_READ_FAILED` | Invocation failed; the underlying exception is unwrapped and logged. |
| `NO_PIPELINE` | The public manager has no current pipeline. |
| `PIPELINE_TYPE_MISMATCH` | Expected and actual pipeline classes are recorded. |
| `INVALID_FLIP_STATE` | A flip getter did not return a non-null set. |
| `COLOR_BUFFER_SWITCHED` | colortex0 membership differs; `before`/`after` show both values. |
| `STABLE` | colortex0 remains on the same side, including when both sides are flipped. |

`sourceStatus=NON_IRIS_SHADER` separately identifies the source-shader gate.
The final `outputVerified` and `stablePrimary` values and full verification result
are also included in rejection logs. Each distinct diagnostic is logged once per
resource lifecycle. Read errors are retried; pipeline snapshots are cached only
when successfully read, and lookup/selection state is cleared on renderer release.

The actual installed Iris 1.8.14-beta.1 API lookup passes with the installed
Sodium 0.8.13 runtime. Sodium's outer jar contains a nested runtime jar; omitting
that from a standalone probe falsely reports a missing `VertexSerializer` class.
The `-BslPack` probe now includes it and checks the production reflection bridge
and ShaderMap symbols before running the GL regression. Six unit cases distinguish
stable/switched buffers, missing/wrong pipelines, invalid results, invocation
failure/recovery, and nested exception causes. None of these offline checks can
recover the branch omitted from the old game log; the new game log identifies it.
