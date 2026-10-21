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
