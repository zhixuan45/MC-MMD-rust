#!/usr/bin/env python3
"""汇总 latest-log-assets 下的 probe JSONL；只读结果日志，不读取模型资产。"""

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path


SAMPLE_FIELDS = [
    "record_type", "source_file", "pair_key", "model", "case", "synthesis",
    "seconds", "scope", "joint_count", "instant_status", "instant_samples", "instant_invalid",
    "instant_anchor_mean_mmd_local", "instant_anchor_peak_mmd_local",
    "instant_linear_mean_mmd_local", "instant_linear_peak_mmd_local",
    "interval_frames", "interval_status", "interval_samples", "interval_invalid",
    "interval_anchor_mean_mmd_local", "interval_anchor_peak_mmd_local",
    "interval_linear_mean_mmd_local", "interval_linear_peak_mmd_local",
    "cycle", "baseline_restored", "baseline_counts_json", "after_drop_counts_json",
]
PAIR_FIELDS = [
    "pair_key", "model", "case", "synthesis", "status", "endpoint_key",
    "before_joint_names_json", "after_joint_names_json", "before_mapping_json",
    "after_mapping_json", "before_file", "after_file",
]


def read_events(path):
    events, errors = [], []
    try:
        with path.open("r", encoding="utf-8-sig") as stream:
            for line_number, line in enumerate(stream, 1):
                if not line.strip():
                    continue
                try:
                    value = json.loads(line)
                    if isinstance(value, dict):
                        events.append(value)
                    else:
                        errors.append(f"{path.name}:{line_number}: JSON 根节点不是对象")
                except json.JSONDecodeError as error:
                    errors.append(f"{path.name}:{line_number}: {error}")
    except OSError as error:
        errors.append(f"{path.name}: {error}")
    return events, errors


def metric_fields(stats, prefix):
    stats = stats if isinstance(stats, dict) else {}
    samples = stats.get("joint_samples", 0)
    invalid = stats.get("invalid_samples", 0)
    status = "Measured" if samples else ("InvalidOnly" if invalid else "NoSamples")
    return {
        f"{prefix}_status": status,
        f"{prefix}_samples": samples,
        f"{prefix}_invalid": invalid,
        f"{prefix}_anchor_mean_mmd_local": stats.get("anchor_mean", "") if samples else "",
        f"{prefix}_anchor_peak_mmd_local": stats.get("anchor_max", "") if samples else "",
        f"{prefix}_linear_mean_mmd_local": stats.get("linear_violation_mean", "") if samples else "",
        f"{prefix}_linear_peak_mmd_local": stats.get("linear_violation_max", "") if samples else "",
    }


def model_label(events, path):
    for event in events:
        if event.get("event") == "probe_manifest":
            pmx = event.get("pmx")
            if isinstance(pmx, str) and pmx:
                return Path(pmx).stem
    return path.stem.removeprefix("before-").removeprefix("after-")


def pair_key(path):
    return path.stem.removeprefix("before-").removeprefix("after-")


def endpoint_identity(endpoint):
    """唯一匹配用 PMX 刚体原索引，否则保留名称及歧义状态。"""
    if not isinstance(endpoint, dict):
        return "unknown_endpoint", {"mapping_status": "missing_endpoint"}
    status = endpoint.get("mapping_status", "unknown")
    name = endpoint.get("runtime_body_name", "")
    candidates = endpoint.get("source_candidates", [])
    if status == "unique_name_match" and len(candidates) == 1:
        index = candidates[0].get("source_pmx_body_index")
        if isinstance(index, int):
            return f"pmx_index:{index}", endpoint
    return f"name:{name}|mapping:{status}", endpoint


def collect_endpoints(events):
    found = defaultdict(list)
    for event in events:
        if event.get("event") == "instant_snapshot":
            found[(event.get("case", ""), event.get("synthesis", ""))]
        if event.get("event") != "synthesized_joint_endpoint_detail":
            continue
        left, left_info = endpoint_identity(event.get("body_a"))
        right, right_info = endpoint_identity(event.get("body_b"))
        endpoints = tuple(sorted((left, right)))
        group = (event.get("case", ""), event.get("synthesis", ""))
        found[group].append({
            "key": endpoints,
            "joint": str(event.get("joint", "")),
            "mapping": [left_info, right_info],
        })
    return found


