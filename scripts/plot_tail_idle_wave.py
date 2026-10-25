"""Plot CSV from probe_tail_idle_wave; requires matplotlib."""
import argparse
import csv
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

parser = argparse.ArgumentParser()
parser.add_argument("csv", type=Path)
parser.add_argument("--out", type=Path, required=True)
parser.add_argument("--units", choices=["pmx", "blocks"], default="pmx")
args = parser.parse_args()
unit = "blocks" if args.units == "blocks" else "PMX units"
with args.csv.open(encoding="utf-8") as f:
    rows = [{k: (v if k == "case" else float(v)) for k, v in row.items()}
            for row in csv.DictReader(f)]
bones = sorted({int(row["bone"]) for row in rows})
tracks = {(case, bone): [r for r in rows if r["case"] == case and r["bone"] == bone]
          for case in ["idle", "moving", "reset"] for bone in bones}
events = {}
for case in ["idle", "moving", "reset"]:
    onsets = []
    was_active = False
    for row in tracks[case, bones[0]]:
        active = row.get("wave_height", row["lift_accel"]) > 0.0001
        if active and not was_active:
            onsets.append(row["time_s"])
        was_active = active
    events[case] = {"root_onsets_s": onsets,
                    "intervals_s": [b-a for a, b in zip(onsets, onsets[1:])]}

first = events["idle"]["root_onsets_s"][0]
fig, axes = plt.subplots(4, 1, figsize=(13, 11), constrained_layout=True)
colors = ["#277da8", "#43aa8b", "#e6ad22", "#ed765b", "#9063be"]
for i, bone in enumerate(bones):
    track = tracks["idle", bone]
    t = [r["time_s"] for r in track]
    label = f"Tail {i} (delay {track[0]['delay_s']:.3f}s)"
    axes[0].plot(t, [r["lift_accel"] for r in track], color=colors[i], label=label)
    for ax, field in zip(axes[1:], ["lift_accel", "delta_y", "delta_rear_z"]):
        ax.plot(t, [r[field] for r in track], color=colors[i])
for ax in axes:
    ax.grid(alpha=0.2)
    ax.axhline(0, color="#444444", linewidth=0.6)
    ax.set_xlabel("Time (s)")
for ax in axes[1:]:
    ax.set_xlim(max(0, first-1), first+8)
axes[0].set_title("teio-1: random 10-20s idle lift, root-to-tip propagation")
axes[0].set_ylabel(f"Acceleration ({unit}/s²)")
axes[0].legend(ncol=3, fontsize=9)
axes[1].set_ylabel(f"Acceleration ({unit}/s²)")
axes[1].set_title("First pulse: measured force and motion")
axes[2].set_ylabel(f"Up displacement ({unit})")
axes[3].set_ylabel(f"Rear displacement ({unit})")
fig.suptitle("Actual PMX/Bullet simulation; displacement relative to a simultaneous idle-lift-OFF model", fontsize=11)
args.out.mkdir(parents=True, exist_ok=True)
fig.savefig(args.out / "teio-idle-wave.png", dpi=150)
fig.savefig(args.out / "teio-idle-wave.svg")

summary = {"units": args.units, "events": events, "bones": bones, "first_pulse": []}
for bone in bones:
    track = [r for r in tracks["idle", bone] if first-1 <= r["time_s"] <= first+8]
    peak = max(track, key=lambda r: r["lift_accel"])
    peak_y = max(track, key=lambda r: r["delta_y"])
    summary["first_pulse"].append({
        "bone": bone, "delay_s": peak["delay_s"], "force_peak_time_s": peak["time_s"],
        "max_accel": peak["lift_accel"], "up_peak_time_s": peak_y["time_s"],
        "up_range": [min(r["delta_y"] for r in track), max(r["delta_y"] for r in track)],
        "rear_range": [min(r["delta_rear_z"] for r in track), max(r["delta_rear_z"] for r in track)]})
moving = [r["lift_accel"] for r in tracks["moving", bones[-1]] if 22 <= r["time_s"] <= 49]
summary["moving_accel_max_after_fade"] = max(moving)
(args.out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print(json.dumps(summary, indent=2))
