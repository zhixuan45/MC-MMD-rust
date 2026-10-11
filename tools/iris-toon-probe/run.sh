#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 3 ]]; then
    echo 'Usage: bash tools/iris-toon-probe/run.sh SHADERPACK_DIRECTORY IRIS_JAR LWJGL_EGL_3.3.3_JAR' >&2
    exit 2
fi
task_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
task_packs=$(realpath "$1")
task_iris=$(realpath "$2")
task_egl=$(realpath "$3")
task_cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
cd "$task_root"
mkdir -p build/iris-toon-probe/classes

# 只编译渲染模块的真实源码，不需要启动 Minecraft。
python3 - "$task_cache" "$task_iris" "$task_egl" <<'PY'
import pathlib, re, sys, zipfile
cache, iris, egl = map(pathlib.Path, sys.argv[1:])
output = pathlib.Path('build/iris-toon-probe')
with zipfile.ZipFile(iris) as archive:
    entries = [n for n in archive.namelist() if re.search(r'/jcpp-[^/]+\.jar$', n)]
    if len(entries) != 1:
        raise SystemExit('Expected one bundled Iris JCPP dependency')
    (output / 'jcpp.jar').write_bytes(archive.read(entries[0]))
classpath = [iris, egl, (output / 'jcpp.jar').resolve(), pathlib.Path('common/src/main/resources').resolve()]
for module in ['lwjgl', 'lwjgl-glfw', 'lwjgl-opengl']:
    for filename in [f'{module}-3.3.3.jar', f'{module}-3.3.3-natives-linux.jar']:
        jars = list((cache / 'org.lwjgl' / module / '3.3.3').rglob(filename))
        if len(jars) != 1:
            raise SystemExit(f'Missing or ambiguous dependency: {filename}')
        classpath.append(jars[0])
for group, module in [('org.apache.logging.log4j', 'log4j-api'), ('org.apache.logging.log4j', 'log4j-core'),
                      ('org.slf4j', 'slf4j-api'), ('com.google.guava', 'guava')]:
    versions = sorted((cache / group / module).iterdir(), key=lambda p: tuple(map(int, re.findall(r'\d+', p.name))))
    jars = list(versions[-1].rglob(f'{module}-{versions[-1].name}.jar'))
    if len(jars) != 1:
        raise SystemExit(f'Missing or ambiguous dependency: {module}')
    classpath.append(jars[0])
(output / 'classpath.txt').write_text(':'.join(map(str, classpath)))
PY
task_cp=$(cat build/iris-toon-probe/classpath.txt)
task_shader_dir=common/src/main/java/com/shiroha/mmdskin/render/shader
javac -proc:none -encoding UTF-8 -cp "$task_cp" -d build/iris-toon-probe/classes \
    common/src/main/java/com/shiroha/mmdskin/util/AssetsUtil.java \
    common/src/main/java/com/shiroha/mmdskin/render/outline/{OutlineCoverage,OutlineGlState,OutlineRenderPass}.java \
    common/src/main/java/com/shiroha/mmdskin/config/{ConfigManager,IRenderConfig,IToonConfig,IPhysicsConfig,IVRConfig,PhysicsCollisionStabilityMode,PaperDollPosition,PaperDollDisplayMode,PaperDollRotationMode}.java \
    "$task_shader_dir"/ToonConfig.java \
    "$task_shader_dir"/{ShaderCompiler,ToonOutputProfile,ComplementaryToonLighting,ToonUniformBridge,ToonShaderBase,ToonShaderCpu}.java \
    tools/iris-toon-probe/{Probe,OverlayProbe,LightingProbe,ToonControlsProbe,UniformBridgeProbe,OutlineCoverageProbe,EglProbe}.java
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 \
    MESA_SHADER_CACHE_DIR="$task_root/build/iris-toon-probe/mesa-cache" \
    java -cp "build/iris-toon-probe/classes:$task_cp" EglProbe "$task_packs"
