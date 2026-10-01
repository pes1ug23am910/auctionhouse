"""Attribution and client-command contracts for both fixed experiment axes."""
import copy
import json
import os
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import design
import run


def workload_summary(initial=80):
    return {"configuration": {"authMode": "local-demo", "seed": 42,
        "ratePerSecond": 100, "durationSeconds": 45, "preAllocatedVUs": initial, "maxVUs": 80,
        "mix": "deterministic seeded approximately 75% browse / 25% bid", "comparisonLabel": "fixture",
        "dataset": "one newly created auction"}, "nominalScheduledTarget": 4500,
        "actualOffered": 4501, "completed": 4501, "dropped": 0, "infrastructureErrorCount": 0,
        "latencyMilliseconds": {"p(50)": 10, "p(95)": 100, "p(99)": 200}}


def campaign(experiment="preallocation-abba"):
    rows = []
    for spec in design.experiment_plan(experiment)["variants"]:
        row = {**spec, "experiment": experiment, "appSHA256": "a" * 64,
               "executionCompleted": True, "instrumentationValid": True, "oraclesPassed": True,
               "cleanupExitCode": 0, "allPerformanceGatesMet": True}
        for phase, rate, duration, suffix, initial in (("warmup", 50, 15, "warmup", 40),
                ("measurement", 100, 45, "measured", spec["preallocatedVUs"])):
            expected = design.expected_configuration(rate, duration, spec["label"] + "-" + suffix, initial)
            row[phase] = {"configuration": {"verified": True, "expected": expected, "observed": dict(expected)},
                          "latencyMilliseconds": {"p95": 100}, "dropped": 0, "validOfferedFraction": 1}
        rows.append(row)
    return rows


def progress(global_seconds, active, allocated, completed, elapsed):
    return (f"running (0m{global_seconds:04.1f}s), {active:02d}/{allocated} VUs, {completed} complete and 0 interrupted iterations\n"
            f"auction   [  50% ] {active:02d}/{allocated} VUs  {elapsed:04.1f}s/45s  100.00 iters/s\n")


