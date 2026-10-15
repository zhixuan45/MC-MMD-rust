#!/usr/bin/env python3
"""汇总游戏日志中的 [MMD透明性能] schemaVersion=1 记录。"""

from __future__ import annotations

import argparse
import json
import sys
from collections import OrderedDict
from pathlib import Path
from typing import Any

MARKER = "[MMD透明性能]"
STAGES = ("CLASSIFY", "CANDIDATES", "DEPTH_KEYS", "SORT", "INDEX_BUILD", "UPLOAD", "SUBMIT", "READBACK")
STAGE_FIELDS = ("avgNs", "medianNs", "p95Ns")
REQUIRED_COUNTERS = ("CANDIDATE_BUILD", "CANDIDATE_REUSE", "CANDIDATE_TRIANGLES", "VISIBLE_TRIANGLES", "RUNS",
            "DRAW_OPAQUE", "DRAW_TRANSLUCENT", "DRAW_DEPTH_SEAL", "DRAW_SHADOW", "READBACK_CALLS",
            "READBACK_BYTES", "UPLOAD_BYTES")
OPTIONAL_COUNTERS = ("BATCH_SUBMISSIONS", "BATCH_RUNS", "BATCH_FALLBACK")
COUNTERS = REQUIRED_COUNTERS + OPTIONAL_COUNTERS
PHASES = ("standing", "walking", "running")


class RecordError(ValueError):
    pass


def _integer(value: Any, where: str, *, positive: bool = False) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < (1 if positive else 0):
        raise RecordError(f"{where} must be an integer >= {1 if positive else 0}")
    return value


def validate(record: Any) -> dict[str, Any]:
    if not isinstance(record, dict):
        raise RecordError("record must be a JSON object")
    if isinstance(record.get("schemaVersion"), bool) or not isinstance(record.get("schemaVersion"), int) or record["schemaVersion"] != 1:
        raise RecordError("schemaVersion must be 1")
    if isinstance(record.get("modelId"), bool) or not isinstance(record.get("modelId"), int):
        raise RecordError("modelId must be an integer")
    for key in ("modelName", "skinning", "scene"):
        if not isinstance(record.get(key), str):
            raise RecordError(f"{key} must be a string")
    _integer(record.get("samples"), "samples", positive=True)
    if "windowSamples" in record:
        window_samples = _integer(record["windowSamples"], "windowSamples", positive=True)
        if window_samples > min(record["samples"], 120):
            raise RecordError("windowSamples must be <= min(samples, 120)")
    for key in ("renderCallMedianNs", "renderCallP95Ns"):
        _integer(record.get(key), key)
    stages = record.get("stages")
    if not isinstance(stages, dict):
        raise RecordError("stages must be an object")
    for stage in STAGES:
        values = stages.get(stage)
        if not isinstance(values, dict):
            raise RecordError(f"stages.{stage} must be an object")
        for field in STAGE_FIELDS:
            _integer(values.get(field), f"stages.{stage}.{field}")
    counters = record.get("counters")
    if not isinstance(counters, dict):
        raise RecordError("counters must be an object")
    for counter in OPTIONAL_COUNTERS:
        counters.setdefault(counter, 0)
    for counter in COUNTERS:
        _integer(counters.get(counter), f"counters.{counter}")
    return record


def parse_logs(logs: list[tuple[Path, str]]) -> tuple[OrderedDict, list[str]]:
    groups: OrderedDict[tuple[str, int, str, str, str], dict[str, Any]] = OrderedDict()
    errors: list[str] = []
    for path, phase in logs:
        path = Path(path)
        try:
            stream = path.open("r", encoding="utf-8-sig", errors="strict")
        except (OSError, UnicodeError) as exc:
            errors.append(f"{path}:0: cannot read log: {exc}")
            continue
        with stream:
            for line_number, line in enumerate(stream, 1):
                marker_at = line.find(MARKER)
                if marker_at < 0:
                    continue
                payload = line[marker_at + len(MARKER):].strip()
                try:
                    parsed = validate(json.loads(payload))
                except (json.JSONDecodeError, RecordError) as exc:
                    errors.append(f"{path}:{line_number}: {exc}")
                    continue
                key = (phase, parsed["modelId"], parsed["modelName"], parsed["skinning"], parsed["scene"])
                group = groups.setdefault(key, {
                    "phase": phase, "modelId": parsed["modelId"], "modelName": parsed["modelName"],
                    "skinning": parsed["skinning"], "scene": parsed["scene"], "samples": 0,
                    "counters": {name: 0 for name in COUNTERS}, "stageWeightedSums": {name: 0 for name in STAGES},
                    "latest": None, "logRecords": 0,
                })
                samples = parsed["samples"]
                group["samples"] += samples
                group["logRecords"] += 1
                for name in COUNTERS:
                    group["counters"][name] += parsed["counters"][name]
                for name in STAGES:
                    group["stageWeightedSums"][name] += parsed["stages"][name]["avgNs"] * samples
                group["latest"] = {
                    "renderCall": {"medianNs": parsed["renderCallMedianNs"], "p95Ns": parsed["renderCallP95Ns"]},
                    "windowSamples": parsed.get("windowSamples", min(samples, 120)),
                    "stages": parsed["stages"],
                    "source": f"{path}:{line_number}",
                }
    for group in groups.values():
        group["averagePerRenderSample"] = {
            name: group["counters"][name] / group["samples"] for name in COUNTERS
        }
        group["stages"] = {
            name: {
                "weightedAvgNsByRenderSamples": group["stageWeightedSums"][name] / group["samples"],
                "latestIntervalAvgNs": group["latest"]["stages"][name]["avgNs"],
                "latestWindow": {
                    "medianNs": group["latest"]["stages"][name]["medianNs"],
                    "p95Ns": group["latest"]["stages"][name]["p95Ns"],
                    "samples": group["latest"]["windowSamples"],
                },
            } for name in STAGES
        }
        del group["stageWeightedSums"]
    return groups, errors


