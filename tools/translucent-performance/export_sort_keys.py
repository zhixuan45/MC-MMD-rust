"""从原模型生成相同静态深度键，不修改资产。"""
import pathlib
import struct
import sys
sys.dont_write_bytecode = True
import audit_assets as audit
import numpy as np

root = pathlib.Path(sys.argv[1])
for number, path in enumerate(p for p in root.rglob('*.pmx') if '方舟指令' in str(p) or '旗袍' in str(p)):
    _, _, xyz, uv, faces, textures, mats, *_ = audit.read_mesh(path)
    coverage = {i: audit.texture_stats(path.parent / t.replace('\\', '/'))[1]
                for i, t in enumerate(textures)}
    candidates = []
    for m in mats:
        if m['diffuse'][3] < .001:
            continue
        for t in range(m['start'], m['start'] + m['triangles']):
            if audit.may_blend(uv[faces[t]], coverage[m['texture']], m['diffuse'][3] < .999, True)[0]:
                candidates.append(t)
    centers = xyz[faces[candidates]].sum(axis=1)
    z = np.float32(.20791169) * centers[:, 0] + np.float32(.9781476) * centers[:, 2]
    destination = pathlib.Path(__file__).parent / f'sort-keys-{number}.bin'
    with destination.open('wb') as output:
        for ordinal, depth in enumerate(z):
            bits = struct.unpack('<i', struct.pack('<f', depth))[0]
            ordered = bits ^ ((bits >> 31) & 0x7fffffff)
            output.write(struct.pack('<q', (ordered << 32) | ordinal))
    print(path.name, len(candidates), destination.name)
