import copy
import json
import os
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from analyze import acquisition_report, histogram_quantile, jfr_report, performance, resource_report
import run


def summary(dropped=0, p95=100, errors=0):
    return {"actualOffered": 100 + dropped, "completed": 100, "dropped": dropped,
            "infrastructureErrorCount": errors, "latencyMilliseconds": {"p(50)": 20, "p(95)": p95, "p(99)": 1000}}


def histogram(when, count, buckets, summed, bounds=None):
    return {"name": "hikaricp.connections.acquire.seconds", "unit": "s",
            "histogram": {"aggregationTemporality": 2, "dataPoints": [{
                "timeUnixNano": str(int(when * 1e9)), "startTimeUnixNano": "1000000000",
                "attributes": [{"key": "pool", "value": {"stringValue": "HikariPool-1"}}],
                "count": str(count), "sum": summed, "explicitBounds": [.01, .1, 1] if bounds is None else bounds,
                "bucketCounts": list(map(str, buckets))}]}}


def samples():
    return [histogram(99, 10, [5, 4, 1, 0], .1), histogram(105, 30, [15, 13, 2, 0], .5)]


def resource(at):
    return {"monotonic": at, "memoryKiB": {"MemAvailable": 10000 - at},
            "app": {"rssKiB": 100 + at, "majorFaults": at, "userTicks": at * 4, "systemTicks": at},
            "vmstat": {"pgmajfault": at * 2, "pswpin": at, "pswpout": 0},
            "cpuTicks": [at * 10, 0, at * 5, at * 75, at * 10, 0, 0, 0, 0, 0],
            "postgres": [{"waitType": "Lock", "waitEvent": "transactionid", "connections": 2, "blocked": 2}],
            "errors": []}


