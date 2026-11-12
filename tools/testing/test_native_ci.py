import argparse
import json
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ci"))

import native_ci

TEST_ROOT = Path(__file__).resolve().parent


def java_class(major=61, payload=b""):
    return b"\xca\xfe\xba\xbe\x00\x00" + major.to_bytes(2, "big") + payload


def windows_x64_pe():
    data = bytearray(0x46)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 0x3C, 0x40)
    data[0x40:0x44] = b"PE\0\0"
    struct.pack_into("<H", data, 0x44, 0x8664)
    return bytes(data)


class ForgeJarVerificationTest(unittest.TestCase):
    def write_forge_jar(self, root, *, refmap_target="Lnet/minecraft/client/Minecraft;m_123_()V",
                        mmdskin_major=61, private_data=False):
        staged = root / "staged"
        native = staged / "natives/windows-x64/mmd_engine.dll"
        native.parent.mkdir(parents=True)
        binary = windows_x64_pe()
        native.write_bytes(binary)

        jar_path = root / "mmdskin-forge-test.jar"
        files = {
            "com/shiroha/mmdskin/MmdSkin.class": java_class(),
            "com/shiroha/mmdskin/MmdSkinClient.class": java_class(payload=b"m_123_"),
            "com/shiroha/mmdskin/render/shader/ShaderProvider.class": java_class(),
            "com/shiroha/mmdskin/render/shader/SkinningComputeShader.class": java_class(mmdskin_major),
            "com/shiroha/mmdskin/shadow/javazoom/jl/player/Player.class": b"relocated",
            "assets/mmdskin/lang/en_us.json": b"{}",
            "assets/mmdskin/lang/ja_jp.json": b"{}",
            "assets/mmdskin/lang/zh_cn.json": b"{}",
            "assets/mmdskin/default_anim/idle.vmd": b"vmd",
            "META-INF/MANIFEST.MF": (
                b"Manifest-Version: 1.0\nMixinConfigs: mmdskin-forge.mixins.json\n\n"
            ),
            "META-INF/mods.toml": (
                b'modLoader = "javafml"\n[[mods]]\nmodId = "mmdskin"\n'
                b'version = "test"\ndisplayName = "test"\n'
            ),
            native_ci.FORGE_MIXIN_CONFIG: json.dumps({
                "required": True,
                "package": "com.shiroha.mmdskin.mixin.forge",
                "compatibilityLevel": "JAVA_17",
                "refmap": native_ci.FORGE_REFMAP,
                "mixins": [],
                "client": [],
            }).encode(),
            native_ci.FORGE_REFMAP: json.dumps({
                "mappings": {
                    "com/shiroha/mmdskin/mixin/forge/TestMixin": {
                        "test": refmap_target,
                    },
                },
            }).encode(),
            "natives/windows-x64/mmd_engine.dll": binary,
        }
        for member in native_ci.FORGE_LICENSES:
            files[member] = b"license text"
        if private_data:
            files["assets/mmdskin/usage_notice.json"] = b"{}"
        with zipfile.ZipFile(jar_path, "w") as jar:
            for member, payload in files.items():
                jar.writestr(member, payload)
        return jar_path, staged

    def test_accepts_forge_production_jar_without_neoforge_mojmap_marker(self):
        with tempfile.TemporaryDirectory(dir=TEST_ROOT) as temporary:
            jar, staged = self.write_forge_jar(Path(temporary))
            native_ci.verify_jar(jar, "forge", staged, "windows-x64")

    def test_rejects_forge_refmap_without_srg_method(self):
        with tempfile.TemporaryDirectory(dir=TEST_ROOT) as temporary:
            jar, staged = self.write_forge_jar(
                Path(temporary),
                refmap_target="Lnet/minecraft/client/Minecraft;tick()V",
            )
            with self.assertRaisesRegex(ValueError, "SRG method"):
                native_ci.verify_jar(jar, "forge", staged, "windows-x64")

    def test_rejects_non_java17_forge_class(self):
        with tempfile.TemporaryDirectory(dir=TEST_ROOT) as temporary:
            jar, staged = self.write_forge_jar(Path(temporary), mmdskin_major=62)
            with self.assertRaisesRegex(ValueError, "major 61"):
                native_ci.verify_jar(jar, "forge", staged, "windows-x64")

    def test_rejects_private_user_data(self):
        with tempfile.TemporaryDirectory(dir=TEST_ROOT) as temporary:
            jar, staged = self.write_forge_jar(Path(temporary), private_data=True)
            with self.assertRaisesRegex(ValueError, "private user data"):
                native_ci.verify_jar(jar, "forge", staged, "windows-x64")

    def test_explicit_forge_directory_does_not_require_unadapted_platforms(self):
        args = argparse.Namespace(fabric_dir=None, neoforge_dir=None, forge_dir="forge/build/libs")
        self.assertEqual(
            [("forge", Path("forge/build/libs"))],
            native_ci.selected_packages(args),
        )

    def test_default_package_selection_preserves_legacy_platforms(self):
        args = argparse.Namespace(fabric_dir=None, neoforge_dir=None, forge_dir=None)
        self.assertEqual(
            [
                ("fabric", Path("fabric/build/libs")),
                ("neoforge", Path("neoforge/build/libs")),
            ],
            native_ci.selected_packages(args),
        )


if __name__ == "__main__":
    unittest.main()