class ExperimentContracts(unittest.TestCase):
    def test_default_and_opt_in_plans_preserve_only_the_intended_axis(self):
        default = design.experiment_plan()
        alternative = design.experiment_plan("preallocation-abba")
        self.assertEqual([(2, 40, 80), (16, 40, 80), (16, 40, 80), (2, 40, 80)],
                         [(s["pool"], s["preallocatedVUs"], s["maxVUs"]) for s in default["variants"]])
        self.assertEqual([(16, 40, 80), (16, 80, 80), (16, 80, 80), (16, 40, 80)],
                         [(s["pool"], s["preallocatedVUs"], s["maxVUs"]) for s in alternative["variants"]])
        with self.assertRaises(ValueError):
            design.experiment_plan("unbounded")

    def test_reversed_pair_is_reported_on_vu_axis_without_pool_attribution(self):
        rows = campaign()
        for row, latency, dropped in zip(rows, (200, 100, 75, 225), (13, 0, 0, 10)):
            row["measurement"].update(latencyMilliseconds={"p95": latency}, dropped=dropped)
            row["allPerformanceGatesMet"] = dropped == 0
        report = run.report_campaign(rows, "preallocation-abba")
        self.assertTrue(report["experimentDesignValid"])
        self.assertTrue(report["executionAndOraclesPassed"])
        self.assertFalse(report["allMeasuredPerformanceGatesMet"])
        self.assertFalse(report["validAcceptance"])
        self.assertEqual([-100, -150], [pair["interventionMinusControlP95Ms"] for pair in report["pairedDifferences"]])
        self.assertEqual([-13, -10], [pair["interventionMinusControlDropped"] for pair in report["pairedDifferences"]])
        self.assertEqual(["preallocatedVUs"] * 2, [pair["axis"] for pair in report["pairedDifferences"]])
        self.assertEqual("04-prealloc40", report["pairedDifferences"][1]["controlRun"])
        self.assertNotIn("pool16MinusPool2", json.dumps(report))

    def test_wrong_axis_order_hash_or_warmup_cannot_be_promoted(self):
        cases = []
        for key, value in (("pool", 2), ("maxVUs", 160), ("preallocatedVUs", 40), ("appSHA256", "b" * 64)):
            rows = campaign()
            rows[1][key] = value
            cases.append(rows)
        rows = campaign()
        rows[1], rows[3] = rows[3], rows[1]
        cases.append(rows)
        rows = campaign()
        rows[1]["warmup"]["configuration"]["observed"]["preAllocatedVUs"] = 80
        cases.append(rows)
        for rows in cases:
            with self.subTest(rows=rows):
                report = run.report_campaign(rows, "preallocation-abba")
                self.assertFalse(report["experimentDesignValid"])
                self.assertFalse(report["executionAndOraclesPassed"])
                self.assertFalse(report["validAcceptance"])
                self.assertEqual([], report["pairedDifferences"])
                self.assertTrue(report["allMeasuredPerformanceGatesMet"])
        self.assertFalse(run.report_campaign(campaign()[:3], "preallocation-abba")["validAcceptance"])

    def test_observed_configuration_requires_exact_request_not_just_passing_counts(self):
        expected = design.expected_configuration(100, 45, "fixture", 80)
        for key, value in (("preAllocatedVUs", 40), ("maxVUs", 160), ("ratePerSecond", 50),
                           ("durationSeconds", 15), ("seed", 99), ("authMode", "session"),
                           ("comparisonLabel", "other"), ("seed", True)):
            data = workload_summary()
            data["configuration"][key] = value
            result = design.configuration_check(data, expected)
            self.assertFalse(result["verified"])
            self.assertIn(key, result["mismatchedFields"])
        data = workload_summary()
        data["nominalScheduledTarget"] = 4501
        self.assertFalse(design.configuration_check(data, expected)["verified"])

    def test_actual_runner_uses_chosen_preallocation_and_retains_configuration_failure(self):
        for observed in (80, 40):
            with self.subTest(observed=observed), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory)
                workload = root / "experiments/load/k6-workload.js"
                workload.parent.mkdir(parents=True)
                workload.write_text("fixture")
                output = root / "measured"
                calls = []
                def execute(args, **kwargs):
                    calls.append(args)
                    if args[0] == "docker":
                        (output / "summary.json").write_text(json.dumps(workload_summary(observed)))
                        return subprocess.CompletedProcess(args, 0, progress(1, 2, observed, 78, .8), "")
                    (output / "reconciliation.json").write_text(json.dumps({"passed": True}))
                    return subprocess.CompletedProcess(args, 0, "oracle passed", "")
                with patch.object(run, "ROOT", root), patch.object(run, "command", side_effect=execute):
                    result = run.run_load(output, 100, 45, "fixture", {"COMPOSE_PROJECT_NAME": "owned"}, 80)
                for argument in ("PREALLOCATED_VUS=80", "MAX_VUS=80", "RATE=100", "DURATION_SECONDS=45", "SEED=42", "--memory=256m", "--cpus=1"):
                    self.assertIn(argument, calls[0])
                self.assertEqual(["docker", "node"], [args[0] for args in calls])
                self.assertEqual(observed == 80, result["executionCompleted"])
                self.assertEqual(observed == 80, result["configuration"]["verified"])
                self.assertTrue(result["reconciliation"]["passed"])
                self.assertTrue(result["allPerformanceGatesMet"])
                self.assertEqual(80, json.loads((output / "environment.json").read_text())["PreallocatedVUs"])
                self.assertEqual(observed, json.loads((output / "client-progress.json").read_text())["maxObservedAllocatedVUs"])

    def test_treatment_variant_keeps_warmup40_then_measures80_and_cleans_up(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            output, private = root / "output", root / "private"
            output.mkdir()
            private.mkdir()
            jar = root / "fixture.jar"
            jar.write_bytes(b"same artifact")
            inventory = [{"id": str(i) * 64, "project": "profile-1234-2", "running": True, "service": service}
                         for i, service in enumerate(run.SERVICES)]
            app = Mock(pid=1234)
            app.poll.return_value = 0
            phases = []
            calls = []
            def execute(args, **kwargs):
                calls.append(args)
                stdout = ""
                if "ps" in args:
                    stdout = "\n".join(item["id"] for item in inventory)
                elif "inspect" in args:
                    stdout = "\n".join(json.dumps(item) for item in inventory)
                return subprocess.CompletedProcess(args, 0, stdout, "")
            def load(dest, rate, duration, label, env, preallocated=40):
                phases.append((rate, duration, preallocated, env["AUCTIONHOUSE_DB_POOL_SIZE"]))
                dest.mkdir()
                (dest / "environment.json").write_text(json.dumps({"StartedAt": "2026-10-01T00:00:00Z"}))
                (dest / "exit.json").write_text(json.dumps({"FinishedAt": "2026-10-01T00:01:00Z"}))
                return {"executionCompleted": True, "reconciliation": {"passed": True}, "oracleExitCode": 0,
                        "performanceTargetMet": True, "allPerformanceGatesMet": True}
            def sampler(stop, dest, *args):
                dest.write_text("{}\n{}\n")
            with patch.object(run, "command", side_effect=execute), patch.object(run.subprocess, "Popen", return_value=app), \
                 patch.dict(os.environ, {"GITHUB_RUN_ID": "1234"}), patch.object(run.os, "sched_getaffinity", return_value={0, 1}, create=True), \
                 patch.object(run, "wait_url"), patch.object(run, "run_load", side_effect=load), patch.object(run.time, "sleep"), \
                 patch.object(run, "sample_resources", side_effect=sampler), patch.object(run, "export_jfr", return_value={"valid": True}), \
                 patch.object(run, "selected_metrics", return_value=[]), patch.object(run, "acquisition_report", return_value={"valid": True}), \
                 patch.object(run, "resource_report", return_value={"valid": True}):
                result = run.variant(2, 16, output, private, jar, jar, {}, 80, "preallocation-abba")
            self.assertEqual([(50, 15, 40, "16"), (100, 45, 80, "16")], phases)
            self.assertTrue(result["instrumentationValid"])
            self.assertTrue(result["oraclesPassed"])
            self.assertEqual("02-prealloc80", result["label"])
            self.assertEqual(80, result["maxVUs"])
            self.assertEqual([], result["cleanupErrors"])
            self.assertTrue(any("down" in args for args in calls))
            self.assertEqual([], list(private.iterdir()))

    def test_progress_separates_setup_clock_and_retains_growth_without_causal_claim(self):
        raw = progress(1, 1, 40, 78, .8) + progress(31, 0, 42, 3076, 30.8) + progress(44, 9, 53, 4356, 43.8)
        result = design.allocation_progress(raw, 40, 80, 13)
        self.assertTrue(result["valid"])
        self.assertEqual(13, result["additionalObservedVUs"])
        self.assertTrue(result["additionalObservedVUsEqualDropped"])
        self.assertEqual(31, result["samples"][1]["globalElapsedSeconds"])
        self.assertEqual(30.8, result["samples"][1]["scenarioElapsedSeconds"])
        self.assertEqual(9, result["maxObservedActiveVUs"])
        self.assertNotIn("threshold", json.dumps(result))
        self.assertFalse(design.allocation_progress(raw, 40, 80, 14)["additionalObservedVUsEqualDropped"])

    def test_invalid_or_missing_progress_is_unavailable_not_zero_work(self):
        missing = design.allocation_progress("no progress", 80, 80, 0)
        self.assertFalse(missing["available"])
        self.assertIsNone(missing["additionalObservedVUs"])
        for raw in (progress(1, 81, 80, 3, .8), progress(1, 1, 40, 3, .8),
                    progress(2, 1, 80, 5, 1.8) + progress(1, 1, 80, 3, .8)):
            self.assertFalse(design.allocation_progress(raw, 80, 80, 0)["valid"])


if __name__ == "__main__":
    unittest.main()
