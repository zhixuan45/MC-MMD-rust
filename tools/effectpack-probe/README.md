# Ray cloth regression probe

Requires Java 21 JDK, Linux Mesa EGL, `lwjgl-egl-3.3.3.jar`, and the original
Ray-MMD 1.5.2 Materials directory (commit `a425ab6d4219a047f8d64ac7fdc4f73c76c31dc8`).
Third-party textures remain outside the repository.

```sh
bash gradlew :common:compileJava :common:writeSphereProbeClasspath \
  -I tools/sphere-texture-probe/classpath.gradle
LWJGL_EGL_JAR=/path/to/lwjgl-egl-3.3.3.jar \
  bash tools/effectpack-probe/run-cloth.sh /path/to/ray-mmd/Materials
```

The probe parses all three original Cloth presets, compiles production regular
and batch fragment shaders, checks a cloth sheen pixel against an independent
Ray formula, and verifies diffuse toggling, custom-map swizzle/scale/invert and
color space. It compares uniform binding with the real std430 batch table.
Normal maps are disabled for the BRDF pixel checks to isolate the lighting
formula from image decoding. This does not replace visual testing in Minecraft
with real normal maps, environment lighting or Iris shader packs.

Parser and batch-layout unit tests:

```sh
bash gradlew :common:test --tests 'com.shiroha.mmdskin.render.effectpack.*' \
  --tests 'com.shiroha.mmdskin.render.material.EffectBatchPlanTest'
```