def output_json(groups: OrderedDict) -> str:
    return json.dumps({
        "schemaVersion": 1,
        "units": {
            "time": "nanoseconds",
            "counterTotals": {name: "bytes" if name.endswith("BYTES") else "count" for name in COUNTERS},
            "averagePerRenderSample": {name: "bytes/sample" if name.endswith("BYTES") else "count/sample" for name in COUNTERS},
        },
        "percentileSemantics": "latestWindow only; values are source-window percentiles, never averaged across log rows",
        "stageAverageSemantics": "weightedAvgNsByRenderSamples weights each interval stage avgNs by that row's render samples; interval averages include skipped-stage zeroes",
        "groups": list(groups.values()),
    }, ensure_ascii=False, indent=2)


def output_markdown(groups: OrderedDict) -> str:
    lines = ["# 半透明渲染性能日志摘要", "", "时间单位为 ns。计数器总量按日志间隔累加；平均每 sample = 累计计数 / render samples。",
             "阶段加权均值按每条日志的 render samples 加权；阶段间隔均值已将跳过阶段的调用按零计入。所有 median/P95 均只取该组最新记录所带的 latest-window 值，不跨日志平均，也不代表游戏帧时间分位数。", ""]
    for g in groups.values():
        lines += [f"## {g['phase']} · {g['modelName']} (id {g['modelId']})", "",
                  f"- skinning: `{g['skinning']}`; scene: `{g['scene']}`; render samples: {g['samples']}; log records: {g['logRecords']}",
                  f"- latest window ({g['latest']['source']}, {g['latest']['windowSamples']} render samples): render call median {g['latest']['renderCall']['medianNs']} ns; P95 {g['latest']['renderCall']['p95Ns']} ns", "",
                  "### 阶段耗时", "", "| 阶段 | 加权 avgNs（按 render samples） | 最新 interval avgNs | 最新窗口 medianNs | 最新窗口 p95Ns |", "|---|---:|---:|---:|---:|"]
        for name in STAGES:
            s = g["stages"][name]
            lines.append(f"| {name} | {s['weightedAvgNsByRenderSamples']:.2f} | {s['latestIntervalAvgNs']} | {s['latestWindow']['medianNs']} | {s['latestWindow']['p95Ns']} |")
        lines += ["", "### 计数器", "", "| 计数器 | interval total | 平均每 render sample |", "|---|---:|---:|"]
        for name in COUNTERS:
            lines.append(f"| {name} | {g['counters'][name]} | {g['averagePerRenderSample'][name]:.4f} |")
        lines.append("")
    lines += ["这些数据是模型作用域渲染调用统计，不是游戏帧时间分布；汇总日志本身也不能证明性能改善。请用同模型、同场景、同配置的前后对照和游戏验收确认效果。", ""]
    return "\n".join(lines)


def parse_phase_spec(spec: str) -> tuple[str, Path]:
    if "=" not in spec:
        raise argparse.ArgumentTypeError("phase must use PHASE=LOG_PATH")
    phase, filename = spec.split("=", 1)
    if not phase.strip() or not filename:
        raise argparse.ArgumentTypeError("phase and log path must be non-empty")
    return phase.strip(), Path(filename)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", action="append", type=parse_phase_spec, default=[], metavar="PHASE=LOG",
                        help="label one input log (repeat for standing/walking/running")
    parser.add_argument("--log", action="append", type=Path, default=[], help="unlabelled input log; repeatable")
    parser.add_argument("--format", choices=("json", "markdown"), default="json")
    parser.add_argument("--output", type=Path, help="output path; defaults to stdout")
    args = parser.parse_args(argv)
    if args.phase and args.log:
        parser.error("use --phase or --log inputs, not both")
    inputs = [(path, phase) for phase, path in args.phase] if args.phase else [(path, "unlabeled") for path in args.log]
    if not inputs:
        parser.error("provide at least one --phase PHASE=LOG or --log LOG")
    for _, phase in inputs:
        if phase not in PHASES and phase != "unlabeled":
            print(f"warning: unrecognized phase label {phase!r}; retaining it verbatim", file=sys.stderr)
    groups, errors = parse_logs(inputs)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        print(f"failed: {len(errors)} malformed log record(s); no summary written", file=sys.stderr)
        return 2
    if not groups:
        print("no [MMD透明性能] records found; no summary written", file=sys.stderr)
        return 1
    rendered = output_json(groups) if args.format == "json" else output_markdown(groups)
    if args.output:
        try:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered + "\n", encoding="utf-8")
        except OSError as exc:
            print(f"{args.output}: cannot write summary: {exc}", file=sys.stderr)
            return 2
    else:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
