#!/usr/bin/env python3
"""提取言和 PMX 裙体刚体、骨骼父链与原生关节元数据。"""

import argparse
import json
import math
import struct
from pathlib import Path


class PmxReader:
    def __init__(self, path):
        self.path = Path(path)
        self.data = self.path.read_bytes()
        self.offset = 0

    def take(self, size):
        start = self.offset
        value = self.data[start:start + size]
        if len(value) != size:
            raise ValueError(f"PMX 截断: offset={start}, size={size}")
        self.offset += size
        return value

    def u8(self):
        return self.take(1)[0]

    def i32(self):
        return struct.unpack("<i", self.take(4))[0]

    def f32(self):
        return struct.unpack("<f", self.take(4))[0]

    def vec3(self):
        return struct.unpack("<3f", self.take(12))

    def index(self, size):
        return int.from_bytes(self.take(size), "little", signed=True)

    def count(self, label):
        value = self.i32()
        if not 0 <= value <= 10_000_000:
            raise ValueError(f"PMX {label} 数量异常: {value} @ {self.offset}")
        return value

    def string(self, encoding):
        size = self.i32()
        if not 0 <= size <= len(self.data) - self.offset:
            raise ValueError(f"PMX 字符串长度异常: {size} @ {self.offset}")
        codec = "utf-16-le" if encoding == 0 else "utf-8"
        return self.take(size).decode(codec, errors="replace")


def skip_model_prefix(reader, sizes):
    vertex_size, texture_size, material_size, bone_size, morph_size, rigid_size = sizes
    for _ in range(4):
        reader.string(reader.encoding)
    uv_count = reader.additional_uv_count
    deform_sizes = {
        0: bone_size,
        1: bone_size * 2 + 4,
        2: bone_size * 4 + 16,
        3: bone_size * 2 + 4 + 36,
        4: bone_size * 4 + 16,
    }
    for _ in range(reader.count("vertex")):
        reader.take(32 + uv_count * 16)
        deform = reader.u8()
        if deform not in deform_sizes:
            raise ValueError(f"未知顶点变形类型: {deform}")
        reader.take(deform_sizes[deform] + 4)
    reader.take(reader.count("face-index") * vertex_size)
    for _ in range(reader.count("texture")):
        reader.string(reader.encoding)
    for _ in range(reader.count("material")):
        reader.string(reader.encoding)
        reader.string(reader.encoding)
        reader.take(65 + 2 * texture_size + 1)
        shared_toon = reader.u8()
        reader.take(1 if shared_toon else texture_size)
        reader.string(reader.encoding)
        reader.take(4)


def read_bones(reader, bone_size, morph_size, vertex_size, material_size, rigid_size):
    bones = []
    for index in range(reader.count("bone")):
        name = reader.string(reader.encoding)
        reader.string(reader.encoding)
        position = reader.vec3()
        parent = reader.index(bone_size)
        reader.take(4)
        flags = int.from_bytes(reader.take(2), "little")
        reader.take(bone_size if flags & 0x0001 else 12)
        if flags & 0x0300:
            reader.take(bone_size + 4)
        if flags & 0x0400:
            reader.take(12)
        if flags & 0x0800:
            reader.take(24)
        if flags & 0x2000:
            reader.take(4)
        if flags & 0x0020:
            reader.take(bone_size + 8)
            for _ in range(reader.count("IK link")):
                reader.take(bone_size)
                if reader.u8():
                    reader.take(24)
        bones.append({"index": index, "name": name, "parent": parent, "position": position})

    widths = {
        0: morph_size + 4, 1: vertex_size + 12, 2: bone_size + 28,
        3: vertex_size + 16, 4: vertex_size + 16, 5: vertex_size + 16,
        6: vertex_size + 16, 7: vertex_size + 16, 8: material_size + 113,
        9: morph_size + 4, 10: rigid_size + 25,
    }
    for _ in range(reader.count("morph")):
        reader.string(reader.encoding)
        reader.string(reader.encoding)
        reader.u8()
        kind = reader.u8()
        width = widths.get(kind)
        if width is None:
            raise ValueError(f"未知 PMX morph 类型: {kind}")
        reader.take(reader.count("morph offset") * width)
    for _ in range(reader.count("display frame")):
        reader.string(reader.encoding)
        reader.string(reader.encoding)
        reader.u8()
        for _ in range(reader.count("display element")):
            kind = reader.u8()
            reader.take(bone_size if kind == 0 else morph_size)
    return bones


