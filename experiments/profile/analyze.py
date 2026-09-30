"""Pure readers for the bounded, same-artifact Linux profiling campaign."""
import collections
import datetime as dt
import gzip
import json
import math
import os
import pathlib
import tempfile

EVENTS = (
    "jdk.ExecutionSample", "jdk.NativeMethodSample", "jdk.ThreadPark",
    "jdk.JavaMonitorEnter", "jdk.SocketRead", "jdk.GarbageCollection",
    "jdk.GCPhasePause", "jdk.CPULoad", "jdk.ThreadCPULoad",
)


def timestamp(value):
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def performance(summary, exit_code):
    """A successful harness/oracle is distinct from attaining a latency target."""
    offered, completed = summary["actualOffered"], summary["completed"]
    errors = summary["infrastructureErrorCount"]
    latency = {name: summary["latencyMilliseconds"][f"p({percentile})"] for name, percentile in (("p50", 50), ("p95", 95), ("p99", 99))}
    if not all(finite(value) and value >= 0 for value in latency.values()):
        raise ValueError("Invalid logical latency percentiles")
    p95 = latency["p95"]
    if not all(isinstance(n, int) and not isinstance(n, bool) and n >= 0 for n in (offered, completed, errors, summary["dropped"])) or completed > offered or errors > completed:
        raise ValueError("Invalid offered/completed/error counts")
    valid = completed - errors
    fraction = valid / offered if offered > 0 else 0
    target = offered > 0 and finite(p95) and fraction >= .99 and p95 < 250
    return {
        "k6ExitCode": exit_code,
        "k6ThresholdsMet": exit_code == 0,
        "offered": offered, "completed": completed, "validCompleted": valid,
        "dropped": summary["dropped"], "infrastructureErrors": errors,
        "validOfferedFraction": fraction,
        "latencyMilliseconds": latency,
        "performanceTargetMet": target,
        "allPerformanceGatesMet": target and exit_code == 0,
    }


def duration_seconds(value):
    # jfr print JSON emits ISO-8601 durations for timespan fields.
    if not isinstance(value, str) or not value.startswith("PT") or not value.endswith("S"):
        raise ValueError("Unexpected JFR duration")
    body = value[2:-1]
    minutes, seconds = body.split("M") if "M" in body else ("0", body)
    return float(minutes) * 60 + float(seconds)


def jfr_report(document):
    events = document["recording"]["events"]
    counts = collections.Counter()
    waits = collections.defaultdict(lambda: {"events": 0, "summedThreadSeconds": 0.0, "maxSeconds": 0.0})
    stack_samples = collections.Counter()
    max_gc = 0.0
    for event in events:
        kind, values = event["type"], event["values"]
        if kind not in EVENTS:
            raise ValueError("Unexpected event in selected JFR export: " + kind)
        counts[kind] += 1
        frames = values.get("stackTrace") or {}
        methods = []
        for frame in frames.get("frames", []):
            method = frame.get("method") or {}
            owner = (method.get("type") or {}).get("name", "")
            methods.append(owner.replace("/", ".") + "." + method.get("name", ""))
        component = "other"
        for match, label in [("com.zaxxer.hikari", "hikari"), ("org.postgresql", "postgres-driver"),
                             ("org.apache.kafka", "kafka"), ("org.springframework.session", "jdbc-session"),
                             ("io.opentelemetry", "telemetry")]:
            if any(match in method for method in methods):
                component = label
                break
        if kind in ("jdk.ThreadPark", "jdk.JavaMonitorEnter", "jdk.SocketRead"):
            seconds = duration_seconds(values["duration"])
            if not finite(seconds) or seconds < 0:
                raise ValueError("Invalid JFR duration")
            key = kind + ":" + component
            waits[key]["events"] += 1
            waits[key]["summedThreadSeconds"] += seconds
            waits[key]["maxSeconds"] = max(waits[key]["maxSeconds"], seconds)
        if kind in ("jdk.ExecutionSample", "jdk.NativeMethodSample"):
            stack_samples[component] += 1
        if kind == "jdk.GCPhasePause":
            max_gc = max(max_gc, duration_seconds(values["duration"]))
    return {"eventCounts": dict(counts), "waits": dict(waits),
            "executionSamplesByStackComponent": dict(stack_samples), "maxGcPauseSeconds": max_gc,
            "valid": counts["jdk.CPULoad"] > 0 and counts["jdk.ExecutionSample"] > 0,
            "limits": "Thresholded sampled events; thread wait totals overlap and are not request wall time. Stack categories are descriptive, not causal attribution."}


