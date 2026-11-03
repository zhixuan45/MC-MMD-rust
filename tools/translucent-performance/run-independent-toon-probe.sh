#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
classpath="$(<"$repo_root/build/translucent-performance/classpath.txt")"
: "${LWJGL_EGL_JAR:?Provide the LWJGL EGL jar matching the Minecraft LWJGL version}"
: "${LWJGL_NATIVE_CLASSPATH:?Provide installed Linux LWJGL natives; this script does not download dependencies}"
classpath="$classpath:$LWJGL_EGL_JAR:$LWJGL_NATIVE_CLASSPATH"
probe_output="$repo_root/build/translucent-performance/independent-probe"
mkdir -p "$probe_output"
cd "$repo_root"
javac -proc:none -encoding UTF-8 -cp "$classpath" -d "$probe_output" \
    tools/translucent-performance/{IndependentToonGlProbe,BlockEntityTransparencyGlProbe,BatchGlProbe,DepthAttachmentGlProbe,ToonTransparencyGlProbe,ToonTransparencyLayersGlProbe}.java
export EGL_PLATFORM="${EGL_PLATFORM:-surfaceless}"
java -cp "$classpath:$probe_output" com.shiroha.mmdskin.render.pipeline.toon.IndependentToonGlProbe
# Existing assertions and production GL kernels, with only the window/context entry replaced by EGL.
for probe in DepthAttachmentGlProbe ToonTransparencyLayersGlProbe; do
    java -cp "$classpath:$probe_output" com.shiroha.mmdskin.render.pipeline.toon.IndependentToonGlProbe "$probe"
done
for batch in false true; do
    java -cp "$classpath:$probe_output" com.shiroha.mmdskin.render.pipeline.toon.IndependentToonGlProbe ToonTransparencyGlProbe "$batch"
done
java -cp "$classpath:$probe_output" com.shiroha.mmdskin.render.pipeline.toon.IndependentToonGlProbe BlockEntityTransparencyGlProbe
java -cp "$classpath:$probe_output" com.shiroha.mmdskin.render.pipeline.toon.IndependentToonGlProbe BlockEntityTransparencyGlProbe --water-order