def read_bodies_and_joints(reader, bone_size, rigid_size):
    bodies = []
    for index in range(reader.count("rigid body")):
        name = reader.string(reader.encoding)
        reader.string(reader.encoding)
        bone = reader.index(bone_size)
        group = reader.u8()
        mask = int.from_bytes(reader.take(2), "little")
        shape = reader.u8()
        size, position, rotation = reader.vec3(), reader.vec3(), reader.vec3()
        mass, linear_damping, angular_damping, restitution, friction = struct.unpack(
            "<5f", reader.take(20)
        )
        mode = reader.u8()
        bodies.append({
            "index": index, "name": name, "bone_index": bone, "group": group,
            "mask": mask, "shape": shape, "size": size, "position": position,
            "rotation": rotation, "mass": mass, "mode": mode,
        })
    joints = []
    for index in range(reader.count("joint")):
        name = reader.string(reader.encoding)
        reader.string(reader.encoding)
        kind = reader.u8()
        body_a, body_b = reader.index(rigid_size), reader.index(rigid_size)
        position, rotation = reader.vec3(), reader.vec3()
        reader.take(72)
        joints.append({
            "index": index, "name": name, "type": kind,
            "body_a": body_a, "body_b": body_b,
            "position": position, "rotation": rotation,
        })
    return bodies, joints


