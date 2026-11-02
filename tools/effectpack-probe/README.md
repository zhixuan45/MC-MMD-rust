# Ray shading-model regression probe

Requires Java 21 JDK, Linux Mesa EGL, `lwjgl-egl-3.3.3.jar`, and the original
Ray-MMD 1.5.2 Materials directory (commit `a425ab6d4219a047f8d64ac7fdc4f73c76c31dc8`).
Third-party textures remain outside the repository.

```sh
bash gradlew :common:compileJava :common:writeSphereProbeClasspath \
  -I tools/sphere-texture-probe/classpath.gradle
LWJGL_EGL_JAR=/path/to/lwjgl-egl-3.3.3.jar \
  bash tools/effectpack-probe/run-cloth.sh /path/to/ray-mmd/Materials
```

The probe parses ten original Cloth/Skin/Anisotropy/Glass/ClearCoat/Subsurface
presets and synthetic models 0–9, compiles production regular and batch fragment
shaders, checks cloth and anisotropic pixels against independent Ray formulas,
and verifies curvature, coat smoothness, toon thresholds, emission intensity,
diffuse toggling, custom-map swizzle/scale/invert and color space. It compares
uniform binding with the real std430 batch table, including evaluated PMX
ambient/specular sources. Glass checks exercise background displacement, GL
state restoration and normalized/HDR multisample framebuffer resolves.
Normal maps are disabled for the BRDF pixel checks to isolate the lighting
formula from image decoding. This does not replace visual testing in Minecraft
with real normal maps, environment lighting or Iris shader packs.

The final scan reports unsupported Editor controllers, animation/blink and
external-runtime inputs separately; no such feature is silently replaced by a
constant. The script name `run-cloth.sh` remains compatible with earlier usage.

Parser and batch-layout unit tests:

```sh
bash gradlew :common:test --tests 'com.shiroha.mmdskin.render.effectpack.*' \
  --tests 'com.shiroha.mmdskin.render.material.EffectBatchPlanTest'
```
