#!/usr/bin/env bash
set -euo pipefail
# 使用已生成的 classpath；natives 由调用者提供，不自动下载。
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
classpath_file="${1:-$repo_root/build/translucent-performance/classpath.txt}"
classpath="$(<"$classpath_file")"
if [[ -n "${LWJGL_NATIVE_CLASSPATH:-}" ]]; then classpath="$classpath:$LWJGL_NATIVE_CLASSPATH"; fi
probe_output="$repo_root/build/translucent-performance/linux-probes"
mkdir -p "$probe_output"
cd "$repo_root"
javac -proc:none -encoding UTF-8 -cp "$classpath" -d "$probe_output" \
    tools/translucent-performance/{BatchGlProbe,ToonTransparencyGlProbe,ToonTransparencyLayersGlProbe,DepthSealOutlineGlProbe,TransparencyStackGlProbe}.java
run_probe() {
    local probe="$1"; shift
    java -cp "$classpath:$probe_output" "com.shiroha.mmdskin.render.material.$probe" "$@"
}
run_probe ToonTransparencyLayersGlProbe --capacity-only
run_probe TransparencyStackGlProbe --close
run_probe TransparencyStackGlProbe
run_probe ToonTransparencyGlProbe
run_probe DepthSealOutlineGlProbe
run_probe ToonTransparencyLayersGlProbe
