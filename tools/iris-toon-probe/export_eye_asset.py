#!/usr/bin/env python3
"""导出 TDA 洛天依 PMX 的真实脸部网格，供独立 Toon 离线探针复验。"""

from __future__ import annotations

import argparse
import pathlib
import struct

DEFAULT_PMX = pathlib.Path(
    r"D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252\3d-skin\EntityPlayer\TDA 方舟指令[洛天依][杏辞] Ver 1.00\TDA 方舟指令[洛天依][杏辞] Ver 1.00.pmx"
)
DEFAULT_OUTPUT = pathlib.Path("logs/iris-toon-unbound-20261011/eye-asset.bin")
MATERIAL_IDS = (0, 1, 2, 3)
MAGIC = b"MMD_EYE1"


class PmxReader:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.offset = 0

    def read(self, fmt: str):
        size = struct.calcsize(fmt)
        if self.offset + size > len(self.data):
            raise ValueError(f"PMX 文件截断: offset={self.offset} need={size}")
        value = struct.unpack_from(fmt, self.data, self.offset)
        self.offset += size
        return value[0] if len(value) == 1 else value

    def bytes(self, size: int) -> bytes:
        if size < 0 or self.offset + size > len(self.data):
            raise ValueError(f"PMX 字段越界: offset={self.offset} size={size}")
        value = self.data[self.offset : self.offset + size]
        self.offset += size
        return value

    def skip(self, size: int) -> None:
        self.bytes(size)

    def text(self, encoding: int) -> str:
        size = self.read("<i")
        if size < 0:
            raise ValueError(f"非法字符串长度: {size}")
        codec = "utf-16le" if encoding == 0 else "utf-8"
        return self.bytes(size).decode(codec, errors="replace")

    def index(self, size: int, *, signed: bool = True) -> int:
        # 骨骼索引有符号；PMX 顶点索引按无符号解码。
        return self.read("<" + ({1: "b", 2: "h", 4: "i"} if signed else {1: "B", 2: "H", 4: "I"})[size])


def parse_pmx(path: pathlib.Path, material_ids=MATERIAL_IDS):
    reader = PmxReader(path.read_bytes())
    if reader.bytes(4) != b"PMX ":
        raise ValueError("不是 PMX 文件")
    version = reader.read("<f")
    globals_ = reader.bytes(reader.read("<B"))
    if len(globals_) < 8:
        raise ValueError("PMX globals 长度无效")
    encoding, additional_uv, vertex_size, texture_size, _material_size, bone_size, _morph_size, _rigid_size = globals_[:8]
    local_name = reader.text(encoding)
    english_name = reader.text(encoding)
    reader.text(encoding)
    reader.text(encoding)

    vertices = []
    for _ in range(reader.read("<i")):
        source_position = reader.read("<3f")
        source_normal = reader.read("<3f")
        source_uv = reader.read("<2f")
        reader.skip(16 * additional_uv)
        deform = reader.read("<B")
        bone_indices = [-1, -1, -1, -1]
        bone_weights = [0.0, 0.0, 0.0, 0.0]
        if deform == 0:  # BDEF1
            bone_indices[0], bone_weights[0] = reader.index(bone_size), 1.0
        elif deform in (1, 3):  # BDEF2 / SDEF
            bone_indices[0] = reader.index(bone_size)
            bone_indices[1] = reader.index(bone_size)
            bone_weights[0] = reader.read("<f")
            bone_weights[1] = 1.0 - bone_weights[0]
            if deform == 3:
                reader.skip(36)
        elif deform in (2, 4):  # BDEF4 / QDEF
            bone_indices = [reader.index(bone_size) for _ in range(4)]
            bone_weights = list(reader.read("<4f"))
        else:
            raise ValueError(f"未知 PMX 顶点权重类型: {deform}")
        reader.read("<f")  # edge scale
        render_position = (source_position[0], source_position[1], -source_position[2])
        render_normal = (source_normal[0], source_normal[1], -source_normal[2])
        render_uv = (source_uv[0], 1.0 - source_uv[1])
        vertices.append(
            {
                "source_position": source_position,
                "source_normal": source_normal,
                "source_uv": source_uv,
                "position": render_position,
                "normal": render_normal,
                "uv": render_uv,
                "bone_indices": bone_indices,
                "bone_weights": bone_weights,
            }
        )

    face_count = reader.read("<i")
    faces = [reader.index(vertex_size, signed=False) for _ in range(face_count)]
    textures = [reader.text(encoding) for _ in range(reader.read("<i"))]
    materials = []
    for _ in range(reader.read("<i")):
        name = reader.text(encoding)
        reader.text(encoding)  # English name
        reader.skip(16 + 12 + 4 + 12 + 1 + 16 + 4)
        texture_index = reader.index(texture_size)
        reader.index(texture_size)  # sphere texture
        reader.read("<B")  # sphere blend mode
        toon_shared = reader.read("<B")
        reader.index(1 if toon_shared else texture_size)
        reader.text(encoding)  # memo
        surface_count = reader.read("<i")
        if surface_count < 0 or surface_count % 3:
            raise ValueError(f"材质 surface_count 无效: {name}={surface_count}")
        materials.append(
            {"name": name, "texture_index": texture_index, "surface_count": surface_count}
        )

    bone_names = []
    for _ in range(reader.read("<i")):
        bone_names.append(reader.text(encoding))
        reader.text(encoding)
        reader.skip(12)
        reader.index(bone_size)
        reader.read("<i")  # transform layer
        flags = reader.read("<H")
        if flags & 0x0001:
            reader.index(bone_size)
        else:
            reader.skip(12)
        if flags & (0x0100 | 0x0200):
            reader.index(bone_size)
            reader.read("<f")
        if flags & 0x0400:
            reader.skip(12)
        if flags & 0x0800:
            reader.skip(24)
        if flags & 0x2000:
            reader.read("<i")
        if flags & 0x0020:
            reader.index(bone_size)
            reader.read("<I")
            reader.read("<f")
            for _ in range(reader.read("<I")):
                reader.index(bone_size)
                if reader.read("<B"):
                    reader.skip(24)

    material_face_indices = []
    cursor = 0
    for material in materials:
        count = material["surface_count"]
        material_face_indices.append(faces[cursor : cursor + count])
        cursor += count
    if cursor != len(faces):
        raise ValueError(f"材质索引总数 {cursor} 与 PMX 面索引数 {len(faces)} 不符")

    selected_sources = sorted(
        {index for material_id in material_ids for index in material_face_indices[material_id]}
    )
    remap = {source: index for index, source in enumerate(selected_sources)}
    selected_vertices = [vertices[index] for index in selected_sources]
    output_materials = []
    for material_id in material_ids:
        source_indices = material_face_indices[material_id]
        # 与 rust_engine/src/model/loader.rs 的运行时绕序一致。
        render_indices = []
        for offset in range(0, len(source_indices), 3):
            a, b, c = source_indices[offset : offset + 3]
            render_indices.extend((remap[c], remap[b], remap[a]))
        material = materials[material_id]
        texture_index = material["texture_index"]
        texture_path = textures[texture_index] if 0 <= texture_index < len(textures) else ""
        output_materials.append(
            {
                "id": material_id,
                "name": material["name"],
                "texture_index": texture_index,
                "texture_path": texture_path,
                "indices": render_indices,
            }
        )

    return {
        "version": version,
        "model_name": local_name,
        "english_name": english_name,
        "bone_names": bone_names,
        "source_vertex_count": len(vertices),
        "source_indices": selected_sources,
        "vertices": selected_vertices,
        "materials": output_materials,
        "texture_paths": textures,
    }


