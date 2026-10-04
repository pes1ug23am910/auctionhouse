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
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

from design import (EXPERIMENTS, allocation_progress, campaign_design_errors, configuration_check,
                    expected_configuration, experiment_plan)

from analyze import (EVENTS, acquisition_report, jfr_report, performance, resource_report,
                     selected_metrics, timestamp, write_gzip_json, write_json)

ROOT = pathlib.Path(__file__).resolve().parents[2]
K6 = "grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34"
ACQUISITION_SLO = "1us,5us,10us,25us,50us,100us,250us,500us,1ms,2ms,5ms,10ms,25ms,50ms,100ms,250ms,500ms,1s,2s,5s"
AGENT_SHA = "f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82"
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


def container_sample(env, container_ids):
    if not container_ids or len(set(container_ids)) != len(container_ids) or not all(re.fullmatch(r"[0-9a-f]{64}", value) for value in container_ids):
        raise ValueError("Refusing unscoped or invalid Docker resource targets")
    load = command(["docker", "inspect", "--format", "{{if .State.Running}}{{.Id}}{{end}}", env["COMPOSE_PROJECT_NAME"] + "-load"], timeout=3, check=False)
    load_id = load.stdout.strip() if load.returncode == 0 else ""
    targets = container_ids + ([load_id] if load_id else [])
    stats = command(["docker", "stats", "--no-stream", "--format", "{{json .}}", *targets], timeout=5, check=False)
    observations = [json.loads(line) for line in stats.stdout.splitlines() if line]
    observed = [item.get("Container") for item in observations]
    if len(set(observed)) != len(observed) or not set(container_ids).issubset(observed) or not set(observed).issubset(targets):
        raise ValueError("Missing dependency or unowned container resource samples")
    return {"containers": observations, "loadContainerRunningAtLookup": bool(load_id)}


def validate_owned_dependencies(records, project):
    if len(records) != len(SERVICES) or {item.get("service") for item in records} != set(SERVICES):
        raise ValueError("Expected exactly the declared dependency services")
    ids = [item.get("id", "") for item in records]
    if len(set(ids)) != len(ids) or not all(re.fullmatch(r"[0-9a-f]{64}", value) for value in ids):
        raise ValueError("Invalid or duplicate dependency identities")
    if not all(item.get("project") == project and item.get("running") is True for item in records):
        raise ValueError("Dependency ownership or running-state mismatch")
    return ids


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
                    row.update(container_sample(env, container_ids))
            except Exception as error:
                row["errors"].append(type(error).__name__ + ": " + str(error)[:240])
            stream.write(json.dumps(row, allow_nan=False) + "\n")
            stream.flush()
            iteration += 1
            stop.wait(max(0, started + iteration - time.monotonic()))


