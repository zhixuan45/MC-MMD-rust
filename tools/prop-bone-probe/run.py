"""Exercise production Java parenting against the real JNI DLL and synthetic PMX/VMD.

All assets are generated in build/prop-bone-probe; no user asset is read or changed.
Requires the release native library and cached Gradle Gson/JOML jars.
"""
import argparse
import math
import os
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def pmx_fixture(path):
    def text(value):
        data = value.encode("utf-8")
        return struct.pack("<i", len(data)) + data

    def floats(*values):
        return struct.pack("<" + "f" * len(values), *values)

    data = b"PMX " + floats(2.0) + bytes([8, 1, 0, 1, 1, 1, 1, 1, 1])
    data += text("Synthetic fan controls") + text("") + text("JNI fixture") + text("")
    data += struct.pack("<i", 3)
    for position in [(0, 1, 0), (1, 1, 0), (0, 2, 0)]:
        data += floats(*position, 0, 0, 1, 0, 0) + bytes([0, 1]) + floats(1)
    data += struct.pack("<i", 3) + bytes([0, 1, 2]) + struct.pack("<i", 0)
    data += struct.pack("<i", 1) + text("fixture") + text("")
    data += floats(1, 1, 1, 1, 0, 0, 0, 1, .5, .5, .5)
    data += bytes([0]) + floats(0, 0, 0, 1, 1)
    data += bytes([255, 255, 0, 1, 0]) + text("") + struct.pack("<i", 3)
    data += struct.pack("<i", 4)
    for name, position, parent in [
        ("Root", (0, 0, 0), -1),
        ("Center", (0, 1, 0), 0),
        ("Tip", (1, 1, 0), 1),
        ("Tassel", (1, 0, 0), 2),
    ]:
        data += text(name) + text("") + floats(*position)
        data += struct.pack("<biH", parent, 0, 0x1E) + floats(0, 1, 0)
    data += struct.pack("<ii", 0, 0)  # morphs, display frames
    data += struct.pack("<i", 2)
    for name, bone, position, mode in [
        ("Anchor", 1, (0, 1, 0), 0),
        ("TasselBody", 3, (1, 0, 0), 1),
    ]:
        data += text(name) + text("") + struct.pack("<bBHB", bone, 0, 0xFFFF, 0)
        data += floats(.1, 0, 0, *position, 0, 0, 0, 1, .05, .05, 0, .5)
        data += bytes([mode])
    data += struct.pack("<i", 1) + text("String") + text("") + bytes([0, 0, 1])
    data += floats(1, 1, 0, 0, 0, 0)
    data += floats(0, 0, 0, 0, 0, 0, -math.pi, -math.pi, -math.pi, math.pi, math.pi, math.pi)
    data += floats(0, 0, 0, 0, 0, 0)
    path.write_bytes(data)


def vmd_fixture(path):
    data = b"Vocaloid Motion Data 0002".ljust(30, b"\0") + b"fixture".ljust(20, b"\0")
    data += struct.pack("<I", 5)
    for name, frame, translation, rotation in [
        ("Root", 0, (0, 0, 0), (0, 0, 0, 1)),
        ("Root", 60, (6, 0, 0), (0, 0, 0, 1)),
        ("Root", 120, (6, 0, 0), (0, 0, 0, 1)),
        ("Center", 0, (2, 0, 1), (0, math.sin(.2), 0, math.cos(.2))),
        ("Center", 60, (4, 1, -2), (0, 0, math.sqrt(.5), math.sqrt(.5))),
    ]:
        data += name.encode("shift_jis").ljust(15, b"\0") + struct.pack("<I3f4f", frame, *translation, *rotation)
        data += bytes([20] * 8 + [107] * 8 + [0] * 48)
    data += struct.pack("<5I", 0, 0, 0, 0, 0)
    path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, default=Path(r"C:\Program Files\Zulu\zulu-17"))
    parser.add_argument("--dll", type=Path, default=ROOT / "rust_engine/target/release/mmd_engine.dll")
    args = parser.parse_args()
    output = ROOT / "build/prop-bone-probe"
    output.mkdir(parents=True, exist_ok=True)
    pmx_fixture(output / "fixture.pmx")
    vmd_fixture(output / "fixture.vmd")
    shim = output / "NativeLibraryLoader.java"
    shim.write_text("""package com.shiroha.mmdskin;
final class NativeLibraryLoader {
    static boolean isAndroid() { return false; }
    static void loadAndVerify(NativeFunc api) { throw new UnsupportedOperationException("Use explicit DLL"); }
}
""", encoding="utf-8")
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    jars = []
    for group, name in [("com.google.code.gson", "gson"), ("org.joml", "joml")]:
        candidates = [p for p in (cache / group / name).rglob("*.jar")
                      if not any(s in p.name for s in ("sources", "javadoc"))]
        if not candidates:
            raise SystemExit(f"Missing cached dependency: {name}")
        jars.append(str(max(candidates, key=lambda p: p.stat().st_mtime)))
    classpath = os.pathsep.join(jars)
    production = ROOT / "common/src/main/java/com/shiroha/mmdskin"
    sources = ["NativeFunc.java", "bridge/runtime/SphereMaterialNative.java", "bridge/runtime/NativeMatrixPort.java",
               "bridge/runtime/NativeModelQueryPort.java", "player/render/PropBoneParenting.java",
               "bridge/runtime/PropBoneBindingAdapter.java", "stage/domain/timeline/BoneParentMath.java",
               "stage/domain/timeline/PropTimeline.java"]
    subprocess.run([str(args.jdk / "bin/javac.exe"), "-encoding", "UTF-8", "-proc:none",
                    "-cp", classpath, "-d", str(output / "classes"), str(shim),
                    *[str(production / p) for p in sources], str(Path(__file__).with_name("Probe.java"))],
                   cwd=ROOT, check=True)
    subprocess.run([str(args.jdk / "bin/java.exe"), "-cp", f"{output / 'classes'}{os.pathsep}{classpath}",
                    "Probe", str(args.dll.resolve()), str(output)], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
