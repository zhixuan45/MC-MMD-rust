#!/usr/bin/env bash
set -euo pipefail
# 使用与项目 LWJGL 相同版本的 lwjgl-egl jar；不修改应用依赖。
: "${LWJGL_EGL_JAR:?Set LWJGL_EGL_JAR to lwjgl-egl-3.3.3.jar}"
task_root=$(cd "$(dirname "$0")/../.." && pwd)
probe_tmp=$(mktemp -d)
trap 'rm -rf "$probe_tmp"' EXIT
export MMD_PROBE_CLASSPATH_FILE="$probe_tmp/classpath.txt"
cat > "$probe_tmp/classpath.gradle" <<'EOF'
gradle.projectsEvaluated {
    def common = gradle.rootProject.project(':common')
    common.tasks.register('writeMaterialProbeClasspath') {
        doLast {
            new File(System.getenv('MMD_PROBE_CLASSPATH_FILE')).text = common.sourceSets.main.runtimeClasspath.asPath
        }
    }
}
EOF
cd "$task_root"
"${GRADLE_COMMAND:-./gradlew}" :common:compileJava :common:writeMaterialProbeClasspath -I "$probe_tmp/classpath.gradle"
probe_cp="$(cat "$MMD_PROBE_CLASSPATH_FILE"):$LWJGL_EGL_JAR"
javac -proc:none -encoding UTF-8 -cp "$probe_cp" -d "$probe_tmp" tools/material-texture-probe/HeadlessProbe.java tools/material-texture-probe/VanillaEntityProbe.java tools/material-texture-probe/FullModelPreview.java
probe_args=()
if [[ -n "${MMD_PROBE_MODEL:-}" ]]; then probe_args+=("-Dmmd.probe.model=$MMD_PROBE_MODEL"); fi
EGL_PLATFORM=surfaceless MESA_SHADER_CACHE_DIR="$probe_tmp/mesa-cache" java "${probe_args[@]}" -cp "$probe_tmp:$probe_cp" probe.HeadlessProbe
if [[ -n "${MMD_PREVIEW_OUTPUT:-}" ]]; then
    : "${MMD_PROBE_MODEL:?Set MMD_PROBE_MODEL to the original PMX for a full preview}"
    preview_args=()
    if [[ "${MMD_PREVIEW_GPU:-0}" == 1 ]]; then preview_args+=("-Dmmd.preview.gpuInput=true"); fi
    if [[ "${MMD_PREVIEW_HEAD:-0}" == 1 ]]; then preview_args+=("-Dmmd.preview.head=true" "-Dmmd.preview.white=true"); fi
    EGL_PLATFORM=surfaceless MESA_SHADER_CACHE_DIR="$probe_tmp/mesa-cache" java "${preview_args[@]}" -cp "$probe_tmp:$probe_cp" \
        probe.FullModelPreview "$MMD_PROBE_MODEL" "$MMD_PREVIEW_OUTPUT"
fi