def distance(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pmx", type=Path)
    parser.add_argument(
        "--output", type=Path,
        default=Path("Logs/latest-log-assets/yanhe-skirt-topology.json"),
    )
    args = parser.parse_args()

    reader = PmxReader(args.pmx)
    if reader.take(4) != b"PMX ":
        raise ValueError("不是 PMX 文件")
    version = reader.f32()
    header_size = reader.u8()
    header = list(reader.take(header_size))
    reader.encoding, reader.additional_uv_count = header[:2]
    sizes = header[2:]
    vertex_size, texture_size, material_size, bone_size, morph_size, rigid_size = sizes
    reader.string(reader.encoding)
    local_name = reader.string(reader.encoding)
    reader.string(reader.encoding)
    reader.string(reader.encoding)
    skip_model_prefix(reader, sizes)
    bones = read_bones(reader, bone_size, morph_size, vertex_size, material_size, rigid_size)
    bodies, joints = read_bodies_and_joints(reader, bone_size, rigid_size)
    if reader.offset != len(reader.data):
        raise ValueError(f"未解析 PMX 尾部: offset={reader.offset}, size={len(reader.data)}")

    bone_by_index = {bone["index"]: bone for bone in bones}
    joint_by_pair = {}
    for joint in joints:
        key = tuple(sorted((joint["body_a"], joint["body_b"])))
        joint_by_pair.setdefault(key, []).append(joint)
    body_by_name = {body["name"]: body for body in bodies}

    def ancestors(bone_index):
        result, seen = [], set()
        while bone_index >= 0 and bone_index in bone_by_index and bone_index not in seen:
            seen.add(bone_index)
            bone = bone_by_index[bone_index]
            result.append({
                "index": bone_index, "name": bone["name"], "parent": bone["parent"],
            })
            bone_index = bone["parent"]
        return result

    families = []
    for prefix in ("スカート", "スカートB"):
        rows = []
        for depth in range(15):
            columns = []
            for column in range(8):
                body = body_by_name.get(f"{prefix}_{depth}_{column}")
                if body is None:
                    raise ValueError(f"缺少刚体: {prefix}_{depth}_{column}")
                bone = bone_by_index.get(body["bone_index"])
                columns.append({
                    **body,
                    "bone_name": bone["name"] if bone else None,
                    "bone_parent": bone["parent"] if bone else None,
                    "bone_ancestors": ancestors(body["bone_index"]),
                })
            left, right = columns[0], columns[-1]
            rows.append({
                "depth_i": depth,
                "columns": columns,
                "same_family_endpoint_distance": distance(left["position"], right["position"]),
                "same_depth_y_spread": max(c["position"][1] for c in columns)
                    - min(c["position"][1] for c in columns),
                "authored_endpoint_joints": joint_by_pair.get(
                    tuple(sorted((left["index"], right["index"]))), []
                ),
            })
        families.append({"name_prefix": prefix, "rows": rows})

    authored_summary = {
        "outer_end_to_end_same_depth": [],
        "back_end_to_end_same_depth": [],
        "cross_family_same_side_same_depth": [],
        "same_column_adjacent_depth": [],
        "adjacent_column_same_depth": [],
    }
    for depth in range(15):
        outer = [body_by_name[f"スカート_{depth}_{column}"] for column in range(8)]
        back = [body_by_name[f"スカートB_{depth}_{column}"] for column in range(8)]
        authored_summary["outer_end_to_end_same_depth"].append({
            "depth_i": depth,
            "joints": joint_by_pair.get(tuple(sorted((outer[0]["index"], outer[-1]["index"]))), []),
        })
        authored_summary["back_end_to_end_same_depth"].append({
            "depth_i": depth,
            "joints": joint_by_pair.get(tuple(sorted((back[0]["index"], back[-1]["index"]))), []),
        })
        for column in (0, 7):
            authored_summary["cross_family_same_side_same_depth"].append({
                "depth_i": depth, "column_j": column,
                "joints": joint_by_pair.get(tuple(sorted((outer[column]["index"], back[column]["index"]))), []),
            })
        if depth:
            for column in range(8):
                for a, b in (
                    (outer[column], body_by_name[f"スカート_{depth - 1}_{column}"]),
                    (back[column], body_by_name[f"スカートB_{depth - 1}_{column}"]),
                ):
                    authored_summary["same_column_adjacent_depth"].append({
                        "depth_i": depth, "column_j": column,
                        "body_pair": [a["index"], b["index"]],
                        "joints": joint_by_pair.get(tuple(sorted((a["index"], b["index"]))), []),
                    })
        for column in range(7):
            for row in (outer, back):
                a, b = row[column], row[column + 1]
                authored_summary["adjacent_column_same_depth"].append({
                    "depth_i": depth, "column_pair": [column, column + 1],
                    "body_pair": [a["index"], b["index"]],
                    "joints": joint_by_pair.get(tuple(sorted((a["index"], b["index"]))), []),
                })

    output = {
        "source_pmx": str(args.pmx),
        "model_name": local_name,
        "pmx_version": version,
        "parsed_counts": {"bones": len(bones), "rigid_bodies": len(bodies), "joints": len(joints)},
        "index_sizes_bytes": {
            "vertex": vertex_size, "texture": texture_size, "material": material_size,
            "bone": bone_size, "morph": morph_size, "rigid_body": rigid_size,
        },
        "families": families,
        "authored_joint_checks": authored_summary,
        "limitations": [
            "只记录 PMX 刚体中心、骨骼父链和原生关节端点；不检查网格三角形是否沿端点闭合。",
            "父链证明 i 深度和 j 列归属；是否应把一条弦加入裙摆仍需网格边界/设计意图确认。",
            "未读取或修改纹理、VMD、模型资产内容以外的文件。",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"已解析骨骼 {len(bones)}、刚体 {len(bodies)}、原生关节 {len(joints)}；输出 {args.output}")


if __name__ == "__main__":
    main()
