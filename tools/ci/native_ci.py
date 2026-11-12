"""校验并暂存 CI 原生库，再检查最终模组 JAR。"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import struct
import tomllib
import zipfile
from pathlib import Path


ARTIFACTS = {
    "windows-x64": ("native-windows-x64", "mmd_engine.dll", "pe", 0x8664),
    "windows-arm64": ("native-windows-arm64", "mmd_engine.dll", "pe", 0xAA64),
    "linux-x64": ("native-linux-x64", "libmmd_engine.so", "elf", 62),
    "linux-arm64": ("native-linux-arm64", "libmmd_engine.so", "elf", 183),
    "linux-loongarch64": ("native-linux-loongarch64", "libmmd_engine.so", "elf", 258),
    "linux-riscv64": ("native-linux-riscv64", "libmmd_engine.so", "elf", 243),
    "macos-x64": ("native-macos-x64", "libmmd_engine.dylib", "macho", 0x01000007),
    "macos-arm64": ("native-macos-arm64", "libmmd_engine.dylib", "macho", 0x0100000C),
    "android-arm64": ("native-android-arm64", "libmmd_engine.so", "elf", 183),
    "android-amd64": ("native-android-amd64", "libmmd_engine.so", "elf", 62),
}
ROOT = Path(__file__).resolve().parents[2]
PLATFORMS = ("fabric", "neoforge", "forge")
FORGE_REFMAP = "mmdskin-forge-refmap.json"
FORGE_MIXIN_CONFIG = "mmdskin-forge.mixins.json"
FORGE_CLASS_MAJOR = 61
FORGE_LICENSES = {
    "META-INF/licenses/mmdskin/LICENSE",
    "META-INF/licenses/mmdskin/LICENSE-CC-BY-NC-SA-4.0",
    "META-INF/licenses/mmdskin/LICENSE-MIT",
    "META-INF/licenses/mmdskin/LICENSE-Ray-MMD",
    "META-INF/licenses/mmdskin/NOTICE",
}
SRG_METHOD = re.compile(r"(?:^|;)m_\d+_\(")
SRG_TOKEN = re.compile(rb"m_\d+_")


def digest(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()


def machine(path: Path, kind: str) -> int:
    data = path.read_bytes()
    if kind == "pe":
        if len(data) < 64 or data[:2] != b"MZ":
            raise ValueError(f"{path} is not a PE binary")
        offset = struct.unpack_from("<I", data, 0x3C)[0]
        if offset + 6 > len(data) or data[offset : offset + 4] != b"PE\0\0":
            raise ValueError(f"{path} has no valid PE header")
        return struct.unpack_from("<H", data, offset + 4)[0]
    if kind == "elf":
        if len(data) < 20 or data[:4] != b"\x7fELF" or data[5] != 1:
            raise ValueError(f"{path} is not a little-endian ELF binary")
        return struct.unpack_from("<H", data, 18)[0]
    if kind == "macho":
        if len(data) < 8:
            raise ValueError(f"{path} is too small to be a Mach-O binary")
        magic = data[:4]
        endian = "<" if magic == b"\xcf\xfa\xed\xfe" else ">" if magic == b"\xfe\xed\xfa\xcf" else None
        if endian is None:
            raise ValueError(f"{path} is not a 64-bit Mach-O binary")
        return struct.unpack_from(endian + "I", data, 4)[0]
    raise ValueError(f"Unknown binary format: {kind}")


def check_binary(path: Path, kind: str, expected: int) -> None:
    if not path.is_file() or path.stat().st_size == 0:
        raise ValueError(f"Missing or empty native binary: {path}")
    actual = machine(path, kind)
    if actual != expected:
        raise ValueError(f"Wrong architecture for {path}: machine={actual:#x}, expected={expected:#x}")


def artifact(args: argparse.Namespace) -> None:
    name, filename, kind, expected = ARTIFACTS[args.arch]
    binary = Path(args.binary).resolve()
    check_binary(binary, kind, expected)
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(binary, output / filename)
    if args.libcxx:
        if not args.arch.startswith("android-"):
            raise ValueError("libc++_shared.so is only expected for Android")
        libcxx = Path(args.libcxx).resolve()
        check_binary(libcxx, "elf", expected)
        shutil.copy2(libcxx, output / "libc++_shared.so")
    elif args.arch.startswith("android-"):
        raise ValueError("Android artifacts must include libc++_shared.so")
    print(f"Prepared {name}/{filename} ({binary.stat().st_size} bytes)")


def stage(args: argparse.Namespace) -> None:
    artifact_root = Path(args.artifact_root).resolve()
    destination = Path(args.destination).resolve()
    build_root = (ROOT / "build").resolve()
    if destination == build_root or build_root not in destination.parents:
        raise ValueError(f"Staging destination must be a child of {build_root}: {destination}")
    if destination == artifact_root or destination in artifact_root.parents:
        raise ValueError("Staging destination cannot contain or replace the artifact input directory")

    # 先验证所选平台的全部输入，避免失败时留下部分 staging。
    sources: dict[str, Path] = {}
    artifacts = ARTIFACTS if args.arch is None else {args.arch: ARTIFACTS[args.arch]}
    for arch, (artifact_name, filename, kind, expected) in artifacts.items():
        source_dir = artifact_root / artifact_name
        source = source_dir / filename
        check_binary(source, kind, expected)
        sources[f"natives/{arch}/{filename}"] = source
        if arch.startswith("android-"):
            libcxx = source_dir / "libc++_shared.so"
            check_binary(libcxx, "elf", expected)
            sources[f"natives/{arch}/libc++_shared.so"] = libcxx

    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    for relative, source in sources.items():
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        if digest(source.read_bytes()) != digest(target.read_bytes()):
            raise ValueError(f"Staged native does not match artifact source: {relative}")
    print(f"Staged and verified {len(artifacts)} native architectures at {destination}")


def jar_candidates(directory: Path, prefix: str) -> list[Path]:
    candidates = [
        path
        for path in directory.glob(f"{prefix}*.jar")
        if not any(token in path.name for token in ("-dev", "-sources", "-universal", "-slim"))
        and not any(path.stem.endswith(f"-{arch}") for arch in ARTIFACTS)
    ]
    if len(candidates) != 1:
        raise ValueError(f"Expected one release JAR in {directory}, found {len(candidates)}")
    return candidates


def manifest_attributes(payload: bytes) -> dict[str, str]:
    lines = payload.decode("utf-8").replace("\r\n", "\n").splitlines()
    unfolded: list[str] = []
    for line in lines:
        if line.startswith(" "):
            if not unfolded:
                raise ValueError("Manifest starts with a continuation line")
            unfolded[-1] += line[1:]
        else:
            unfolded.append(line)
    attributes = {}
    for line in unfolded:
        if not line:
            continue
        if ":" not in line:
            raise ValueError(f"Malformed manifest line: {line}")
        key, value = line.split(":", 1)
        attributes[key.lower()] = value.strip()
    return attributes


def class_major(payload: bytes, member: str) -> int:
    if len(payload) < 8 or payload[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError(f"{member} is not a valid Java class file")
    return int.from_bytes(payload[6:8], "big")


def forge_refmap_targets(refmap: object, path: Path) -> list[str]:
    if not isinstance(refmap, dict):
        raise ValueError(f"{path} Forge refmap is not a JSON object")
    mappings = refmap.get("mappings")
    if not isinstance(mappings, dict) or not mappings:
        raise ValueError(f"{path} Forge refmap has no class mappings")
    targets = []
    for class_name, class_mappings in mappings.items():
        if not isinstance(class_mappings, dict) or not class_mappings:
            raise ValueError(f"{path} Forge refmap has no mappings for {class_name}")
        for source_name, target in class_mappings.items():
            if not isinstance(target, str) or not target:
                raise ValueError(f"{path} Forge refmap mapping is empty: {class_name}.{source_name}")
            targets.append(target)
    if not any(SRG_METHOD.search(target) for target in targets):
        raise ValueError(f"{path} Forge refmap contains no production SRG method mappings")
    return targets


def verify_forge_jar(jar: zipfile.ZipFile, path: Path, members: set[str]) -> None:
    manifest = manifest_attributes(jar.read("META-INF/MANIFEST.MF"))
    mixin_configs = {
        item.strip() for item in manifest.get("mixinconfigs", "").split(",") if item.strip()
    }
    if FORGE_MIXIN_CONFIG not in mixin_configs:
        raise ValueError(f"{path} manifest MixinConfigs does not list {FORGE_MIXIN_CONFIG}")

    mixin_config = json.loads(jar.read(FORGE_MIXIN_CONFIG))
    if not isinstance(mixin_config, dict):
        raise ValueError(f"{path} Forge Mixin config is not a JSON object")
    if mixin_config.get("refmap") != FORGE_REFMAP:
        raise ValueError(f"{path} Forge Mixin config does not reference {FORGE_REFMAP}")
    if mixin_config.get("compatibilityLevel") != "JAVA_17":
        raise ValueError(f"{path} Forge Mixin config must target JAVA_17")

    tomllib.loads(jar.read("META-INF/mods.toml").decode("utf-8"))
    forge_refmap_targets(json.loads(jar.read(FORGE_REFMAP)), path)

    owned_classes = [
        name
        for name in members
        if name.endswith(".class")
        and name.startswith("com/shiroha/mmdskin/")
        and not name.startswith("com/shiroha/mmdskin/shadow/")
    ]
    if not owned_classes:
        raise ValueError(f"{path} contains no MMD Skin class files")
    class_payloads = {name: jar.read(name) for name in owned_classes}
    wrong_major = {
        name: major
        for name, payload in class_payloads.items()
        if (major := class_major(payload, name)) != FORGE_CLASS_MAJOR
    }
    if wrong_major:
        sample = ", ".join(f"{name}={major}" for name, major in sorted(wrong_major.items())[:3])
        raise ValueError(f"{path} Forge classes are not Java 17 (major 61): {sample}")
    if not any(SRG_TOKEN.search(payload) for payload in class_payloads.values()):
        raise ValueError(f"{path} Forge classes contain no production SRG method references")


def verify_jar(path: Path, platform: str, staged: Path, arch: str | None = None) -> None:
    if platform not in PLATFORMS:
        raise ValueError(f"Unknown platform: {platform}")
    artifacts = ARTIFACTS if arch is None else {arch: ARTIFACTS[arch]}
    with zipfile.ZipFile(path) as jar:
        members = set(jar.namelist())
        required = {
            "com/shiroha/mmdskin/MmdSkin.class",
            "com/shiroha/mmdskin/MmdSkinClient.class",
            "assets/mmdskin/lang/en_us.json",
            "assets/mmdskin/lang/ja_jp.json",
            "assets/mmdskin/lang/zh_cn.json",
            "assets/mmdskin/default_anim/idle.vmd",
            "com/shiroha/mmdskin/render/shader/ShaderProvider.class",
            "com/shiroha/mmdskin/render/shader/SkinningComputeShader.class",
        } | FORGE_LICENSES
        if platform == "fabric":
            required.update({"fabric.mod.json", "mmdskin-fabric-refmap.json"})
        elif platform == "neoforge":
            required.add("META-INF/neoforge.mods.toml")
        else:
            required.update({"META-INF/MANIFEST.MF", "META-INF/mods.toml", FORGE_REFMAP, FORGE_MIXIN_CONFIG})
        missing = sorted(required - members)
        if missing:
            raise ValueError(f"{path} is missing required mod content: {', '.join(missing)}")
        for member in required:
            if not jar.read(member):
                raise ValueError(f"{path} contains an empty required resource: {member}")

        languages = ["assets/mmdskin/lang/en_us.json", "assets/mmdskin/lang/ja_jp.json", "assets/mmdskin/lang/zh_cn.json"]
        for member in languages:
            json.loads(jar.read(member))
        if platform == "fabric":
            json.loads(jar.read("fabric.mod.json"))
            refmap = json.loads(jar.read("mmdskin-fabric-refmap.json"))
            mappings = refmap.get("mappings") if isinstance(refmap, dict) else None
            if not isinstance(mappings, dict) or not any(
                isinstance(class_mappings, dict) and class_mappings
                for class_mappings in mappings.values()
            ):
                raise ValueError(f"{path} Fabric refmap is empty")
        elif platform == "neoforge":
            tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode("utf-8"))
            class_files = [name for name in members if name.endswith(".class") and name.startswith("com/shiroha/mmdskin/")]
            if not any(
                b"net/minecraft/client/Minecraft" in jar.read(name)
                or b"net/minecraft/world/entity/player/Player" in jar.read(name)
                for name in class_files
            ):
                raise ValueError(f"{path} contains no recognizable NeoForge Mojmap class reference")
        else:
            verify_forge_jar(jar, path, members)

        relocated = [name for name in members if name.startswith("com/shiroha/mmdskin/shadow/javazoom/jl/") and name.endswith(".class")]
        bare = [name for name in members if name.startswith("javazoom/") and name.endswith(".class")]
        if not relocated or bare:
            raise ValueError(f"{path} jlayer relocation invalid: relocated={len(relocated)}, bare={len(bare)}")
        private_members = [
            name for name in members if name.rsplit("/", 1)[-1].lower() == "usage_notice.json"
        ]
        if private_members:
            raise ValueError(f"{path} contains private user data: {', '.join(sorted(private_members))}")

        expected_natives = {
            f"natives/{arch}/{filename}"
            for arch, (_, filename, _, _) in artifacts.items()
        }
        expected_natives.update(
            f"natives/{arch}/libc++_shared.so"
            for arch in artifacts if arch.startswith("android-")
        )
        actual_natives = {name for name in members if name.startswith("natives/") and not name.endswith("/")}
        if actual_natives != expected_natives:
            raise ValueError(
                f"{path} native entries differ from selected architectures: "
                f"missing={sorted(expected_natives - actual_natives)}, "
                f"unexpected={sorted(actual_natives - expected_natives)}"
            )

        for arch, (_, filename, kind, expected) in artifacts.items():
            member = f"natives/{arch}/{filename}"
            if member not in members:
                raise ValueError(f"{path} is missing {member}")
            payload = jar.read(member)
            staged_file = staged / member
            if digest(payload) != digest(staged_file.read_bytes()):
                raise ValueError(f"{path} does not contain the staged native unchanged: {member}")
            temp = path.parent / f".{path.name}.{filename}.check"
            try:
                temp.write_bytes(payload)
                check_binary(temp, kind, expected)
            finally:
                temp.unlink(missing_ok=True)
            if arch.startswith("android-"):
                libcxx_member = f"natives/{arch}/libc++_shared.so"
                if libcxx_member not in members:
                    raise ValueError(f"{path} is missing {libcxx_member}")
                libcxx_payload = jar.read(libcxx_member)
                if digest(libcxx_payload) != digest((staged / libcxx_member).read_bytes()):
                    raise ValueError(f"{path} does not contain staged libc++ unchanged: {libcxx_member}")
                temp.write_bytes(libcxx_payload)
                try:
                    check_binary(temp, "elf", expected)
                finally:
                    temp.unlink(missing_ok=True)


def selected_packages(args: argparse.Namespace) -> list[tuple[str, Path]]:
    directories = {
        "fabric": args.fabric_dir,
        "neoforge": args.neoforge_dir,
        "forge": args.forge_dir,
    }
    selected = [
        (platform, Path(directory))
        for platform, directory in directories.items()
        if directory is not None
    ]
    if selected:
        return selected
    return [
        ("fabric", Path("fabric/build/libs")),
        ("neoforge", Path("neoforge/build/libs")),
    ]


def package(args: argparse.Namespace) -> None:
    staged = Path(args.staged).resolve()
    artifacts = ARTIFACTS if args.arch is None else {args.arch: ARTIFACTS[args.arch]}
    for arch, (_, filename, kind, expected) in artifacts.items():
        check_binary(staged / "natives" / arch / filename, kind, expected)

    outputs = []
    for platform, directory in selected_packages(args):
        prefix = f"mmdskin-{platform}-"
        jar = jar_candidates(directory, prefix)[0]
        verify_jar(jar, platform, staged, args.arch)
        classifier = args.arch or "universal"
        packaged = jar.with_name(f"{jar.stem}-{classifier}{jar.suffix}")
        shutil.copy2(jar, packaged)
        verify_jar(packaged, platform, staged, args.arch)
        outputs.append(packaged)
    print("Verified native resources in these mod artifacts:")
    for path in outputs:
        print(f"  {path}")


def main() -> None:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    artifact_parser = commands.add_parser("artifact")
    artifact_parser.add_argument("--arch", choices=ARTIFACTS, required=True)
    artifact_parser.add_argument("--binary", required=True)
    artifact_parser.add_argument("--output", default="build/ci-native-artifact")
    artifact_parser.add_argument("--libcxx")
    artifact_parser.set_defaults(func=artifact)
    stage_parser = commands.add_parser("stage")
    stage_parser.add_argument("--artifact-root", default="native-libs")
    stage_parser.add_argument("--destination", default="build/ci-native-resources")
    stage_parser.add_argument("--arch", choices=ARTIFACTS, help="Stage only this platform (default: all)")
    stage_parser.set_defaults(func=stage)
    package_parser = commands.add_parser("package")
    package_parser.add_argument("--staged", default="build/ci-native-resources")
    package_parser.add_argument("--fabric-dir")
    package_parser.add_argument("--neoforge-dir")
    package_parser.add_argument("--forge-dir")
    package_parser.add_argument("--arch", choices=ARTIFACTS, help="Package only this platform (default: universal)")
    package_parser.set_defaults(func=package)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
