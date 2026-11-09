"""Run the production stage controller, preview motion binding and parenting against real JNI.

Requires :common:classes and :common:writeStagePropProbeClasspath with the accompanying init script.
Uses synthetic assets only. No Minecraft renderer or user model is loaded.
"""
import argparse
import importlib.util
import math
import os
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def motion(path, end, actor):
    data = b"Vocaloid Motion Data 0002".ljust(30, b"\0") + b"fixture".ljust(20, b"\0")
    data += struct.pack("<I", 4)
    for name, frame, translation, rotation in [
        ("Root", 0, (0, 0, 0), (0, 0, 0, 1)),
        ("Root", end, (6, 2, 3), (0, math.sin(.6), 0, math.cos(.6))),
        ("Center", 0, (2, 0, 1), (0, math.sin(.2), 0, math.cos(.2))),
        ("Center", end, (4, 1, -2), (0, 0, math.sqrt(.5), math.sqrt(.5))),
    ]:
        if actor and name == "Center":
            translation = (translation[0] * 2, translation[1], translation[2])
        data += name.encode("shift_jis").ljust(15, b"\0")
        data += struct.pack("<I3f4f", frame, *translation, *rotation)
        data += bytes([20] * 8 + [107] * 8 + [0] * 48)
    data += struct.pack("<5I", 0, 0, 0, 0, 0)
    path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, default=Path(r"C:\Program Files\Zulu\zulu-21"))
    parser.add_argument("--dll", type=Path, default=ROOT / "rust_engine/target/release/mmd_engine.dll")
    parser.add_argument("--frame-scale", type=int, default=1, help="Scale authored motion and registration times")
    args = parser.parse_args()
    if args.frame_scale < 1:
        parser.error("--frame-scale must be positive")
    output = ROOT / "build/stage-prop-probe"
    output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location("fixtures", ROOT / "tools/prop-bone-probe/run.py")
    fixtures = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixtures)
    fixtures.pmx_fixture(output / "actor.pmx")
    fixtures.pmx_fixture(output / "fan.pmx")
    motion(output / "actor.vmd", 60 * args.frame_scale, True)
    motion(output / "prop.vmd", 240 * args.frame_scale, False)
    classpath = (output / "classpath.txt").read_text().strip()
    classes = output / "classes"
    subprocess.run([str(args.jdk / "bin/javac.exe"), "-encoding", "UTF-8", "-proc:none",
                    "-cp", classpath, "-d", str(classes), str(Path(__file__).with_name("Probe.java"))],
                   cwd=ROOT, check=True)
    subprocess.run([str(args.jdk / "bin/java.exe"), "-Djava.awt.headless=true",
                    "-cp", os.pathsep.join([str(classes), classpath]), "Probe",
                    str(args.dll.resolve()), str(output), str(args.frame_scale)], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
