#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
classpath_file="${1:-$repo_root/build/translucent-performance/classpath.txt}"
classpath="$(<"$classpath_file")"
cd "$repo_root"
mkdir -p build/toon-rotation
probe_output="$(mktemp -d "$repo_root/build/toon-rotation/run-XXXXXX")"
mkdir -p "$probe_output/classes"
javac -proc:none -encoding UTF-8 -cp "$classpath" -d "$probe_output/classes" \
    tools/iris-toon-probe/{ToonRotationFixtures,ToonRotationSources}.java
probe_classpath="$repo_root/common/src/main/resources:$probe_output/classes:$classpath"
java -cp "$probe_classpath" com.shiroha.mmdskin.render.shader.ToonRotationFixtures "$probe_output/fixtures"
java -cp "$probe_classpath" com.shiroha.mmdskin.render.shader.ToonRotationSources "$probe_output"
python3 tools/iris-toon-probe/toon_rotation_probe.py --fixtures "$probe_output/fixtures" --output "$probe_output/result.json"
echo "Results: $probe_output/result.json"
