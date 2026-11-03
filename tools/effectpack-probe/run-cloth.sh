#!/usr/bin/env bash
set -euo pipefail
: "${LWJGL_EGL_JAR:?Set LWJGL_EGL_JAR to lwjgl-egl-3.3.3.jar}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
classpath_file="${MMD_PROBE_CLASSPATH_FILE:-build/sphere-texture-probe/classpath.txt}"
classpath="$(cat "$classpath_file"):$LWJGL_EGL_JAR:$root/common/src/main/resources"
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.lwjgl"
while IFS= read -r -d '' native; do classpath="$classpath:$native"; done < <(find "$cache" -name '*-natives-linux.jar' -print0)
output="$(mktemp -d "$root/build/cloth-gl-XXXXXX")"
javac -encoding UTF-8 -proc:none -cp "$classpath" -d "$output" tools/effectpack-probe/ClothGlProbe.java
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 MESA_SHADER_CACHE_DIR="$output/mesa-cache" \
    java -Djava.awt.headless=true -cp "$output:$classpath" \
    com.shiroha.mmdskin.render.effectpack.ClothGlProbe "${1:?Pass the upstream Ray-MMD 1.5.2 Materials directory}"
