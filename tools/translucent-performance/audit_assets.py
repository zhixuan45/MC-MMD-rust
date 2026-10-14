"""只读检查 PMX 透明候选与绑定姿态绘制分组，不代表游戏帧率。"""
import collections
import hashlib
import importlib.util
import json
import math
import pathlib
import sys

import numpy as np
from PIL import Image

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('pmx_reader', ROOT / 'tools/iris-toon-probe/export_eye_asset.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def read_mesh(path):
    data = path.read_bytes()
    r = module.PmxReader(data)
    assert r.bytes(4) == b'PMX '
    r.read('<f')
    encoding, extra, vi, ti, mi, bi, moi, ri = r.bytes(r.read('<B'))[:8]
    names = [r.text(encoding) for _ in range(4)]
    vertices = r.read('<i')
    xyz = np.zeros((vertices, 3), dtype=np.float32)
    uv = np.zeros((vertices, 2), dtype=np.float32)
    sizes = {0: bi, 1: 2 * bi + 4, 2: 4 * bi + 16, 3: 2 * bi + 40, 4: 4 * bi + 16}
    for i in range(vertices):
        xyz[i] = r.read('<3f')
        r.skip(12)
        uv[i] = r.read('<2f')
        r.skip(extra * 16)
        deform = r.read('<B')
        r.skip(sizes[deform] + 4)
    xyz[:, 2] *= -1
    uv[:, 1] = 1 - uv[:, 1]
    count = r.read('<i')
    faces = np.frombuffer(r.bytes(count * vi), dtype={1: '<u1', 2: '<u2', 4: '<u4'}[vi]).reshape(-1, 3)
    textures = [r.text(encoding) for _ in range(r.read('<i'))]
    materials = []
    start = 0
    for _ in range(r.read('<i')):
        name, english = r.text(encoding), r.text(encoding)
        diffuse = r.read('<4f')
        specular = r.read('<3f')
        power = r.read('<f')
        ambient = r.read('<3f')
        flags = r.read('<B')
        r.skip(20)
        texture = r.index(ti)
        r.index(ti)
        r.read('<B')
        shared = r.read('<B')
        r.index(1 if shared else ti)
        r.text(encoding)
        n = r.read('<i') // 3
        materials.append(dict(name=name, english=english, diffuse=diffuse, specular=specular,
                              power=power, ambient=ambient, flags=flags, texture=texture,
                              start=start, triangles=n))
        start += n
    assert start == len(faces)
    bone_count = r.read('<i')
    for _ in range(bone_count):
        r.text(encoding)
        r.text(encoding)
        r.skip(12 + bi + 4)
        flags = r.read('<H')
        r.skip(bi if flags & 1 else 12)
        if flags & 0x0300:
            r.skip(bi + 4)
        if flags & 0x0400:
            r.skip(12)
        if flags & 0x0800:
            r.skip(24)
        if flags & 0x2000:
            r.skip(4)
        if flags & 0x0020:
            r.skip(bi + 8)
            for _ in range(r.read('<i')):
                r.skip(bi)
                if r.read('<B'):
                    r.skip(24)
    widths = {0: moi + 4, 1: vi + 12, 2: bi + 28, 3: vi + 16, 4: vi + 16,
              5: vi + 16, 6: vi + 16, 7: vi + 16, 8: mi + 113, 9: moi + 4, 10: ri + 25}
    morphs = collections.Counter()
    uv_morphs = []
    for _ in range(r.read('<i')):
        morph_name = r.text(encoding)
        r.text(encoding)
        r.read('<B')
        kind = r.read('<B')
        count = r.read('<i')
        morphs[kind] += 1
        if 3 <= kind <= 7:
            uv_morphs.append(dict(name=morph_name, kind=kind, offsets=count))
        r.skip(widths[kind] * count)
    return data, names[0], xyz, uv, faces, textures, materials, bone_count, morphs, uv_morphs


def texture_stats(path):
    with Image.open(path) as im:
        has_alpha = 'A' in im.getbands() or 'transparency' in im.info
        alpha = np.asarray(im.convert('RGBA'))[::-1, :, 3]
    h, w = alpha.shape
    rows, columns = (h + 15) // 16, (w + 15) // 16
    lo, hi = np.zeros((rows, columns), dtype=np.uint8), np.zeros((rows, columns), dtype=np.uint8)
    for y in range(rows):
        for x in range(columns):
            tile = alpha[y * 16:(y + 1) * 16, x * 16:(x + 1) * 16]
            lo[y, x], hi[y, x] = tile.min(), tile.max()
    public = dict(path=str(path), width=w, height=h, has_alpha_channel=has_alpha,
                  zero=int((alpha == 0).sum()), partial=int(((alpha > 0) & (alpha < 255)).sum()),
                  opaque=int((alpha == 255).sum()))
    return public, (lo, hi, w, h, alpha)


def may_blend(triangle_uv, coverage, include_opaque, exact_bounds=False):
    lo, hi, w, h, pixels = coverage
    low = triangle_uv.min(axis=0)
    high = triangle_uv.max(axis=0)
    if (not np.isfinite(low.sum() + high.sum()) or low[0] <= .5 / w or low[1] <= .5 / h
            or high[0] >= 1 - .5 / w or high[1] >= 1 - .5 / h):
        return True, True
    if exact_bounds:
        # 像素级 UV 包围盒仍保守，不宣称精确三角覆盖。
        x0 = max(0, math.floor(float(low[0]) * w) - 1)
        x1 = min(w - 1, math.ceil(float(high[0]) * w) + 1)
        y0 = max(0, math.floor(float(low[1]) * h) - 1)
        y1 = min(h - 1, math.ceil(float(high[1]) * h) + 1)
        region = pixels[y0:y1 + 1, x0:x1 + 1]
        return int(region.max()) > 0 and (include_opaque or int(region.min()) < 255), False
    x0 = max(0, (math.floor(float(low[0]) * w) - 1) // 16)
    x1 = min(lo.shape[1] - 1, (math.ceil(float(high[0]) * w) + 1) // 16)
    y0 = max(0, (math.floor(float(low[1]) * h) - 1) // 16)
    y1 = min(lo.shape[0] - 1, (math.ceil(float(high[1]) * h) + 1) // 16)
    minimum = int(lo[y0:y1 + 1, x0:x1 + 1].min())
    maximum = int(hi[y0:y1 + 1, x0:x1 + 1].max())
    return maximum > 0 and (include_opaque or minimum < 255), False


def audit(path):
    data, name, xyz, uv, faces, textures, mats, bones, morphs, uv_morphs = read_mesh(path)
    tex, coverage = {}, {}
    for i, relative in enumerate(textures):
        p = path.parent / relative.replace('\\', '/')
        if p.exists():
            tex[i], coverage[i] = texture_stats(p)
        else:
            tex[i] = dict(path=str(p), missing=True)
    candidates, refined, refined_owners, all_candidates, owners, batches = [], [], [], [], [], []
    for i, m in enumerate(mats):
        alpha = max(0, min(1, m['diffuse'][3]))
        texture = tex.get(m['texture'], {})
        eligible = alpha >= .001 and (alpha < .999 or texture.get('has_alpha_channel', False))
        kept, fallback = [], 0
        span = range(m['start'], m['start'] + m['triangles'])
        if eligible:
            all_candidates.extend(span)
            for t in span:
                keep, conservative = (True, False)
                if m['texture'] in coverage:
                    keep, conservative = may_blend(uv[faces[t]], coverage[m['texture']], alpha < .999)
                if keep:
                    kept.append(t)
                fallback += conservative
        m.update(alpha_candidates=len(kept), uv_boundary_fallback=fallback, sort_eligible=eligible)
        refined_kept = [t for t in kept if m['texture'] not in coverage or
                        may_blend(uv[faces[t]], coverage[m['texture']], alpha < .999, True)[0]]
        m['pixel_bounds_candidates'] = len(refined_kept)
        refined.extend(refined_kept)
        refined_owners.extend([i] * len(refined_kept))
        candidates.extend(kept)
        owners.extend([i] * len(kept))
        # 与已知 Toon 公式的静态状态一致；眼遮罩等运行时状态不纳入预测。
        color = tuple(max(0, min(1, m['diffuse'][c] * .5 + m['ambient'][c])) for c in range(3))
        batches.append((m['texture'], bool(m['flags'] & 1), color, alpha, m['specular'],
                        m['power'] if any(m['specular']) else 0))
    candidates = np.array(candidates, dtype=int)
    owners = np.array(owners, dtype=int)
    angles = []
    for label, selected, selected_owners in [('current_16px_tiles', candidates, owners),
                                             ('pixel_uv_bounds', np.array(refined, dtype=int),
                                              np.array(refined_owners, dtype=int))]:
        if not len(selected):
            continue
        centers = xyz[faces[selected]].sum(axis=1)
        for degrees in (0, 12, 90):
            a = math.radians(degrees)
            z = np.float32(math.sin(a)) * centers[:, 0] + np.float32(math.cos(a)) * centers[:, 2]
            order = np.argsort(z, kind='stable')
            ordered = selected_owners[order]
            runs, contributions, previous = 0, collections.Counter(), None
            for owner in ordered:
                batch = batches[owner]
                if previous != batch:
                    runs += 1
                    contributions[mats[owner]['name']] += 1
                    previous = batch
            angles.append(dict(method=label, yaw_degrees=degrees, runs=runs,
                               top_run_materials=contributions.most_common(8)))
    return dict(path=str(path), sha256=hashlib.sha256(data).hexdigest(), name=name,
                vertices=len(xyz), triangles=len(faces), materials=len(mats), bones=bones,
                morph_types=dict(morphs), uv_morphs=uv_morphs, textures=list(tex.values()),
                unfiltered_alpha_candidates=len(all_candidates), uv_filtered_candidates=len(candidates),
                pixel_bounds_candidates=len(refined),
                candidate_vertices=len(np.unique(faces[candidates])) if len(candidates) else 0,
                static_bind_pose_runs=angles, material_details=mats,
                boundary='静态原始姿态、无视锥裁剪、无动作/morph/眼遮罩，不代表游戏 drawcall 或帧率。')


if __name__ == '__main__':
    folder = pathlib.Path(sys.argv[1])
    result = []
    for path in folder.rglob('*.pmx'):
        if '方舟指令' in str(path) or '旗袍' in str(path):
            value = audit(path)
            result.append(value)
            print(json.dumps({k: value[k] for k in ('name', 'vertices', 'triangles', 'materials',
                              'unfiltered_alpha_candidates', 'uv_filtered_candidates', 'pixel_bounds_candidates', 'candidate_vertices',
                              'static_bind_pose_runs')}, ensure_ascii=False))
    pathlib.Path(sys.argv[2]).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
