import json
import tempfile
import unittest
from collections import OrderedDict
from pathlib import Path

import summarize


def row(model_id=1, model_name="杏辞", scene="WORLD", samples=10, median=100, p95=200, stage_avg=20):
    return {
        "schemaVersion": 1, "modelId": model_id, "modelName": model_name,
        "skinning": "GPU_COMPUTE", "scene": scene, "samples": samples,
        "renderCallMedianNs": median, "renderCallP95Ns": p95,
        "stages": {name: {"avgNs": stage_avg, "medianNs": stage_avg + 1, "p95Ns": stage_avg + 2}
                   for name in summarize.STAGES},
        "counters": {name: samples * 2 for name in summarize.COUNTERS},
    }


class SummarizeTests(unittest.TestCase):
    def test_batch_metrics_accept_historical_logs_and_keep_new_counts(self):
        old = row()
        for key in summarize.OPTIONAL_COUNTERS:
            del old["counters"][key]
        self.assertEqual(summarize.validate(old)["counters"]["BATCH_SUBMISSIONS"], 0)
        new = row()
        self.assertEqual(summarize.validate(new)["counters"]["BATCH_RUNS"], 20)
        new["counters"]["BATCH_RUNS"] = -1
        with self.assertRaisesRegex(summarize.RecordError, "BATCH_RUNS"):
            summarize.validate(new)

    def test_phase_chinese_models_scenes_weighting_and_latest_window(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "游戏.log"
            records = [row(samples=10, median=100, p95=180, stage_avg=20),
                       row(samples=30, median=300, p95=480, stage_avg=60),
                       row(model_id=2, model_name="兰序", scene="GUI", samples=5)]
            path.write_text("noise ignored\n" + "\n".join("[MMD透明性能] " + json.dumps(x, ensure_ascii=False) for x in records), encoding="utf-8")
            groups, errors = summarize.parse_logs([(path, "walking")])
            self.assertEqual(errors, [])
            first, second = list(groups.values())
            self.assertEqual(first["phase"], "walking")
            self.assertEqual(first["modelName"], "杏辞")
            self.assertEqual(first["samples"], 40)
            self.assertEqual(first["counters"]["RUNS"], 80)
            self.assertEqual(first["averagePerRenderSample"]["RUNS"], 2)
            self.assertEqual(first["stages"]["SORT"]["weightedAvgNsByRenderSamples"], 50)
            self.assertEqual(first["latest"]["renderCall"]["medianNs"], 300)
            self.assertEqual(first["latest"]["renderCall"]["p95Ns"], 480)
            self.assertEqual(first["latest"]["windowSamples"], 30)
            self.assertEqual(first["stages"]["SORT"]["latestIntervalAvgNs"], 60)
            self.assertEqual(second["modelName"], "兰序")
            self.assertEqual(second["scene"], "GUI")

    def test_bad_json_and_schema_report_line_numbers(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "bad.log"
            invalid = row()
            del invalid["stages"]["SORT"]
            path.write_text("skip\n[MMD透明性能] {not-json}\n[MMD透明性能] " + json.dumps(invalid), encoding="utf-8")
            groups, errors = summarize.parse_logs([(path, "standing")])
            self.assertFalse(groups)
            self.assertEqual(len(errors), 2)
            self.assertIn(":2:", errors[0])
            self.assertIn(":3:", errors[1])
            self.assertIn("stages.SORT", errors[1])

    def test_cli_does_not_write_success_summary_on_invalid_log(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "bad.log"
            target = Path(temp) / "out.json"
            source.write_text("[MMD透明性能] {}\n", encoding="utf-8")
            status = summarize.main(["--phase", f"running={source}", "--output", str(target)])
            self.assertEqual(status, 2)
            self.assertFalse(target.exists())

    def test_stage_schema_is_complete_and_units_are_explicit(self):
        record = row()
        del record["stages"]["UPLOAD"]["p95Ns"]
        with self.assertRaisesRegex(summarize.RecordError, "UPLOAD.p95Ns"):
            summarize.validate(record)
        output = json.loads(summarize.output_json(OrderedDict()))
        self.assertEqual(output["units"]["time"], "nanoseconds")
        self.assertEqual(output["units"]["counterTotals"]["UPLOAD_BYTES"], "bytes")
        self.assertIn("latestWindow only", output["percentileSemantics"])


if __name__ == "__main__":
    unittest.main()
