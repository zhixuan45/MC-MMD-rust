"""Check Java native declarations against Rust JNI exports without building.

Run from any directory: python doc/check_jni_consistency.py
Checks names, duplicate exports, parameter counts, primitive/array ABI types,
and the shared panic boundary on every export.
This is a source consistency check; the real-DLL probes also test linkage.
"""
from collections import Counter
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "common/src/main/java"
RUST = ROOT / "rust_engine/src/jni_bridge"
JAVA_TYPES = {
    "void": "void", "boolean": "jboolean", "byte": "jbyte",
    "int": "jint", "long": "jlong", "float": "jfloat", "double": "jdouble",
    "String": "jstring", "ByteBuffer": "JByteBuffer",
    "int[]": "JIntArray", "float[]": "JFloatArray", "byte[]": "JByteArray",
}


def without_comments(text):
    return re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)


def java_type(value):
    value = value.rsplit(".", 1)[-1]
    return JAVA_TYPES.get(value, value)

def rust_type(value):
    value = value.strip().split("<", 1)[0].rsplit("::", 1)[-1]
    return {"JString": "jstring", "JObject": "JByteBuffer"}.get(value, value)


def check():
    declarations = {}
    errors = []
    for path in JAVA.rglob("*.java"):
        text = without_comments(path.read_text(encoding="utf-8-sig"))
        methods = list(re.finditer(
            r"\bnative\s+([\w.\[\]]+)\s+(\w+)\s*\((.*?)\)\s*;", text, re.S))
        if not methods:
            continue
        package = re.search(r"\bpackage\s+([\w.]+)\s*;", text).group(1)
        class_name = (package + "." + path.stem).replace("_", "_1").replace(".", "_")
        for method in methods:
            name = "Java_" + class_name + "_" + method[2].replace("_", "_1")
            params = [
                java_type(param.strip().rsplit(None, 1)[0])
                for param in method[3].split(",") if param.strip()
            ]
            if name in declarations:
                errors.append(f"Overloaded native method needs a JNI signature suffix: {name}")
            declarations[name] = (java_type(method[1]), params)

    exports = {}
    counts = Counter()
    for path in RUST.rglob("*.rs"):
        text = without_comments(path.read_text(encoding="utf-8-sig"))
        for method in re.finditer(
            r'pub\s+(?:unsafe\s+)?extern\s+"system"\s+fn\s+(Java_\w+)'
            r"(?:<[^>]*>)?\s*\((.*?)\)\s*(?:->\s*([\w:*]+))?\s*\{", text, re.S):
            name = method[1]
            counts[name] += 1
            if not text[method.end():].lstrip().startswith("jni_guard!("):
                errors.append(f"Missing shared panic boundary: {name}")
            params = [
                rust_type(param.strip().split(":", 1)[1])
                for param in method[2].split(",") if param.strip()
            ]
            exports[name] = (rust_type(method[3] or "void"), params[2:])

    for name in sorted(declarations.keys() - exports.keys()):
        errors.append(f"Missing Rust export: {name}")
    for name in sorted(exports.keys() - declarations.keys()):
        errors.append(f"Undeclared Rust export: {name}")
    for name, count in sorted(counts.items()):
        if count != 1:
            errors.append(f"Duplicate Rust export ({count}): {name}")
    for name in sorted(declarations.keys() & exports.keys()):
        if declarations[name] != exports[name]:
            errors.append(f"ABI mismatch: {name}\n  Java {declarations[name]}\n  Rust {exports[name]}")
    for error in errors:
        print(error)
    print(f"JNI declarations: {len(declarations)}, exports: {len(exports)}, errors: {len(errors)}")
    return bool(errors)


if __name__ == "__main__":
    sys.exit(check())
