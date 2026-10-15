#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../.." && pwd)"
use_egl=0
if [[ "${1:-}" == "--egl" ]]; then use_egl=1; shift; fi
classpath_file="${1:-$repo_root/build/translucent-performance/classpath.txt}"
if [[ ! -f "$classpath_file" ]]; then
  echo "runtime classpath file not found: $classpath_file" >&2
  echo "Generate it with :common:writeTranslucentProbeClasspath and validation.gradle first." >&2
  exit 2
fi
classpath="$(<"$classpath_file")"
if [[ -z "$classpath" ]]; then
  echo "runtime classpath file is empty: $classpath_file" >&2
  exit 2
fi
if [[ "$use_egl" == 0 && -z "${DISPLAY:-}" ]] && ! command -v xvfb-run >/dev/null 2>&1 && [[ -n "${LWJGL_EGL_JAR:-}" ]]; then
  use_egl=1
  echo "No DISPLAY/xvfb-run; using EGL pbuffer. Pass --egl to select this path explicitly."
fi
sources=("$script_dir/CacheGlProbe.java" "$script_dir/GpuTimerGlProbe.java")
if [[ "$use_egl" == 1 ]]; then
  : "${LWJGL_EGL_JAR:?Set LWJGL_EGL_JAR to the existing lwjgl-egl-3.3.3.jar}"
  if [[ ! -f "$LWJGL_EGL_JAR" ]]; then echo "EGL jar not found: $LWJGL_EGL_JAR" >&2; exit 2; fi
  classpath="$classpath:$LWJGL_EGL_JAR"
  sources+=("$script_dir/CacheEglProbe.java")
fi
temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
javac -proc:none -encoding UTF-8 -cp "$classpath" -d "$temp_dir" "${sources[@]}"
if [[ "$use_egl" == 1 ]]; then
  EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 MESA_SHADER_CACHE_DIR="$temp_dir/mesa-cache" \
    java -cp "$temp_dir:$classpath" CacheEglProbe
elif [[ -n "${DISPLAY:-}" ]]; then
  java -cp "$temp_dir:$classpath" CacheGlProbe
elif command -v xvfb-run >/dev/null 2>&1; then
  xvfb-run -a java -cp "$temp_dir:$classpath" CacheGlProbe
else
  echo "no DISPLAY/xvfb-run; use --egl with LWJGL_EGL_JAR for Mesa surfaceless EGL" >&2
  exit 2
fi