def histogram_quantile(bounds, counts, quantile):
    total = sum(counts)
    if total <= 0:
        return None
    target, cumulative, lower = quantile * total, 0, 0.0
    for upper, count in zip(bounds, counts):
        if cumulative + count >= target and count:
            return lower + (upper - lower) * (target - cumulative) / count
        cumulative += count
        lower = upper
    # Overflow bucket: an exact finite quantile is not known.
    return None


def acquisition_report(samples, start, end):
    """Use actual OTLP metadata/units and cumulative bucket deltas, not a guessed PromQL name."""
    points = []
    identities = set()
    for sample in samples:
        if sample.get("name") != "hikaricp.connections.acquire.seconds":
            continue
        if sample.get("unit") != "s":
            raise ValueError("Acquisition histogram unit must be seconds")
        histogram = sample.get("histogram", {})
        if histogram.get("aggregationTemporality") != 2:
            raise ValueError("Expected cumulative acquisition histogram")
        for point in histogram.get("dataPoints", []):
            attributes = point.get("attributes", [])
            if len({item["key"] for item in attributes}) != len(attributes):
                raise ValueError("Duplicate acquisition series attributes")
            identity = json.dumps(sorted(attributes, key=lambda item: item["key"]), sort_keys=True)
            pool = [item.get("value", {}).get("stringValue") for item in attributes if item["key"] == "pool"]
            if len(pool) != 1 or not isinstance(pool[0], str) or not pool[0]:
                raise ValueError("Acquisition series must identify one pool")
            identities.add(identity)
            when = int(point["timeUnixNano"]) / 1e9
            points.append((when, point))
    if len(identities) != 1:
        raise ValueError("Expected one acquisition series; multiple pools cannot be interleaved")
    points.sort(key=lambda item: item[0])
    before = [item for item in points if item[0] <= start]
    after = [item for item in points if start < item[0] <= end + 7]
    if not before or not after:
        raise ValueError("No acquisition histogram bracketing the measured window")
    first_time, first = before[-1]
    last_time, last = after[-1]
    if start - first_time > 12 or abs(last_time - end) > 7:
        raise ValueError("Acquisition snapshots are too far from the measured window")
    bounds = last.get("explicitBounds", [])
    if not bounds or bounds != first.get("explicitBounds"):
        raise ValueError("Finite acquisition buckets are absent or changed")
    if not all(finite(x) and x >= 0 for x in bounds) or bounds != sorted(set(bounds)):
        raise ValueError("Invalid acquisition boundaries")
    if first.get("startTimeUnixNano") != last.get("startTimeUnixNano"):
        raise ValueError("Acquisition series reset during measurement")
    count = int(last["count"]) - int(first["count"])
    summed = last["sum"] - first["sum"]
    buckets = [int(b) - int(a) for a, b in zip(first["bucketCounts"], last["bucketCounts"])]
    if len(buckets) != len(bounds) + 1 or len(first["bucketCounts"]) != len(last["bucketCounts"]):
        raise ValueError("Histogram dimensions do not agree")
    if count <= 0 or not finite(summed) or summed < 0 or any(n < 0 for n in buckets) or sum(buckets) != count:
        raise ValueError("Acquisition histogram has invalid or empty deltas")
    p95 = histogram_quantile(bounds, buckets, .95)
    return {"metricName": "hikaricp.connections.acquire.seconds", "unit": "s", "seriesAttributes": json.loads(next(iter(identities))),
            "snapshotStart": first_time, "snapshotEnd": last_time, "count": count,
            "sumSeconds": summed, "meanSeconds": summed / count,
            "estimatedP95Seconds": p95, "finiteP95": finite(p95),
            "explicitBoundsSeconds": bounds, "bucketDelta": buckets,
            "valid": finite(p95),
            "limits": "Histogram interpolation across recorded export snapshots, including boundary slack; all pool callers, not HTTP requests alone."}


