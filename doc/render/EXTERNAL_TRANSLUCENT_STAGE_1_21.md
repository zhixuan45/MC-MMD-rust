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
