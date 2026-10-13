#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
output_dir="${1:-/tmp/mmd-locked-axis-diagnosis}"
mkdir -p "$output_dir"
mapfile -t archives < <(rg --files --no-ignore rust_engine/target/debug/build | rg '/libbullet3\.a$')
if (( ${#archives[@]} != 1 )); then
    echo 'Expected one Bullet archive; compile the Rust diagnostic example first.' >&2
    exit 1
fi
g++ -std=c++17 -O2 -fno-exceptions -fno-rtti \
    -I rust_engine/deps/bullet3/src tools/physics-diagnosis/locked_axis.cpp \
    "${archives[0]}" -o "$output_dir/locked-axis"
"$output_dir/locked-axis" | tee "$output_dir/locked-axis.jsonl"