def resource_report(samples):
    if len(samples) < 2:
        raise ValueError("Insufficient host samples")
    first, last = samples[0], samples[-1]
    gaps = [b["monotonic"] - a["monotonic"] for a, b in zip(samples, samples[1:])]
    vm_keys = ("pgmajfault", "pswpin", "pswpout", "pgpgin", "pgpgout", "oom_kill")
    waits = collections.Counter()
    blocking = 0
    for sample in samples:
        for row in sample.get("postgres", []):
            waits[(row.get("waitType") or "none") + ":" + (row.get("waitEvent") or "none")] += row["connections"]
            blocking = max(blocking, row["blocked"])
    cpu_delta = [b - a for a, b in zip(first["cpuTicks"], last["cpuTicks"])]
    total = sum(cpu_delta[:8])  # guest/guest_nice are already included in user/nice.
    elapsed = last["monotonic"] - first["monotonic"]
    return {"samples": len(samples), "maxSampleGapSeconds": max(gaps),
            "minimumMemAvailableKiB": min(s["memoryKiB"]["MemAvailable"] for s in samples),
            "maximumAppRssKiB": max(s["app"]["rssKiB"] for s in samples),
            "appMajorFaultDelta": last["app"]["majorFaults"] - first["app"]["majorFaults"],
            "hostBusyFraction": 1 - (cpu_delta[3] + cpu_delta[4]) / total if total else None,
            "hostIoWaitFraction": cpu_delta[4] / total if total else None,
            "appCpuTicksDelta": (last["app"]["userTicks"] + last["app"]["systemTicks"] - first["app"]["userTicks"] - first["app"]["systemTicks"]),
            "sampleDurationSeconds": elapsed,
            "hostVmCounterDeltas": {key: last["vmstat"].get(key, 0) - first["vmstat"].get(key, 0) for key in vm_keys},
            "maxObservedBlockedPgConnections": blocking,
            "postgresWaitConnectionSamples": dict(waits),
            "samplerErrors": sum(bool(s.get("errors")) for s in samples),
            "valid": max(gaps) < 8 and not any(s.get("errors") for s in samples),
            "limits": "One-second nominal sampling; actual gaps retained. PostgreSQL counts are connection-samples, not distinct requests or cumulative wait duration."}


def selected_metrics(directory):
    result = []
    for file in sorted(pathlib.Path(directory).glob("metrics*.jsonl")):
        for line in file.read_text(encoding="utf-8").splitlines():
            for resource in json.loads(line).get("resourceMetrics", []):
                for scope in resource.get("scopeMetrics", []):
                    for metric in scope.get("metrics", []):
                        if metric.get("name", "").startswith(("hikaricp.", "jvm.gc.", "process.cpu.", "system.cpu.", "http.server.request.duration")):
                            result.append(metric)
    return result


def write_json(path, value):
    pathlib.Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def write_gzip_json(path, value):
    destination = pathlib.Path(path)
    with tempfile.NamedTemporaryFile(dir=destination.parent, suffix=".tmp", delete=False) as handle:
        temporary = pathlib.Path(handle.name)
    try:
        with gzip.open(temporary, "wt", encoding="utf-8") as out:
            json.dump(value, out, allow_nan=False)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)