def write_string(stream, value: str) -> None:
    data = value.encode("utf-8")
    stream.write(struct.pack(">I", len(data)))
    stream.write(data)


def export(path: pathlib.Path, output: pathlib.Path, material_ids=MATERIAL_IDS, texture_override=None) -> None:
    data = parse_pmx(path, material_ids)
    if texture_override:
        face_path = pathlib.Path(texture_override).resolve()
    else:
        texture_paths = [m["texture_path"] for m in data["materials"] if m["texture_path"]]
        face_path = (path.parent / texture_paths[0]).resolve() if texture_paths else (path.parent / "data" / "Face.png").resolve()
    if not face_path.is_file():
        raise FileNotFoundError(f"目标贴图不存在: {face_path}")
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as stream:
        stream.write(MAGIC)
        stream.write(struct.pack(">I", 1))
        write_string(stream, str(path.resolve()))
        write_string(stream, str(face_path))
        write_string(stream, data["model_name"])
        write_string(stream, data["english_name"])
        stream.write(struct.pack(">fIII", data["version"], data["source_vertex_count"], len(data["vertices"]), len(data["bone_names"])))
        for name in data["bone_names"]:
            write_string(stream, name)
        for source_index, vertex in zip(data["source_indices"], data["vertices"]):
            stream.write(struct.pack(">I", source_index))
            stream.write(struct.pack(">3f3f2f", *vertex["source_position"], *vertex["source_normal"], *vertex["source_uv"]))
            stream.write(struct.pack(">3f3f2f", *vertex["position"], *vertex["normal"], *vertex["uv"]))
            stream.write(struct.pack(">4i4f", *vertex["bone_indices"], *vertex["bone_weights"]))
        stream.write(struct.pack(">I", len(data["materials"])))
        for material in data["materials"]:
            stream.write(struct.pack(">IIi", material["id"], material["texture_index"], len(material["indices"])))
            write_string(stream, material["name"])
            write_string(stream, material["texture_path"])
            stream.write(struct.pack(">" + "I" * len(material["indices"]), *material["indices"]))
    print(f"输出: {output.resolve()}")
    print(f"模型: {data['model_name']} / {data['english_name']}")
    print(f"顶点: {len(data['vertices'])} / 源顶点: {data['source_vertex_count']} / 骨骼: {len(data['bone_names'])}")
    for material in data["materials"]:
        print(f"材质 #{material['id']} {material['name']}: {len(material['indices']) // 3} triangles, texture={material['texture_path']}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pmx", type=pathlib.Path, default=DEFAULT_PMX)
    parser.add_argument("--output", type=pathlib.Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--materials", help="逗号分隔的 PMX 材质编号；默认导出 TDA 材质 0..3")
    parser.add_argument("--texture", type=pathlib.Path, help="实际贴图覆盖路径；默认取首个所选材质的贴图")
    args = parser.parse_args()
    material_ids = tuple(int(value) for value in args.materials.split(",")) if args.materials else MATERIAL_IDS
    if not material_ids or min(material_ids) < 0:
        parser.error("--materials 必须包含非负材质编号")
    export(args.pmx, args.output, material_ids, args.texture)


if __name__ == "__main__":
    main()