class ProfilingContracts(unittest.TestCase):
    def test_dropped_work_consumes_offered_budget_and_preserves_exit99(self):
        result = performance(summary(dropped=2), 99)
        self.assertEqual(100 / 102, result["validOfferedFraction"])
        self.assertEqual(99, result["k6ExitCode"])
        self.assertFalse(result["performanceTargetMet"])

    def test_latency_failure_is_visible_even_with_successful_k6(self):
        result = performance(summary(p95=250), 0)
        self.assertTrue(result["k6ThresholdsMet"])
        self.assertFalse(result["allPerformanceGatesMet"])

    def test_infrastructure_errors_reduce_valid_completion(self):
        self.assertFalse(performance(summary(errors=2), 99)["performanceTargetMet"])

    def test_nan_and_inconsistent_counts_are_rejected(self):
        for value in (summary(p95=float("nan")), summary(errors=101)):
            with self.assertRaises(ValueError):
                performance(value, 0)

    def test_cumulative_acquisition_buckets_use_delta_and_real_seconds(self):
        result = acquisition_report(samples(), 100, 106)
        self.assertEqual(20, result["count"])
        self.assertEqual([10, 9, 1, 0], result["bucketDelta"])
        self.assertAlmostEqual(.02, result["meanSeconds"])
        self.assertAlmostEqual(.1, result["estimatedP95Seconds"])
        self.assertTrue(result["valid"])

    def test_old_unbucketed_series_is_rejected_instead_of_nan_success(self):
        data = [histogram(99, 10, [10], .1, []), histogram(105, 30, [30], .5, [])]
        with self.assertRaisesRegex(ValueError, "absent"):
            acquisition_report(data, 100, 106)

    def test_unknown_units_are_not_silently_treated_as_seconds(self):
        data = samples()
        data[1]["unit"] = "ms"
        with self.assertRaisesRegex(ValueError, "unit"):
            acquisition_report(data, 100, 106)

    def test_multiple_series_cannot_be_interleaved_into_one_histogram(self):
        data = samples()
        other = copy.deepcopy(data[1])
        other["histogram"]["dataPoints"][0]["attributes"][0]["value"]["stringValue"] = "HikariPool-2"
        with self.assertRaisesRegex(ValueError, "multiple pools"):
            acquisition_report(data + [other], 100, 106)

    def test_missing_pool_identity_is_rejected(self):
        data = samples()
        data[0]["histogram"]["dataPoints"][0]["attributes"] = []
        with self.assertRaisesRegex(ValueError, "identify one pool"):
            acquisition_report(data, 100, 106)

    def test_counter_reset_negative_buckets_and_missing_window_fail(self):
        reset = samples()
        reset[1]["histogram"]["dataPoints"][0]["startTimeUnixNano"] = "2000000000"
        negative = samples()
        negative[1]["histogram"]["dataPoints"][0]["bucketCounts"] = ["1", "28", "1", "0"]
        for data, start, end in ((reset, 100, 106), (negative, 100, 106), (samples(), 200, 205)):
            with self.assertRaises(ValueError):
                acquisition_report(data, start, end)

    def test_overflow_quantile_is_unknown_not_infinite_or_clamped(self):
        self.assertIsNone(histogram_quantile([.01, .1], [0, 1, 99], .95))

    def test_jfr_requires_explicit_allowlist(self):
        for name in ("jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty", "jdk.SystemProcess"):
            with self.assertRaisesRegex(ValueError, "Unexpected event"):
                jfr_report({"recording": {"events": [{"type": name, "values": {}}]}})

    def test_thread_wait_duration_and_components_are_descriptive(self):
        document = {"recording": {"events": [
            {"type": "jdk.ThreadPark", "values": {"duration": "PT0.12S", "stackTrace": {"frames": [
                {"method": {"type": {"name": "com/zaxxer/hikari/util/ConcurrentBag"}, "name": "borrow"}}]}}},
            {"type": "jdk.CPULoad", "values": {}}, {"type": "jdk.ExecutionSample", "values": {}},
            {"type": "jdk.GCPhasePause", "values": {"duration": "PT0.005S"}}]}}
        report = jfr_report(document)
        self.assertTrue(report["valid"])
        self.assertEqual(.12, report["waits"]["jdk.ThreadPark:hikari"]["summedThreadSeconds"])
        self.assertEqual(.005, report["maxGcPauseSeconds"])

    def test_resource_deltas_and_postgres_blocking_are_separate(self):
        report = resource_report([resource(1), resource(2)])
        self.assertTrue(report["valid"])
        self.assertEqual(2, report["hostVmCounterDeltas"]["pgmajfault"])
        self.assertEqual(2, report["maxObservedBlockedPgConnections"])
        self.assertAlmostEqual(.15, report["hostBusyFraction"])
        self.assertEqual(4, report["postgresWaitConnectionSamples"]["Lock:transactionid"])

    def test_sampler_failures_and_large_gaps_are_not_hidden(self):
        self.assertFalse(resource_report([resource(1), resource(20)])["valid"])
        data = [resource(1), resource(2)]
        data[1]["errors"] = ["postgres timeout"]
        self.assertFalse(resource_report(data)["valid"])

    def test_campaign_execution_success_does_not_promote_failed_targets(self):
        runs = [{"label": label, "executionCompleted": True, "instrumentationValid": True,
                 "oraclesPassed": True, "cleanupExitCode": 0, "allPerformanceGatesMet": False,
                 "measurement": performance(summary(dropped=10), 99)} for label in ("01-pool2", "02-pool16", "03-pool16", "04-pool2")]
        report = run.report_campaign(runs)
        self.assertTrue(report["executionAndOraclesPassed"])
        self.assertFalse(report["allMeasuredPerformanceGatesMet"])
        self.assertEqual(2, len(report["pairedDifferences"]))
        self.assertFalse(run.report_campaign(runs[:3])["executionAndOraclesPassed"])

    def test_actual_runner_contract_reconciles_after_k6_exit99(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            workload = root / "experiments/load/k6-workload.js"
            workload.parent.mkdir(parents=True)
            workload.write_text("fixture")
            output = root / "measured"
            calls = []

            def execute(args, **kwargs):
                calls.append(args[0])
                if args[0] == "docker":
                    (output / "summary.json").write_text(json.dumps(summary(dropped=3)))
                    return subprocess.CompletedProcess(args, 99, "threshold failed", "")
                (output / "reconciliation.json").write_text(json.dumps({"passed": True}))
                return subprocess.CompletedProcess(args, 0, "oracle passed", "")

            with patch.object(run, "ROOT", root), patch.object(run, "command", side_effect=execute):
                result = run.run_load(output, 100, 45, "fixture", {"COMPOSE_PROJECT_NAME": "owned"})
            self.assertEqual(["docker", "node"], calls)
            self.assertTrue(result["executionCompleted"])
            self.assertTrue(result["reconciliation"]["passed"])
            self.assertFalse(result["performanceTargetMet"])

    def test_failed_startup_still_cleans_only_owned_project(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            output, private = root / "output", root / "private"
            output.mkdir()
            private.mkdir()
            jar = root / "fixture.jar"
            jar.write_bytes(b"fixture")
            calls = []

            def execute(args, **kwargs):
                calls.append((args, kwargs.get("env", {})))
                return subprocess.CompletedProcess(args, 1 if "up" in args else 0, "", "")

            with patch.object(run, "command", side_effect=execute), patch.dict(os.environ, {"GITHUB_RUN_ID": "1234"}):
                result = run.variant(1, 2, output, private, jar, jar, {})
            self.assertFalse(result["executionCompleted"])
            self.assertEqual(0, result["cleanupExitCode"])
            down = next(call for call in calls if "down" in call[0])
            self.assertEqual("profile-1234-1", down[1]["COMPOSE_PROJECT_NAME"])
            self.assertEqual([], list(private.iterdir()))


if __name__ == "__main__":
    unittest.main()