def run_load(output, rate, duration, label, env, preallocated=40):
    expected = expected_configuration(rate, duration, label, preallocated)
    output.mkdir()
    # k6 runs as its non-root image user and writes only to this disposable directory.
    output.chmod(0o777)
    write_json(output / "environment.json", {"StartedAt": now(), "Label": label,
        "Image": K6, "Rate": rate, "DurationSeconds": duration, "Seed": 42,
        "PreallocatedVUs": preallocated, "MaxVUs": 80, "ContainerMemoryMiB": 256, "ContainerCPUs": 1,
        "BaseURL": "http://127.0.0.1:8080", "ScriptSHA256": sha(ROOT / "experiments/load/k6-workload.js")})
    # Keep the stopped container until the resource sampler has joined; --rm races docker stats.
    args = ["docker", "run", "--name", env["COMPOSE_PROJECT_NAME"] + "-load", "--network", "host",
        "--memory=256m", "--cpus=1", "-e", "AUCTIONHOUSE_DEMO_PASSWORD", "-e", "AUTH_MODE=local-demo",
        "-e", "BASE_URL=http://127.0.0.1:8080", "-e", f"RATE={rate}", "-e", f"DURATION_SECONDS={duration}",
        "-e", f"PREALLOCATED_VUS={preallocated}", "-e", "MAX_VUS=80", "-e", "SEED=42", "-e", f"COMPARISON_LABEL={label}",
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
    checked = configuration_check(summary, expected)
    progress = allocation_progress(result.stdout + result.stderr, preallocated, 80, summary.get("dropped"))
    write_json(output / "client-progress.json", progress)
    return {**performance(summary, result.returncode), "reconciliation": reconcile,
            "configuration": checked, "allocationProgress": {key: value for key, value in progress.items() if key != "samples"},
            "executionCompleted": result.returncode in (0, 99) and checked["verified"], "oracleExitCode": oracle.returncode}


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
    projected = recording.with_suffix(".selected.jsonl")
    command(["java", "-Xmx128m", str(ROOT / "experiments/profile/ProfileEvents.java"), str(recording), str(projected)], timeout=90)
    document = {"recording": {"events": [json.loads(line) for line in projected.read_text(encoding="utf-8").splitlines()]}}
    report = jfr_report(document)
    write_json(output / "jfr-summary.json", {"recordedEventCounts": recorded, **report})
    # Never upload the raw recording: even disabled event types have metadata in JFR files.
    write_gzip_json(output / "selected-jfr-events.json.gz", document)
    return report


def update_load_flags(result):
    phases = [result.get(name) for name in ("warmup", "measurement")]
    result["executionCompleted"] = all(phase and phase["executionCompleted"] for phase in phases)
    result["oraclesPassed"] = all(phase and phase["reconciliation"]["passed"] and phase["oracleExitCode"] == 0 for phase in phases)
    measured = result.get("measurement", {})
    result["performanceTargetMet"] = measured.get("performanceTargetMet", False)
    result["allPerformanceGatesMet"] = measured.get("allPerformanceGatesMet", False)


def analyze_independently(tasks, result, env):
    for name, analyze in tasks:
        try:
            result[name] = analyze()
        except Exception as error:
            result[name] = {"valid": False, "error": scrub(type(error).__name__ + ": " + str(error), env)}


def variant(index, pool, output, private, jar, agent, base_env, preallocated=40, experiment="pool-abba"):
    spec = experiment_plan(experiment)["variants"][index - 1]
    if pool != spec["pool"] or preallocated != spec["preallocatedVUs"]:
        raise ValueError("Variant does not match fixed experiment plan")
    label = spec["label"]
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
    result = {**spec, "experiment": experiment, "appSHA256": sha(jar), "StartedAt": now(),
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
        if not ids:
            raise RuntimeError("Owned Compose dependency inventory is empty")
        # Selected immutable/runtime fields only; docker inspect includes secret environment values.
        fields = '{"id":{{json .Id}},"image":{{json .Image}},"memoryBytes":{{json .HostConfig.Memory}},"nanoCpus":{{json .HostConfig.NanoCpus}},"project":{{json (index .Config.Labels "com.docker.compose.project")}},"service":{{json (index .Config.Labels "com.docker.compose.service")}},"running":{{json .State.Running}}}'
        inspected = command(["docker", "inspect", "--format", fields, *ids]).stdout
        inventory = [json.loads(line) for line in inspected.splitlines() if line]
        ids = validate_owned_dependencies(inventory, env["COMPOSE_PROJECT_NAME"])
        write_json(dest / "container-identities.json", inventory)
        logfile = (temp / "backend.log").open("w", encoding="utf-8")
        cpus = sorted(os.sched_getaffinity(0))[:2]
        if len(cpus) != 2:
            raise RuntimeError("Two available application CPUs are required")
        args = ["taskset", "-c", ",".join(map(str, cpus)), "java", "-Xms384m", "-Xmx384m", "-XX:ActiveProcessorCount=2",
            "-Duser.timezone=UTC", f"-javaagent:{agent}", f"-Dotel.javaagent.configuration-file={ROOT / 'observability/agent.properties'}",
            "-jar", str(jar), "--spring.profiles.active=local,broker,cache",
            f"--management.metrics.distribution.slo.hikaricp.connections.acquire={ACQUISITION_SLO}"]
        app = subprocess.Popen(args, cwd=ROOT, env=env, stdout=logfile, stderr=subprocess.STDOUT)
        wait_url("http://127.0.0.1:8080/actuator/health/readiness", app)
        wait_url("http://127.0.0.1:9464/metrics", app)
        result["warmup"] = run_load(dest / "warmup", 50, 15, label + "-warmup", env)
        command(["docker", "rm", env["COMPOSE_PROJECT_NAME"] + "-load"])
        time.sleep(7)  # Establish a pre-measurement cumulative acquisition snapshot.
        jfr = command(["jcmd", str(app.pid), "JFR.start", "name=measurement", f"settings={ROOT / 'experiments/profile/diagnostic.jfc'}",
                       f"filename={recording}", "maxsize=16m", "dumponexit=true"])
        (dest / "jfr-start.txt").write_text(jfr.stdout, encoding="utf-8")
        monitor = threading.Thread(target=sample_resources, args=(stop, dest / "resources.jsonl", app.pid, env, ids), daemon=True)
        monitor.start()
        result["measurement"] = run_load(dest / "measured", 100, 45, label + "-measured", env, preallocated)
        update_load_flags(result)
        stop.set()
        monitor.join(timeout=15)
        if monitor.is_alive():
            raise RuntimeError("Resource sampler did not stop")
        command(["docker", "rm", env["COMPOSE_PROJECT_NAME"] + "-load"])
        def jfr_analysis():
            command(["jcmd", str(app.pid), "JFR.stop", "name=measurement", f"filename={recording}"])
            return export_jfr(recording, dest)
        analyze_independently([("jfr", jfr_analysis)], result, env)
        time.sleep(7)
        # Stop before reading rotated files, and let the agent flush the final export.
        app.terminate()
        app.wait(timeout=40)
        command([*COMPOSE, "stop", "collector"], env=env, timeout=40)
        start = timestamp(json.loads((dest / "measured/environment.json").read_text())["StartedAt"])
        end = timestamp(json.loads((dest / "measured/exit.json").read_text())["FinishedAt"])
        def acquisition_analysis():
            metrics = selected_metrics(telemetry)
            write_gzip_json(dest / "selected-metrics.json.gz", metrics)
            return acquisition_report(metrics, start, end)
        def resources_analysis():
            return resource_report([json.loads(line) for line in (dest / "resources.jsonl").read_text().splitlines()])
        analyze_independently([("acquisition", acquisition_analysis), ("resources", resources_analysis)], result, env)
        result["instrumentationValid"] = all(result[key]["valid"] for key in ("jfr", "acquisition", "resources"))
    except Exception as error:
        result["error"] = scrub(type(error).__name__ + ": " + str(error), env)
    finally:
        cleanup_errors = []
        stop.set()
        if monitor:
            monitor.join(timeout=15)
            if monitor.is_alive():
                cleanup_errors.append("sampler did not stop")
        if app and app.poll() is None:
            try:
                app.terminate()
                try:
                    app.wait(timeout=40)
                except subprocess.TimeoutExpired:
                    app.kill()
                    app.wait(timeout=10)
            except Exception as error:
                cleanup_errors.append("application stop: " + type(error).__name__)
        if logfile:
            try:
                logfile.close()
                (dest / "backend.log").write_text(scrub((temp / "backend.log").read_text(), env), encoding="utf-8")
            except Exception as error:
                cleanup_errors.append("backend log: " + type(error).__name__)
        try:
            command(["docker", "rm", "-f", env["COMPOSE_PROJECT_NAME"] + "-load"], check=False)
        except Exception as error:
            cleanup_errors.append("load container cleanup: " + type(error).__name__)
        try:
            cleanup = command([*COMPOSE, "down", "--volumes", "--remove-orphans"], env=env, timeout=90, check=False)
            result["cleanupExitCode"] = cleanup.returncode
        except Exception as error:
            result["cleanupExitCode"] = -1
            result["cleanupError"] = type(error).__name__
        result["FinishedAt"] = now()
        result["cleanupErrors"] = cleanup_errors
        write_json(dest / "result.json", result)
        # The private root is created by this process and never supplied by a caller.
        try:
            shutil.rmtree(temp)
        except Exception as error:
            result["cleanupErrors"].append("private file cleanup: " + type(error).__name__)
            write_json(dest / "result.json", result)
    return result


def report_campaign(runs, experiment="pool-abba"):
    plan = experiment_plan(experiment)
    design_errors = campaign_design_errors(runs, plan)
    execution = len(runs) == 4 and not design_errors and all(run["executionCompleted"] and run["instrumentationValid"] and run["oraclesPassed"] and run["cleanupExitCode"] == 0 and not run.get("cleanupErrors") for run in runs)
    pairs = []
    for left, right in ((0, 1), (3, 2)):
        if not design_errors and len(runs) > max(left, right) and all("measurement" in runs[i] for i in (left, right)):
            a, b = runs[left]["measurement"], runs[right]["measurement"]
            pairs.append({"axis": plan["axis"], "controlValue": plan["control"], "interventionValue": plan["intervention"],
                "controlRun": runs[left]["label"], "interventionRun": runs[right]["label"],
                "interventionMinusControlP95Ms": b["latencyMilliseconds"]["p95"] - a["latencyMilliseconds"]["p95"],
                "interventionMinusControlDropped": b["dropped"] - a["dropped"],
                "interventionMinusControlValidOfferedFraction": b["validOfferedFraction"] - a["validOfferedFraction"]})
    attainment = len(runs) == 4 and all(run["allPerformanceGatesMet"] for run in runs)
    return {"formatVersion": 2, "experiment": plan,
            "experimentDesignValid": len(runs) == 4 and not design_errors, "experimentDesignErrors": design_errors,
            "executionAndOraclesPassed": execution,
            "allMeasuredPerformanceGatesMet": attainment, "validAcceptance": execution and attainment,
            "orderedRuns": runs, "pairedDifferences": pairs,
            "interpretation": "Two runs per level on one hosted VM are a bounded " + plan["axis"] + " investigation, not production capacity, a stable SLO, or cloud scaling. Only within-campaign comparisons are controlled. Correlation of waits/stacks/resources does not establish cause. The same bidder session and hot auction create intentional shared-session/row contention. Preallocating more VUs changes burst absorption, not the offered rate or maximum concurrency. No application or default workload setting is changed."}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--agent", type=pathlib.Path, required=True)
    parser.add_argument("--experiment", choices=EXPERIMENTS, default="pool-abba")
    args = parser.parse_args()
    plan = experiment_plan(args.experiment)
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
    source_paths = ["experiments/profile/run.py", "experiments/profile/analyze.py", "experiments/profile/design.py", "experiments/profile/diagnostic.jfc", "experiments/profile/ProfileEvents.java",
                    "experiments/load/k6-workload.js", "experiments/load/reconcile.mjs", "observability/agent.properties",
                    "compose.yaml", "compose.broker.yaml", "compose.cache.yaml", "compose.observability.yaml", "observability/collector.yaml"]
    write_json(output / "campaign-environment.json", {"sourceSHA": os.environ["GITHUB_SHA"], "runId": os.environ["GITHUB_RUN_ID"],
        "runAttempt": os.environ["GITHUB_RUN_ATTEMPT"], "appSHA256": sha(jar), "agentSHA256": sha(args.agent),
        "files": {p: sha(ROOT / p) for p in source_paths}, "platform": platform.platform(), "cpuCount": os.cpu_count(),
        "cpuAffinity": sorted(os.sched_getaffinity(0)), "cpuModel": next((line.split(":", 1)[1].strip() for line in pathlib.Path("/proc/cpuinfo").read_text().splitlines() if line.startswith("model name")), "unknown"),
        "memoryKiB": key_numbers("/proc/meminfo", {"MemTotal", "MemAvailable", "SwapTotal", "SwapFree"}),
        "clockTicksPerSecond": os.sysconf("SC_CLK_TCK"), "javaVersion": command(["java", "-version"]).stderr,
        "appHeapMiB": 384, "appActiveProcessorCount": 2, "appAffinity": sorted(os.sched_getaffinity(0))[:2],
        "experiment": plan, "poolOrder": [spec["pool"] for spec in plan["variants"]], "freshDependenciesPerRun": True, "traceSampling": .1,
        "samplerNominalPeriodSeconds": 1, "containerStatsEverySamples": 5,
        "acquisitionHistogramSloDurations": ACQUISITION_SLO.split(","),
        "load": {"warmupRate": 50, "warmupSeconds": 15, "rate": 100, "seconds": 45, "seed": 42,
                 "warmupPreallocatedVUs": 40, "measuredPreallocatedVUsOrder": [spec["preallocatedVUs"] for spec in plan["variants"]], "maxVUs": 80, "k6CPUs": 1, "k6MemoryMiB": 256}})
    command(["docker", "pull", K6], timeout=180)
    runs = []
    with tempfile.TemporaryDirectory(prefix="profile-private-", dir=temp_root) as private:
        for index, spec in enumerate(plan["variants"], 1):
            print(f"Starting bounded run {index}/4: {spec}", flush=True)
            runs.append(variant(index, spec["pool"], output, pathlib.Path(private), jar, args.agent.resolve(), env, spec["preallocatedVUs"], args.experiment))
            print(json.dumps({key: runs[-1][key] for key in ("label", "executionCompleted", "instrumentationValid", "oraclesPassed", "performanceTargetMet", "allPerformanceGatesMet")}), flush=True)
            write_json(output / "campaign.json", report_campaign(runs, args.experiment))
    report = report_campaign(runs, args.experiment)
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
            summary.write("## Bounded " + args.experiment + " profiling\n\nExecution, instrumentation and durable oracles: **" + ("PASS" if report["executionAndOraclesPassed"] else "FAIL") + "**.\n\n")
            summary.write("All measured load/latency gates attained: **" + ("YES" if report["allMeasuredPerformanceGatesMet"] else "NO") + "**. A separate final performance-acceptance step fails the workflow when these gates are missed.\n\n")
            summary.write("| Run | k6 exit | Offered | Completed | Dropped | p95 ms | Target met |\n|---|---:|---:|---:|---:|---:|---|\n")
            for run in runs:
                measured = run.get("measurement", {})
                summary.write(f"| {run['label']} | {measured.get('k6ExitCode', 'missing')} | {measured.get('offered', 'missing')} | {measured.get('completed', 'missing')} | {measured.get('dropped', 'missing')} | {measured.get('latencyMilliseconds', {}).get('p95', 'missing')} | {run['performanceTargetMet']} |\n")
            summary.write("\n" + report["interpretation"] + "\n")
    return 0 if report["executionAndOraclesPassed"] else 1


if __name__ == "__main__":
    sys.exit(main())
