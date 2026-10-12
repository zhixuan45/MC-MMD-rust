#!/usr/bin/env python3
"""只读审计运行实例 PMX 的眼部材质、UV 网格岛与骨骼权重。"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import struct
from collections import defaultdict

from PIL import Image, ImageDraw


DEFAULT_ROOT = pathlib.Path(
    r"D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252\3d-skin\EntityPlayer"
)
DEFAULT_OUTPUT = pathlib.Path("logs/iris-toon-unbound-20261011")
EYE_TERMS = ("eye", "iris", "pupil", "sclera", "white", "lens", "glass", "瞳", "眼", "目", "虹彩", "镜片", "鏡片")
EXCLUDED_MATERIAL_TERMS = ("lash", "brow", "lid", "睫", "眉", "睫毛", "まつげ", "まぶた")
MASK_EXCLUDED = ("brow", "lash", "lid", "眉", "睫", "瞼", "縁", "まぶた")


class Reader:
    def __init__(self, data: bytes):
        self.data, self.offset = data, 0

    def read(self, fmt: str):
        size = struct.calcsize(fmt)
        if self.offset + size > len(self.data):
            raise ValueError(f"文件截断 offset={self.offset} size={size}")
        values = struct.unpack_from(fmt, self.data, self.offset)
        self.offset += size
        return values[0] if len(values) == 1 else values

    def bytes(self, size: int) -> bytes:
        if size < 0 or self.offset + size > len(self.data):
            raise ValueError(f"字段越界 offset={self.offset} size={size}")
        value = self.data[self.offset : self.offset + size]
        self.offset += size
        return value

    def text(self, encoding: int) -> str:
        size = self.read("<i")
        return self.bytes(size).decode("utf-16le" if encoding == 0 else "utf-8", errors="replace")

    def index(self, size: int, signed: bool = True) -> int:
        kind = {1: "b" if signed else "B", 2: "h" if signed else "H", 4: "i" if signed else "I"}[size]
        return self.read("<" + kind)


def parse_pmx(path: pathlib.Path) -> dict:
    reader = Reader(path.read_bytes())
    if reader.bytes(4) != b"PMX ":
        raise ValueError("不是 PMX")
    version = reader.read("<f")
    globals_ = reader.bytes(reader.read("<B"))
    if len(globals_) < 8:
        raise ValueError("PMX globals 无效")
    encoding, extra_uv, vertex_size, texture_size, _mat_size, bone_size, _morph_size, _body_size = globals_[:8]
    local_name, english_name = reader.text(encoding), reader.text(encoding)
    reader.text(encoding)
    reader.text(encoding)

    vertices = []
    vertex_count = reader.read("<i")
    for _ in range(vertex_count):
        position = reader.read("<3f")
        normal = reader.read("<3f")
        uv = reader.read("<2f")
        reader.bytes(16 * extra_uv)
        deform = reader.read("<B")
        bones, weights = [-1] * 4, [0.0] * 4
        if deform == 0:
            bones[0], weights[0] = reader.index(bone_size), 1.0
        elif deform in (1, 3):
            bones[:2] = (reader.index(bone_size), reader.index(bone_size))
            weights[:2] = (reader.read("<f"), 0.0)
            weights[1] = 1.0 - weights[0]
            if deform == 3:
                reader.bytes(36)
        elif deform in (2, 4):
            bones = [reader.index(bone_size) for _ in range(4)]
            weights = list(reader.read("<4f"))
        else:
            raise ValueError(f"unknown deform type {deform}")
        reader.read("<f")
        vertices.append({"position": position, "uv": uv, "bones": bones, "weights": weights})

    faces_count = reader.read("<i")
    # PMX 面顶点索引是 unsigned；骨骼索引继续按 signed 解码。
    faces = [reader.index(vertex_size, signed=False) for _ in range(faces_count)]
    textures = [reader.text(encoding) for _ in range(reader.read("<i"))]
    materials = []
    for material_id in range(reader.read("<i")):
        name, english = reader.text(encoding), reader.text(encoding)
        diffuse = reader.read("<4f")
        specular = reader.read("<3f")
        power = reader.read("<f")
        ambient = reader.read("<3f")
        flags = reader.read("<B")
        edge_color = reader.read("<4f")
        edge_size = reader.read("<f")
        texture_index = reader.index(texture_size)
        sphere_index = reader.index(texture_size)
        sphere_mode = reader.read("<B")
        toon_shared = reader.read("<B")
        toon_index = reader.index(1 if toon_shared else texture_size)
        memo = reader.text(encoding)
        index_count = reader.read("<i")
        materials.append({
            "id": material_id, "name": name, "english": english,
            "texture_index": texture_index, "sphere_index": sphere_index,
            "sphere_mode": sphere_mode, "toon_shared": toon_shared,
            "toon_index": toon_index, "index_count": index_count,
            "diffuse": diffuse, "flags": flags, "memo": memo,
        })

    bones = []
    for _ in range(reader.read("<i")):
        name, english = reader.text(encoding), reader.text(encoding)
        position = reader.read("<3f")
        parent = reader.index(bone_size)
        layer = reader.read("<i")
        flags = reader.read("<H")
        if flags & 0x0001:
            reader.index(bone_size)
        else:
            reader.bytes(12)
        if flags & (0x0100 | 0x0200):
            reader.index(bone_size)
            reader.read("<f")
        if flags & 0x0400:
            reader.bytes(12)
        if flags & 0x0800:
            reader.bytes(24)
        if flags & 0x2000:
            reader.read("<i")
        if flags & 0x0020:
            reader.index(bone_size)
            reader.read("<I")
            reader.read("<f")
            for _ in range(reader.read("<I")):
                reader.index(bone_size)
                if reader.read("<B"):
                    reader.bytes(24)
        bones.append({"name": name, "english": english, "position": position, "parent": parent, "layer": layer})

    cursor = 0
    for material in materials:
        end = cursor + material["index_count"]
        if end > len(faces) or material["index_count"] % 3:
            raise ValueError(f"material #{material['id']} 索引长度无效")
        material["indices"] = faces[cursor:end]
        cursor = end
        ti = material["texture_index"]
        material["texture"] = textures[ti] if 0 <= ti < len(textures) else ""
    if cursor != len(faces):
        raise ValueError(f"材质索引累计 {cursor} != faces {len(faces)}")
    for material in materials:
        if any(index < 0 or index >= len(vertices) for index in material["indices"]):
            raise ValueError(f"材质 #{material['id']} 存在越界/负面顶点索引")
    return {
        "path": path, "hash": hashlib.sha256(path.read_bytes()).hexdigest(),
        "version": version, "name": local_name, "english": english_name,
        "vertices": vertices, "materials": materials, "textures": textures, "bones": bones,
    }


def is_eye_bone(name: str) -> bool:
    label = name.lower()
    if any(term in label for term in MASK_EXCLUDED):
        return False
    compact = re.sub(r"[\s._-]", "", label)
    return (label.startswith(("左目", "右目", "両目", "左眼", "右眼"))
            or re.fullmatch(r"(?:left|right|l|r)?(?:eye|eyeball)(?:left|right|l|r)?(?:return|helper|end|ctrl|[0-9]+)?", compact) is not None
            or compact in ("jadjlfac eye".replace(" ", ""), "jadjrfaceeye"))


def eye_material(material: dict) -> bool:
    label = (material["name"] + " " + material["english"] + " " + material["texture"]).lower()
    return any(term in label for term in EYE_TERMS)


def bbox(values: list[tuple[float, ...]]) -> str:
    if not values:
        return "—"
    return "[" + "; ".join(f"{min(v[i] for v in values):.4f}..{max(v[i] for v in values):.4f}" for i in range(len(values[0]))) + "]"


def material_stats(data: dict, material: dict, eye_bones: set[int]) -> dict:
    verts = data["vertices"]
    triangles = [material["indices"][i:i + 3] for i in range(0, len(material["indices"]), 3)]
    eye_weights = [sum(w for bone, w in zip(v["bones"], v["weights"]) if bone in eye_bones and w > 0) for v in verts]
    all_eye, any_eye, mixed = [], [], []
    tri_positions, tri_uv = [], []
    for tri in triangles:
        vals = [eye_weights[i] for i in tri]
        if max(vals) > 1e-8:
            any_eye.append(tri)
        if min(vals) > 1e-8:
            all_eye.append(tri)
        if min(vals) <= 1e-8 < max(vals):
            mixed.append(tri)
        tri_positions.extend(verts[i]["position"] for i in tri)
        tri_uv.extend(verts[i]["uv"] for i in tri)
    eye_vertices = sorted({i for tri in triangles for i in tri if eye_weights[i] > 1e-8})
    return {
        "triangles": triangles, "eye_weights": eye_weights,
        "all_eye": all_eye, "any_eye": any_eye, "mixed": mixed,
        "eye_vertices": eye_vertices,
        "eye_positions": [verts[i]["position"] for i in eye_vertices],
        "eye_uvs": [verts[i]["uv"] for i in eye_vertices],
        "position_bbox": bbox(tri_positions), "uv_bbox": bbox(tri_uv),
        "eye_position_bbox": bbox([verts[i]["position"] for tri in any_eye for i in tri]),
        "eye_uv_bbox": bbox([verts[i]["uv"] for tri in any_eye for i in tri]),
        "zero_triangles": len(triangles) - len(any_eye),
    }


def uv_components(data: dict, stats: dict) -> list[dict]:
    triangles = stats["triangles"]
    vertices = data["vertices"]
    eye_weights = stats["eye_weights"]
    owner: dict[tuple[int, int], int] = {}
    parent = list(range(len(triangles)))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra

    for ti, tri in enumerate(triangles):
        for vi in tri:
            u, v = vertices[vi]["uv"]
            key = (round(u * 100000), round(v * 100000))
            if key in owner:
                union(ti, owner[key])
            else:
                owner[key] = ti
    groups: dict[int, list[int]] = defaultdict(list)
    for ti in range(len(triangles)):
        groups[find(ti)].append(ti)
    components = []
    for tids in groups.values():
        vids = sorted({v for ti in tids for v in triangles[ti]})
        vals = [eye_weights[i] for i in vids]
        components.append({
            "tris": len(tids), "uv": bbox([vertices[i]["uv"] for i in vids]),
            "pos": bbox([vertices[i]["position"] for i in vids]),
            "eye_vertices": sum(value > 1e-8 for value in vals),
            "zero_vertices": sum(value <= 1e-8 for value in vals),
            "mixed_triangles": sum(
                min(eye_weights[v] for v in triangles[ti]) <= 1e-8
                < max(eye_weights[v] for v in triangles[ti]) for ti in tids
            ),
            "eye_triangles": sum(min(eye_weights[v] for v in triangles[ti]) > 1e-8 for ti in tids),
            "any_eye_triangles": sum(max(eye_weights[v] for v in triangles[ti]) > 1e-8 for ti in tids),
        })
    return sorted(components, key=lambda c: c["tris"], reverse=True)


def overlay_texture(data: dict, output: pathlib.Path) -> list[pathlib.Path]:
    output.mkdir(parents=True, exist_ok=True)
    groups: dict[str, list[tuple[dict, dict]]] = defaultdict(list)
    eye_bones = {i for i, bone in enumerate(data["bones"]) if is_eye_bone(bone["name"])}
    for material in data["materials"]:
        if not eye_material(material):
            continue
        resolved = (data["path"].parent / material["texture"]).resolve() if material["texture"] else None
        if resolved and resolved.exists():
            groups[str(resolved)].append((material, material_stats(data, material, eye_bones)))
    saved = []
    for texture_path, items in groups.items():
        image = Image.open(texture_path).convert("RGBA")
        draw = ImageDraw.Draw(image, "RGBA")
        width, height = image.size
        colors = {"eye": (15, 245, 50, 190), "zero": (255, 40, 30, 190), "mixed": (20, 170, 255, 220)}
        for material, stats in items:
            for tri in stats["triangles"]:
                weights = [stats["eye_weights"][i] for i in tri]
                mode = "eye" if min(weights) > 1e-8 else "mixed" if max(weights) > 1e-8 else "zero"
                points = []
                for vi in tri:
                    u, v = data["vertices"][vi]["uv"]
                    points.append((round(u * (width - 1)), round(v * (height - 1))))
                draw.polygon(points, outline=colors[mode], fill=None)
        label = re.sub(r"[^\w-]+", "_", data["path"].stem, flags=re.UNICODE)[:48]
        texture_label = pathlib.Path(texture_path).stem[:40]
        target = output / f"uv-eye-{label}-{texture_label}.png"
        image.save(target)
        saved.append(target)
    return saved


def audit_model(path: pathlib.Path, output: pathlib.Path) -> tuple[str, list[dict]]:
    data = parse_pmx(path)
    eye_bones = {i for i, bone in enumerate(data["bones"]) if is_eye_bone(bone["name"])}
    all_stats = {m["id"]: material_stats(data, m, eye_bones) for m in data["materials"]}
    eye_stats = {mid: all_stats[mid] for mid, m in enumerate(data["materials"]) if eye_material(m)}
    eyeish_bones = [(i, b) for i, b in enumerate(data["bones"])
                    if any(term in (b["name"] + " " + b["english"]).lower() for term in EYE_TERMS)]
    eye_bone_labels = ", ".join(
        f"#{i} {data['bones'][i]['name']} (parent #{data['bones'][i]['parent']})"
        for i in sorted(eye_bones)
    ) or "无匹配眼骨"
    lines = [
        f"### `{path.parent.name}/{path.name}`",
        f"模型：{data['name']} / {data['english']}；PMX {data['version']:.2f}；SHA-256 `{data['hash']}`。",
        f"规模：{len(data['vertices'])} 顶点，{sum(len(m['indices']) for m in data['materials']) // 3} 三角形，{len(data['bones'])} 骨骼；眼骨影响 {sum(s['eye_vertices'].__len__() for s in all_stats.values())} 材质顶点实例（跨材质重复计数）。",
        f"当前生产名规则识别眼骨 {len(eye_bones)} 根、{sum(bool(eye_stats[mid]['eye_vertices']) for mid in eye_stats)} 个眼部材质含眼骨权重；骨骼：{eye_bone_labels}。",
        "",
        "| 材质 | 纹理/存在 | 三角形 | 全顶点眼骨权重 | 有眼骨权重 | 混合边界 | UV 范围 | 网格范围 xyz | 眼骨子网格 xyz / UV |",
        "|---|---|---:|---:|---:|---:|---|---|---|",
    ]
    if eyeish_bones:
        lines.append("眼/瞳相关骨名候选：" + ", ".join(
            f"#{i} {b['name']} ({'生产规则命中' if i in eye_bones else '生产规则未命中'})"
            for i, b in eyeish_bones
        ) + "。")
    lines.extend([
        "",
        "所有眼骨影响或名称相关材质（包括眼白/镜片等，权重统计用于排除风险）：",
        "| 材质 | 纹理 | tris | 全顶点有眼骨权重 | 任一顶点有眼骨权重 | 混合边界 tris |",
        "|---|---|---:|---:|---:|---:|",
    ])
    for material in data["materials"]:
        stats = all_stats[material["id"]]
        if not eye_material(material) and not stats["any_eye"]:
            continue
        lines.append(f"| #{material['id']} {material['name']} / {material['english']} | `{material['texture']}` | {len(stats['triangles'])} | {len(stats['all_eye'])} | {len(stats['any_eye'])} | {len(stats['mixed'])} |")
    lines.extend([
        "",
        "| 相关骨骼 | 加权顶点 | 权重总和 |",
        "|---|---:|---:|",
    ])
    for bi in sorted(eye_bones):
        vertex_weights = [sum(w for bone, w in zip(v["bones"], v["weights"]) if bone == bi and w > 0) for v in data["vertices"]]
        lines.append(f"| #{bi} {data['bones'][bi]['name']} | {sum(w > 0 for w in vertex_weights)} | {sum(vertex_weights):.4f} |")
    lines.append("")
    for material in data["materials"]:
        if not eye_material(material):
            continue
        stats = eye_stats[material["id"]]
        resolved = (path.parent / material["texture"]).resolve() if material["texture"] else None
        texture_label = f"`{material['texture']}`" if material["texture"] else "未指定"
        texture_label += " ✓" if resolved and resolved.exists() else " 缺失"
        lines.append(
            f"| #{material['id']} {material['name']} / {material['english']} | {texture_label} | {len(stats['triangles'])} | {len(stats['all_eye'])} | {len(stats['any_eye'])} | {len(stats['mixed'])} | {stats['uv_bbox']} | {stats['position_bbox']} | {stats['eye_position_bbox']} / {stats['eye_uv_bbox']} |"
        )
        components = uv_components(data, stats)
        lines.append("")
        lines.append(f"材质 #{material['id']} 的 UV 连通岛（按共享 UV 顶点连接，前 12 个）：")
        lines.append("| 岛 | tris | UV 范围 | xyz 范围 | 全眼骨 tris / 含眼骨 tris / 混合 tris | 含眼骨顶点 / 零眼骨顶点 |")
        lines.append("|---:|---:|---|---|---:|---:|")
        for ci, comp in enumerate(components[:40]):
            lines.append(f"| {ci} | {comp['tris']} | {comp['uv']} | {comp['pos']} | {comp['eye_triangles']} / {comp['any_eye_triangles']} / {comp['mixed_triangles']} | {comp['eye_vertices']} / {comp['zero_vertices']} |")
        if len(components) > 40:
            lines.append(f"其余 {len(components) - 40} 个小岛合计 {sum(c['tris'] for c in components[40:])} tris。")
        lines.append("")
    overlays = overlay_texture(data, output)
    for overlay in overlays:
        lines.append(f"UV/权重叠图：`{overlay.relative_to(output.parent).as_posix()}`（绿=三顶点均有眼骨权重，蓝=跨眼骨边界，红=零眼骨权重）。")
    lines.append("")
    rows = []
    for m in data["materials"]:
        if m["id"] not in eye_stats:
            continue
        s = eye_stats[m["id"]]
        rows.append({"material": m["id"], "name": m["name"], "texture": m["texture"],
                     "triangles": len(s["triangles"]), "all_eye": len(s["all_eye"]),
                     "any_eye": len(s["any_eye"]), "mixed": len(s["mixed"]),
                     "position_bbox": s["position_bbox"], "uv_bbox": s["uv_bbox"]})
    return "\n".join(lines), rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, default=DEFAULT_ROOT)
    parser.add_argument("--output", type=pathlib.Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    models = sorted(args.root.rglob("*.pmx"))
    sections, summary = [], []
    for model in models:
        try:
            section, rows = audit_model(model, output)
            sections.append(section)
            summary.append({"path": str(model), "materials": rows, "status": "parsed"})
            print(f"PASS {model.parent.name}/{model.name}")
        except Exception as exc:
            sections.append(f"### `{model.parent.name}/{model.name}`\n\n解析失败：`{type(exc).__name__}: {exc}`\n")
            summary.append({"path": str(model), "status": "error", "error": str(exc)})
            print(f"ERROR {model}: {exc}")
    report = "# 多模型眼部资产权重审计\n\n" + f"实例目录：`{args.root}`。检查 {len(models)} 个 PMX；面索引按无符号值读取，骨索引按有符号值读取。\n\n" + "\n".join(sections)
    (output / "multi-model-eye-audit.md").write_text(report, encoding="utf-8")
    (output / "multi-model-eye-audit.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"报告: {output / 'multi-model-eye-audit.md'}")


if __name__ == "__main__":
    main()
