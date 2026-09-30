"""Run a disposable ABBA diagnostic on a dedicated GitHub Linux runner.

Exit 0: execution/instrumentation/reconciliation complete (see target booleans).
Exit 1: an execution, instrumentation, or durable-oracle check failed.
Threshold exit 99 from k6 is retained and does not abort the remaining variants.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
import pathlib
import platform
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

from analyze import (EVENTS, acquisition_report, jfr_report, performance, resource_report,
                     selected_metrics, timestamp, write_gzip_json, write_json)

ROOT = pathlib.Path(__file__).resolve().parents[2]
K6 = "grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34"
AGENT_SHA = "bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba"
COMPOSE = ["docker", "compose", "-f", "compose.yaml", "-f", "compose.broker.yaml",
           "-f", "compose.cache.yaml", "-f", "compose.observability.yaml"]
SERVICES = ["postgres", "redpanda", "memcached", "collector", "tempo", "prometheus"]
PG_SAMPLE = """SELECT coalesce(json_agg(s),'[]'::json) FROM (
 SELECT state,wait_event_type AS "waitType",wait_event AS "waitEvent",count(*)::int AS connections,
 count(*) FILTER(WHERE cardinality(pg_blocking_pids(pid))>0)::int AS blocked,
 coalesce(max(extract(epoch FROM(clock_timestamp()-xact_start))*1000),0)::float8 AS "oldestTransactionMs"
 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()
 GROUP BY state,wait_event_type,wait_event) s"""


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sha(path):
    with pathlib.Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def command(args, *, env=None, timeout=60, check=True):
    result = subprocess.run(args, cwd=ROOT, env=env, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        # Output may contain credentials. Callers persist only sanitized text.
        raise RuntimeError(f"Command {args[0]} failed with exit {result.returncode}")
    return result


def scrub(text, env):
    for name in ("AUCTIONHOUSE_DEMO_PASSWORD", "AUCTIONHOUSE_DEMO_PASSWORD_HASH",
                 "AUCTIONHOUSE_DB_PASSWORD", "GRAFANA_ADMIN_PASSWORD"):
        value = env.get(name)
        if value:
            text = text.replace(value, "[REDACTED]")
    return text


def wait_url(url, app, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if app.poll() is not None:
            raise RuntimeError("Owned application exited before readiness")
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, TimeoutError):
            pass
        time.sleep(1)
    raise RuntimeError("Owned service did not become ready")


def proc_sample(pid):
    fields = pathlib.Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return {"minorFaults": int(fields[7]), "majorFaults": int(fields[9]),
            "userTicks": int(fields[11]), "systemTicks": int(fields[12]),
            "rssKiB": int(fields[21]) * os.sysconf("SC_PAGE_SIZE") // 1024}


def key_numbers(path, allowed=None):
    result = {}
    for line in pathlib.Path(path).read_text().splitlines():
        fields = line.replace(":", "").split()
        if len(fields) >= 2 and (allowed is None or fields[0] in allowed):
            result[fields[0]] = int(fields[1])
    return result


def sample_resources(stop, path, pid, env, container_ids):
    started = time.monotonic()
    iteration = 0
    with path.open("w", encoding="utf-8") as stream:
        while not stop.is_set():
            row = {"at": now(), "monotonic": time.monotonic(), "errors": []}
            try:
                row["app"] = proc_sample(pid)
                row["memoryKiB"] = key_numbers("/proc/meminfo", {"MemTotal", "MemAvailable", "SwapTotal", "SwapFree"})
                row["vmstat"] = key_numbers("/proc/vmstat", {"pgmajfault", "pswpin", "pswpout", "pgpgin", "pgpgout", "oom_kill"})
                row["cpuTicks"] = [int(x) for x in pathlib.Path("/proc/stat").read_text().splitlines()[0].split()[1:]]
                row["pressure"] = {kind: pathlib.Path("/proc/pressure", kind).read_text().strip() for kind in ("cpu", "memory", "io")}
                pg = command([*COMPOSE, "exec", "-T", "postgres", "psql", "-U", "auctionhouse", "-d", "auctionhouse", "-At", "-v", "ON_ERROR_STOP=1", "-c", PG_SAMPLE], env=env, timeout=4)
                row["postgres"] = json.loads(pg.stdout)
                if iteration % 5 == 0:
                    load = command(["docker", "inspect", "--format", "{{.Id}}", env["COMPOSE_PROJECT_NAME"] + "-load"], timeout=3, check=False)
                    targets = container_ids + ([load.stdout.strip()] if load.returncode == 0 else [])
                    stats = command(["docker", "stats", "--no-stream", "--format", "{{json .}}", *targets], timeout=5, check=False)
                    row["containers"] = [json.loads(line) for line in stats.stdout.splitlines() if line]
                    row["loadContainerPresentAtLookup"] = load.returncode == 0
                    if not row["containers"]:
                        raise ValueError("No owned container resource samples")
            except Exception as error:
                row["errors"].append(type(error).__name__ + ": " + str(error)[:240])
            stream.write(json.dumps(row, allow_nan=False) + "\n")
            stream.flush()
            iteration += 1
            stop.wait(max(0, started + iteration - time.monotonic()))


def run_load(output, rate, duration, label, env):
    output.mkdir()
    # k6 runs as its non-root image user and writes only to this disposable directory.
    output.chmod(0o777)
    write_json(output / "environment.json", {"StartedAt": now(), "Label": label,
        "Image": K6, "Rate": rate, "DurationSeconds": duration, "Seed": 42,
        "PreallocatedVUs": 40, "MaxVUs": 80, "ContainerMemoryMiB": 256, "ContainerCPUs": 1,
        "BaseURL": "http://127.0.0.1:8080", "ScriptSHA256": sha(ROOT / "experiments/load/k6-workload.js")})
    args = ["docker", "run", "--rm", "--name", env["COMPOSE_PROJECT_NAME"] + "-load", "--network", "host",
        "--memory=256m", "--cpus=1", "-e", "AUCTIONHOUSE_DEMO_PASSWORD", "-e", "AUTH_MODE=local-demo",
        "-e", "BASE_URL=http://127.0.0.1:8080", "-e", f"RATE={rate}", "-e", f"DURATION_SECONDS={duration}",
        "-e", "PREALLOCATED_VUS=40", "-e", "MAX_VUS=80", "-e", "SEED=42", "-e", f"COMPARISON_LABEL={label}",
        "-e", "SUMMARY_PATH=/evidence/summary.json", "-v", f"{ROOT / 'experiments/load'}:/scripts:ro",
        "-v", f"{output}:/evidence", K6, "run", "--no-usage-report", "--log-format=json", "/scripts/k6-workload.js"]
    result = command(args, env=env, timeout=duration + 180, check=False)
    write_json(output / "exit.json", {"FinishedAt": now(), "ExitCode": result.returncode})
    (output / "raw.log").write_text(scrub(result.stdout + result.stderr, env), encoding="utf-8")
    # Reconcile even when k6 exits 99; a target miss must not erase durable history evidence.
    oracle = command(["node", "experiments/load/reconcile.mjs", str(output)], env=env, timeout=90, check=False)
    (output / "reconcile.log").write_text(scrub(oracle.stdout + oracle.stderr, env), encoding="utf-8")
    summary = json.loads((output / "summary.json").read_text())
    reconcile = json.loads((output / "reconciliation.json").read_text()) if (output / "reconciliation.json").exists() else {"passed": False}
    return {**performance(summary, result.returncode), "reconciliation": reconcile,
            "executionCompleted": result.returncode in (0, 99), "oracleExitCode": oracle.returncode}


def export_jfr(recording, output):
    summary = command(["jfr", "summary", str(recording)]).stdout
    recorded = {}
    for line in summary.splitlines():
        fields = line.split()
        if len(fields) == 3 and fields[1].isdigit() and fields[0].startswith(("jdk.", "com.")):
            recorded[fields[0]] = int(fields[1])
    unexpected = {name: count for name, count in recorded.items() if count and name not in EVENTS and name not in ("jdk.Checkpoint", "jdk.Metadata")}
    if unexpected or not recorded:
        raise RuntimeError("JFR contains unapproved event types or no event inventory")
    exported = command(["jfr", "print", "--json", "--stack-depth", "24", "--events", ",".join(EVENTS), str(recording)], timeout=90).stdout
    if len(exported.encode("utf-8")) > 64 * 1024 * 1024:
        raise RuntimeError("Selected JFR export exceeds the 64 MiB report bound")
    document = json.loads(exported)
    report = jfr_report(document)
    write_json(output / "jfr-summary.json", {"recordedEventCounts": recorded, **report})
    # Never upload the raw recording: even disabled event types have metadata in JFR files.
    write_gzip_json(output / "selected-jfr-events.json.gz", document)
    return report


def variant(index, pool, output, private, jar, agent, base_env):
    label = f"{index:02d}-pool{pool}"
    dest = output / label
    dest.mkdir()
    temp = private / label
    temp.mkdir()
    telemetry = temp / "telemetry"
    telemetry.mkdir(mode=0o777)
    telemetry.chmod(0o777)
    env = dict(base_env, COMPOSE_PROJECT_NAME=f"profile-{os.environ['GITHUB_RUN_ID']}-{index}",
               AUCTIONHOUSE_TELEMETRY_DIR=str(telemetry), AUCTIONHOUSE_DB_POOL_SIZE=str(pool),
               AUCTIONHOUSE_INSTANCE=label, OTEL_SERVICE_NAME="auctionhouse", OTEL_RESOURCE_ATTRIBUTES=f"service.instance.id={label}",
               OTEL_TRACES_SAMPLER="parentbased_traceidratio", OTEL_TRACES_SAMPLER_ARG="0.1")
    result = {"label": label, "pool": pool, "appSHA256": sha(jar), "StartedAt": now(),
              "executionCompleted": False, "instrumentationValid": False, "oraclesPassed": False,
              "performanceTargetMet": False, "allPerformanceGatesMet": False}
    app = None
    stop = threading.Event()
    monitor = None
    logfile = None
    recording = temp / "measurement.jfr"
    try:
        up = command([*COMPOSE, "up", "-d", "--wait", "--wait-timeout", "120", *SERVICES], env=env, timeout=180, check=False)
        (dest / "startup.log").write_text(scrub(up.stdout + up.stderr, env), encoding="utf-8")
        if up.returncode:
            raise RuntimeError("Disposable dependency startup failed")
        ids = command([*COMPOSE, "ps", "-q"], env=env).stdout.split()
        # Selected immutable/runtime fields only; docker inspect includes secret environment values.
        inspect = command(["docker", "inspect", "--format", '{{json .Id}} {{json .Image}} {{json .HostConfig.Memory}} {{json .HostConfig.NanoCpus}}', *ids]).stdout
        (dest / "container-identities.txt").write_text(inspect, encoding="utf-8")
        logfile = (temp / "backend.log").open("w", encoding="utf-8")
        cpus = sorted(os.sched_getaffinity(0))[:2]
        if len(cpus) != 2:
            raise RuntimeError("Two available application CPUs are required")
        args = ["taskset", "-c", ",".join(map(str, cpus)), "java", "-Xms384m", "-Xmx384m", "-XX:ActiveProcessorCount=2",
            "-Duser.timezone=UTC", f"-javaagent:{agent}", f"-Dotel.javaagent.configuration-file={ROOT / 'observability/agent.properties'}",
            "-jar", str(jar), "--spring.profiles.active=local,broker,cache",
            "--management.metrics.distribution.percentiles-histogram.hikaricp.connections.acquire=true"]
        app = subprocess.Popen(args, cwd=ROOT, env=env, stdout=logfile, stderr=subprocess.STDOUT)
        wait_url("http://127.0.0.1:8080/actuator/health/readiness", app)
        wait_url("http://127.0.0.1:9464/metrics", app)
        result["warmup"] = run_load(dest / "warmup", 50, 15, label + "-warmup", env)
        time.sleep(7)  # Establish a pre-measurement cumulative acquisition snapshot.
        jfr = command(["jcmd", str(app.pid), "JFR.start", "name=measurement", f"settings={ROOT / 'experiments/profile/diagnostic.jfc'}",
                       f"filename={recording}", "maxsize=16m", "dumponexit=true"])
        (dest / "jfr-start.txt").write_text(jfr.stdout, encoding="utf-8")
        monitor = threading.Thread(target=sample_resources, args=(stop, dest / "resources.jsonl", app.pid, env, ids), daemon=True)
        monitor.start()
        result["measurement"] = run_load(dest / "measured", 100, 45, label + "-measured", env)
        stop.set()
        monitor.join(timeout=15)
        if monitor.is_alive():
            raise RuntimeError("Resource sampler did not stop")
        command(["jcmd", str(app.pid), "JFR.stop", "name=measurement", f"filename={recording}"])
        result["jfr"] = export_jfr(recording, dest)
        time.sleep(7)
        # Stop before reading rotated files, and let the agent flush the final export.
        app.terminate()
        app.wait(timeout=40)
        command([*COMPOSE, "stop", "collector"], env=env, timeout=40)
        metrics = selected_metrics(telemetry)
        write_gzip_json(dest / "selected-metrics.json.gz", metrics)
        start = timestamp(json.loads((dest / "measured/environment.json").read_text())["StartedAt"])
        end = timestamp(json.loads((dest / "measured/exit.json").read_text())["FinishedAt"])
        samples = [json.loads(line) for line in (dest / "resources.jsonl").read_text().splitlines()]
        for key, analyze in (("acquisition", lambda: acquisition_report(metrics, start, end)),
                             ("resources", lambda: resource_report(samples))):
            try:
                result[key] = analyze()
            except (ValueError, KeyError) as error:
                result[key] = {"valid": False, "error": str(error)}
        result["executionCompleted"] = all(result[phase]["executionCompleted"] for phase in ("warmup", "measurement"))
        result["oraclesPassed"] = all(result[phase]["reconciliation"]["passed"] for phase in ("warmup", "measurement"))
        result["instrumentationValid"] = all(result[key]["valid"] for key in ("jfr", "acquisition", "resources"))
        result["performanceTargetMet"] = result["measurement"]["performanceTargetMet"]
        result["allPerformanceGatesMet"] = result["measurement"]["allPerformanceGatesMet"]
    except Exception as error:
        result["error"] = scrub(type(error).__name__ + ": " + str(error), env)
    finally:
        stop.set()
        if monitor:
            monitor.join(timeout=15)
        if app and app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=40)
            except subprocess.TimeoutExpired:
                app.kill()
                app.wait(timeout=10)
        if logfile:
            logfile.close()
            (dest / "backend.log").write_text(scrub((temp / "backend.log").read_text(), env), encoding="utf-8")
        command(["docker", "rm", "-f", env["COMPOSE_PROJECT_NAME"] + "-load"], check=False)
        try:
            cleanup = command([*COMPOSE, "down", "--volumes", "--remove-orphans"], env=env, timeout=90, check=False)
            result["cleanupExitCode"] = cleanup.returncode
        except Exception as error:
            result["cleanupExitCode"] = -1
            result["cleanupError"] = type(error).__name__
        result["FinishedAt"] = now()
        write_json(dest / "result.json", result)
        # The private root is created by this process and never supplied by a caller.
        shutil.rmtree(temp)
    return result


def report_campaign(runs):
    execution = len(runs) == 4 and all(run["executionCompleted"] and run["instrumentationValid"] and run["oraclesPassed"] and run["cleanupExitCode"] == 0 for run in runs)
    pairs = []
    for left, right in ((0, 1), (3, 2)):
        if len(runs) > max(left, right) and all("measurement" in runs[i] for i in (left, right)):
            a, b = runs[left]["measurement"], runs[right]["measurement"]
            pairs.append({"pool2Run": runs[left]["label"], "pool16Run": runs[right]["label"],
                "pool16MinusPool2P95Ms": b["latencyMilliseconds"]["p95"] - a["latencyMilliseconds"]["p95"],
                "pool16MinusPool2Dropped": b["dropped"] - a["dropped"],
                "pool16MinusPool2ValidOfferedFraction": b["validOfferedFraction"] - a["validOfferedFraction"]})
    return {"executionAndOraclesPassed": execution,
            "allMeasuredPerformanceGatesMet": len(runs) == 4 and all(run["allPerformanceGatesMet"] for run in runs),
            "orderedRuns": runs, "pairedDifferences": pairs,
            "interpretation": "Two runs per pool on one hosted VM are a bounded contention investigation, not production capacity, a stable SLO, or cloud scaling. Correlation of waits/stacks/resources does not establish cause. The same bidder session and hot auction create intentional shared-session/row contention. No application default is changed."}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--agent", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if platform.system() != "Linux" or os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("Use this destructive disposable-stack harness only on a dedicated GitHub Linux runner")
    temp_root = pathlib.Path(os.environ["RUNNER_TEMP"]).resolve()
    output = args.output.resolve()
    if not output.is_relative_to(temp_root) or output == temp_root or output.exists():
        raise SystemExit("Choose a fresh child directory of RUNNER_TEMP")
    if sha(args.agent) != AGENT_SHA:
        raise SystemExit("Agent checksum mismatch")
    env = dict(os.environ)
    if not all(env.get(key) for key in ("AUCTIONHOUSE_DEMO_PASSWORD", "AUCTIONHOUSE_DEMO_PASSWORD_HASH", "AUCTIONHOUSE_DB_PASSWORD")):
        raise SystemExit("Missing disposable fixture credentials")
    env["GRAFANA_ADMIN_PASSWORD"] = secrets.token_urlsafe(32)
    output.mkdir()
    jar = ROOT / "build/libs/auctionhouse.jar"
    source_paths = ["experiments/profile/run.py", "experiments/profile/analyze.py", "experiments/profile/diagnostic.jfc",
                    "experiments/load/k6-workload.js", "experiments/load/reconcile.mjs", "observability/agent.properties",
                    "compose.yaml", "compose.broker.yaml", "compose.cache.yaml", "compose.observability.yaml", "observability/collector.yaml"]
    write_json(output / "campaign-environment.json", {"sourceSHA": os.environ["GITHUB_SHA"], "runId": os.environ["GITHUB_RUN_ID"],
        "runAttempt": os.environ["GITHUB_RUN_ATTEMPT"], "appSHA256": sha(jar), "agentSHA256": sha(args.agent),
        "files": {p: sha(ROOT / p) for p in source_paths}, "platform": platform.platform(), "cpuCount": os.cpu_count(),
        "cpuAffinity": sorted(os.sched_getaffinity(0)), "cpuModel": next((line.split(":", 1)[1].strip() for line in pathlib.Path("/proc/cpuinfo").read_text().splitlines() if line.startswith("model name")), "unknown"),
        "memoryKiB": key_numbers("/proc/meminfo", {"MemTotal", "MemAvailable", "SwapTotal", "SwapFree"}),
        "clockTicksPerSecond": os.sysconf("SC_CLK_TCK"), "javaVersion": command(["java", "-version"]).stderr,
        "appHeapMiB": 384, "appActiveProcessorCount": 2, "appAffinity": sorted(os.sched_getaffinity(0))[:2],
        "poolOrder": [2, 16, 16, 2], "freshDependenciesPerRun": True, "traceSampling": .1,
        "samplerNominalPeriodSeconds": 1, "containerStatsEverySamples": 5,
        "load": {"warmupRate": 50, "warmupSeconds": 15, "rate": 100, "seconds": 45, "seed": 42,
                 "preallocatedVUs": 40, "maxVUs": 80, "k6CPUs": 1, "k6MemoryMiB": 256}})
    command(["docker", "pull", K6], timeout=180)
    runs = []
    with tempfile.TemporaryDirectory(prefix="profile-private-", dir=temp_root) as private:
        for index, pool in enumerate((2, 16, 16, 2), 1):
            print(f"Starting bounded run {index}/4, pool={pool}", flush=True)
            runs.append(variant(index, pool, output, pathlib.Path(private), jar, args.agent.resolve(), env))
            print(json.dumps({key: runs[-1][key] for key in ("label", "executionCompleted", "instrumentationValid", "oraclesPassed", "performanceTargetMet", "allPerformanceGatesMet")}), flush=True)
            write_json(output / "campaign.json", report_campaign(runs))
    report = report_campaign(runs)
    write_json(output / "campaign.json", report)
    # Text/JSON artifacts only plus explicitly selected JSON gzip; no raw JFR/telemetry or secrets.
    artifact_bytes = 0
    for file in output.rglob("*"):
        if file.is_file():
            artifact_bytes += file.stat().st_size
            data = file.read_bytes()
            if file.suffix == ".gz":
                import gzip
                data = gzip.decompress(data)
            if any(env[key].encode() in data for key in ("AUCTIONHOUSE_DEMO_PASSWORD", "AUCTIONHOUSE_DEMO_PASSWORD_HASH", "AUCTIONHOUSE_DB_PASSWORD", "GRAFANA_ADMIN_PASSWORD")):
                raise RuntimeError("Credential found in report; artifact upload must remain disabled")
    if artifact_bytes > 100 * 1024 * 1024:
        raise RuntimeError("Diagnostic artifacts exceed the 100 MiB upload bound")
    write_json(output / "privacy-check.json", {"passed": True, "artifactBytes": artifact_bytes, "rawJfrIncluded": False, "rawTelemetryIncluded": False, "selectedJfrEvents": list(EVENTS)})
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as step_output:
            step_output.write("upload_safe=true\n")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write("## Bounded ABBA profiling\n\nExecution, instrumentation and durable oracles: **" + ("PASS" if report["executionAndOraclesPassed"] else "FAIL") + "**.\n\n")
            summary.write("All measured load/latency gates attained: **" + ("YES" if report["allMeasuredPerformanceGatesMet"] else "NO") + "**. A separate final performance-acceptance step fails the workflow when these gates are missed.\n\n")
            summary.write("| Run | k6 exit | Offered | Completed | Dropped | p95 ms | Target met |\n|---|---:|---:|---:|---:|---:|---|\n")
            for run in runs:
                measured = run.get("measurement", {})
                summary.write(f"| {run['label']} | {measured.get('k6ExitCode', 'missing')} | {measured.get('offered', 'missing')} | {measured.get('completed', 'missing')} | {measured.get('dropped', 'missing')} | {measured.get('latencyMilliseconds', {}).get('p95', 'missing')} | {run['performanceTargetMet']} |\n")
            summary.write("\n" + report["interpretation"] + "\n")
    return 0 if report["executionAndOraclesPassed"] else 1


if __name__ == "__main__":
    sys.exit(main())
