# GUI model visibility probe

This hidden-GL probe exercises the production
`BaseModelInstance.composeModelViewMatrix(PoseStack, RenderScene)` entrypoint. It
uploads the resulting matrix to a real GLSL program and checks the rasterized
pixels; it does not assert the matrix formula in isolation.

The probe configures the 1.20.1 GUI projection (`1000..21000`) and the
`RenderSystem` model-view base (`z=-11000`), then verifies:

- `RenderScene.INVENTORY` is visible.
- `RenderScene.PAPERDOLL` is visible and pixel-identical to `INVENTORY`.
- The old entity-PoseStack-only baseline is completely clipped.
- `RenderScene.WORLD` preserves its supplied world/entity PoseStack without
  multiplying the GUI base a second time.

Run after the main Gradle compilation has refreshed
`build/translucent-performance/classpath.txt`:

```powershell
powershell -ExecutionPolicy Bypass -File tools/gui-model-probe/run-gui-model-probe.ps1
```

Artifacts default to
`build/diagnostics/1201-camera-gui-euphoria-20261113/gui-gl`.
