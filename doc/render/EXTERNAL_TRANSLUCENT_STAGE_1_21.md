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
- To **opt in** to the original GPU-to-CPU sorting and more accurate transparency on non-Toon programs, launch with `-Dmmdskin.plainShaderGpuReadbackSort=true`.

Measure using the in-mod GPU diagnostic counters `READBACK_CALLS`, `READBACK_BYTES`, and submission timings. The fast path should reduce these specifically for non-Toon GPU-animated models. A severe slowdown still occurring with the feature disabled would require a CPU/GPU profile; no numeric FPS improvement is claimed.

## Shader-pack + chest/block entity occlusion regression

The prior `classicDepthPrepass()` returned true for every world model when the shared queue was unavailable. This occurred with active shader packs, and triggered a **depth-only translucent pass** during entity submission. Later block entities such as chests could then fail their depth test behind nominally transparent MMD hair/clothing. This was **not** a block-entity API problem; it was premature model depth occlusion.

Disable the early depth-only prepass for world scenes entirely. World fallback always uses opaque + translucent color passes with depth writes disabled for translucent fragments, and no subsequent depth seal, even if the public callback has fired. The private Toon depth-peeling mechanism can still select layers without committing transparent pixels to world depth. This fixes the early-depth hazard; exact blending order of independent later block entities remains a separate cross-pass limitation requiring staged deferred compositing.

Regression scenarios: place a chest / sign / banner immediately behind translucent MMD hair, while facing away and moving the camera, both with Toon peeling enabled and disabled and with BSL/NeOculus. Check water/glass and outline masks too. In particular, verify the actual depth mask and depth buffer via a GPU capture, not only the resulting screenshot.
