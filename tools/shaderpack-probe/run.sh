#!/usr/bin/env bash
set -euo pipefail
# PROBE_CP: Iris jar, its nested jcpp jar, log4j-api, slf4j-api, and Guava.
: "${PROBE_CP:?Set PROBE_CP to the Iris/Jcpp/logging/Guava classpath}"
if (( $# == 0 )); then
    printf 'Usage: PROBE_CP=... bash tools/shaderpack-probe/run.sh shader.zip ...\n' >&2
    exit 2
fi
task_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
task_packs=()
for task_pack in "$@"; do task_packs+=("$(realpath "$task_pack")"); done
cd "$task_root"
mkdir -p build/shaderpack-probe
task_run=$(mktemp -d "$task_root/build/shaderpack-probe/run-XXXXXXXX")
mkdir -p "$task_run/classes"
task_java=${JAVA_HOME:+$JAVA_HOME/bin/}java
task_javac=${JAVA_HOME:+$JAVA_HOME/bin/}javac
"$task_javac" -proc:none -encoding UTF-8 -cp "$PROBE_CP" -d "$task_run/classes" \
    common/src/main/java/com/shiroha/mmdskin/render/shader/ToonOutputProfile.java \
    common/src/main/java/com/shiroha/mmdskin/render/shader/ComplementaryToonLighting.java \
    tools/shaderpack-probe/SourceProbe.java
"$task_java" -cp "$task_run/classes:$PROBE_CP" SourceProbe "$task_run/sources" "${task_packs[@]}"
XDG_CACHE_HOME="$task_run/cache" python3 tools/shaderpack-probe/check_glsl.py "$task_run/sources"
