# Iris / BSL multi-PMX transparency

## Root-cause candidates addressed

- Iris may bind different FBO handles which nevertheless reference the same primary colortex0 and depth attachment. Former code rejected the second PMX based on FBO ID alone, mixing immediate alpha with the shared OIT queue.
- The first model's fallback depth snapshot can be too early to contain opaque triangles from subsequent PMX entities if the later block-entity depth stage is unavailable.

## Implementation

1. Share the OIT queue across Iris FBO wrappers only when the underlying color and depth attachment identity, format, sample count and viewport all pass validation. The old strict FBO equality check remains for non-Iris.
2. At the loader's after-entities stage, copy verified actual post-entity depth. The subsequent block-entity stage can replace it with more complete depth. Never read the late water-stage depth as an opaque cutoff.
3. Add **Debug Settings → Per-model transparency diagnostics** in both Fabric and NeoForge. This logs sampled model names, CREATE_SHARED_QUEUE / JOIN_SHARED_QUEUE / IMMEDIATE_FALLBACK decisions and per-frame counts. Enable **Transparency stage diagnostics** alongside it for precise depth/FBO stage messages. Both are off by default.
4. Override via `-Dmmdskin.debugTransparencyModels=true` and `-Dmmdskin.debugTransparencyStages=true`.

## Validation checklist

- Use BSL + Iris with two overlapping PMX models and rotate the camera; compare with shaders disabled.
- Repeat using a held diamond sword and a chest behind translucent PMX hair or clothing.
- Check logs for `JOIN_SHARED_QUEUE`, `after-entities depth captured`, `opaque-depth captured`, and `IMMEDIATE_FALLBACK`.
- Verify mismatched attachments do not join, and same backing textures/depth can join despite different FBO wrappers.
- Check `-Dmmdskin.worldTransparencyQueue=false` for comparison.
- Run `./gradlew :common:test --tests '*WorldTransparencyStagePolicyTest' :fabric:compileJava :neoforge:compileJava`.

## Remaining limits

The shared list contains PMX fragments, not every vanilla translucent item or water fragment. Exact cross-renderer transparency requires a shared OIT submission/depth API. This patch does not claim an in-game BSL visual verification; the GPU state needs a game test.