def write_csv(path, fields, rows):
    with path.open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def summarize(directory):
    files = {}
    errors = []
    metrics = []
    endpoint_data = {}
    lifecycle_rows = []

    for path in sorted(directory.glob("*.jsonl")):
        if not (path.name.startswith("before-") or path.name.startswith("after-")):
            continue
        events, file_errors = read_events(path)
        errors.extend(file_errors)
        role = "before" if path.name.startswith("before-") else "after"
        key = pair_key(path)
        model = model_label(events, path)
        files[(role, key)] = {"path": path.name, "model": model, "events": events}
        endpoint_data[(role, key)] = collect_endpoints(events)

        for event in events:
            if event.get("event") == "instant_snapshot":
                interval = event.get("fixed_step_interval_stats", {})
                scopes = [
                    ("all", "all_joints", "all_joints"),
                    ("synthesized", "synthesized_skirt_cross", "synthesized_skirt_cross"),
                ]
                for scope, instant_key, interval_key in scopes:
                    row = {
                        "record_type": "snapshot", "source_file": path.name,
                        "pair_key": key, "model": model, "case": event.get("case", ""),
                        "synthesis": event.get("synthesis", ""), "seconds": event.get("seconds", ""),
                        "scope": scope,
                        "joint_count": event.get("all_joint_count" if scope == "all" else "synthesized_joint_count", ""),
                    }
                    row.update(metric_fields(event.get(instant_key), "instant"))
                    row.update(metric_fields(interval.get(interval_key), "interval"))
                    row["interval_frames"] = interval.get("frames", "")
                    metrics.append(row)
            elif event.get("event") == "lifecycle_cycle":
                lifecycle_rows.append({
                    "record_type": "lifecycle", "source_file": path.name, "pair_key": key,
                    "model": model, "case": "lifecycle", "synthesis": "default",
                    "cycle": event.get("cycle", ""),
                    "baseline_restored": event.get("baseline_restored", ""),
                    "baseline_counts_json": json.dumps(event.get("before", {}), ensure_ascii=False, sort_keys=True),
                    "after_drop_counts_json": json.dumps(event.get("after_drop", {}), ensure_ascii=False, sort_keys=True),
                })
    metrics.extend(lifecycle_rows)

    diff_rows, pair_summaries = [], []
    pair_keys = sorted({key for _, key in files})
    for key in pair_keys:
        before = files.get(("before", key))
        after = files.get(("after", key))
        pair = {
            "pair_key": key,
            "model": (after or before)["model"],
            "before_present": before is not None,
            "after_present": after is not None,
            "before_file": before["path"] if before else None,
            "after_file": after["path"] if after else None,
            "pair_status": "paired" if before and after else "before_only" if before else "after_only",
            "endpoint_diffs": [],
            "interpretation_note": "跨材质或跨命名区域本身不构成缺陷；结果仅比较探针端点集合。",
        }
        before_groups = endpoint_data.get(("before", key), {})
        after_groups = endpoint_data.get(("after", key), {})
        groups = sorted(set(before_groups) | set(after_groups))
        for case, synthesis in groups:
            if not (before and after):
                unavailable = "comparison_unavailable_missing_before" if before is None else "comparison_unavailable_missing_after"
                diff_rows.append({
                    "pair_key": key, "model": pair["model"], "case": case,
                    "synthesis": synthesis, "status": unavailable,
                    "endpoint_key": "", "before_joint_names_json": "[]",
                    "after_joint_names_json": "[]", "before_mapping_json": "[]",
                    "after_mapping_json": "[]",
                    "before_file": before["path"] if before else "",
                    "after_file": after["path"] if after else "",
                })
                pair["endpoint_diffs"].append({
                    "case": case, "synthesis": synthesis,
                    "comparison": unavailable,
                })
                continue
            if (case, synthesis) not in before_groups or (case, synthesis) not in after_groups:
                diff_rows.append({
                    "pair_key": key, "model": pair["model"], "case": case,
                    "synthesis": synthesis,
                    "status": "comparison_unavailable_missing_case_or_synthesis",
                    "endpoint_key": "", "before_joint_names_json": "[]",
                    "after_joint_names_json": "[]", "before_mapping_json": "[]",
                    "after_mapping_json": "[]", "before_file": before["path"],
                    "after_file": after["path"],
                })
                pair["endpoint_diffs"].append({
                    "case": case, "synthesis": synthesis,
                    "comparison": "unavailable_missing_case_or_synthesis",
                })
                continue
            left = {item["key"]: item for item in before_groups.get((case, synthesis), [])}
            right = {item["key"]: item for item in after_groups.get((case, synthesis), [])}
            summary_counts = Counter()
            changes = []
            for endpoint_key in sorted(set(left) | set(right)):
                status = "present_both" if endpoint_key in left and endpoint_key in right else (
                    "added_after" if endpoint_key in right else "removed_after"
                )
                summary_counts[status] += 1
                before_item, after_item = left.get(endpoint_key), right.get(endpoint_key)
                row = {
                    "pair_key": key, "model": pair["model"], "case": case,
                    "synthesis": synthesis, "status": status,
                    "endpoint_key": json.dumps(endpoint_key, ensure_ascii=False),
                    "before_joint_names_json": json.dumps([before_item["joint"]] if before_item else [], ensure_ascii=False),
                    "after_joint_names_json": json.dumps([after_item["joint"]] if after_item else [], ensure_ascii=False),
                    "before_mapping_json": json.dumps(before_item["mapping"] if before_item else [], ensure_ascii=False),
                    "after_mapping_json": json.dumps(after_item["mapping"] if after_item else [], ensure_ascii=False),
                    "before_file": before["path"] if before else "",
                    "after_file": after["path"] if after else "",
                }
                diff_rows.append(row)
                changes.append({"status": status, "endpoint_key": list(endpoint_key)})
            if not left and not right:
                diff_rows.append({
                    "pair_key": key, "model": pair["model"], "case": case,
                    "synthesis": synthesis, "status": "empty_both",
                    "endpoint_key": "[]", "before_joint_names_json": "[]",
                    "after_joint_names_json": "[]", "before_mapping_json": "[]",
                    "after_mapping_json": "[]",
                    "before_file": before["path"], "after_file": after["path"],
                })
            pair["endpoint_diffs"].append({
                "case": case, "synthesis": synthesis,
                "counts": dict(summary_counts), "empty_both": not left and not right,
                "changed_endpoints": changes,
            })
        pair_summaries.append(pair)

    return {
        "input_directory": str(directory),
        "read_files": [
            {"role": role, "pair_key": key, "file": info["path"], "model": info["model"]}
            for (role, key), info in sorted(files.items())
        ],
        "pair_summaries": pair_summaries,
        "errors": errors,
        "notes": [
            "anchor/linear 数值沿用日志数值，按 MMD local 单位标记；不设置通过阈值。",
            "瞬时值与前一采样区间的 fixed-step 累计统计分列。",
            "端点只有唯一 source PMX 刚体名映射时使用原刚体索引；否则按运行时名称和歧义状态比较。",
            "joint_samples=0 时状态为 NoSamples 且测量列留空；零值不代表共锚或稳定。",
            "synthesis=off 是关闭合成基线，不能解读为修复后连接正确。",
            "未读取任何 PMX/VMD 资源；材质权重不用于推断身体部位或连接正确性。",
        ],
    }, metrics, diff_rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", nargs="?", type=Path, default=Path("Logs/latest-log-assets"))
    args = parser.parse_args()
    directory = args.directory
    if not directory.is_dir():
        parser.error(f"结果目录不存在: {directory}")
    summary, metrics, diffs = summarize(directory)
    (directory / "asset-results-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    write_csv(directory / "sample-metrics.csv", SAMPLE_FIELDS, metrics)
    write_csv(directory / "pair-diff.csv", PAIR_FIELDS, diffs)
    print(f"读取 {len(summary['read_files'])} 个 JSONL；输出 {len(metrics)} 条指标、{len(diffs)} 条端点差异")
    print(f"输出目录: {directory}")
    if summary["errors"]:
        print(f"JSONL 解析错误: {len(summary['errors'])}（详见 asset-results-summary.json）")


if __name__ == "__main__":
    main()
