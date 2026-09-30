"""Repeat real sink-file ingestion and dbt checks against one immutable cut."""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
import platform
import subprocess
import sys
import time
from datetime import UTC, datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from auctionwarehouse import Warehouse


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runs", type=int, default=3)
    args = parser.parse_args()
    if args.runs < 1:
        parser.error("--runs must be positive")
    args.output.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    database = (args.output / "warehouse.duckdb").resolve()
    dbt = Path(sys.executable).with_name("dbt.exe" if os.name == "nt" else "dbt")
    env = {**os.environ, "AUCTIONHOUSE_WAREHOUSE_PATH": str(database),
           "DBT_SEND_ANONYMOUS_USAGE_STATS": "false", "DBT_USE_COLORS": "false"}
    evidence = {
        "startedAt": datetime.now(UTC).isoformat(), "python": platform.python_version(),
        "packages": {name: importlib.metadata.version(name) for name in ["duckdb", "dbt-core", "dbt-duckdb"]},
        "inputSha256": hashlib.sha256(args.input.read_bytes()).hexdigest(),
        "manifestSha256": manifest["sha256"], "cutId": manifest["cutId"],
        "path": "repeated warehouse ingestion of one fixed cut",
        "declaredInputSource": manifest["source"],
        "runs": [],
    }
    for number in range(1, args.runs + 1):
        with Warehouse(database) as warehouse:
            cut = warehouse.register_cut(manifest)
            started = time.perf_counter()
            outcomes = warehouse.ingest_file(args.input, source=f"notification-sink-replay-{number}", cut_id=cut)
            ingestion_ms = (time.perf_counter() - started) * 1000
            reconciliation = warehouse.reconcile(cut, delivery_complete=True)
        command = [str(dbt), "build", "--project-dir", str(root / "dbt"),
                   "--profiles-dir", str(root / "dbt"), "--target-path", str((args.output / f"dbt-target-{number}").resolve()),
                   "--log-path", str((args.output / f"dbt-logs-{number}").resolve())]
        process = subprocess.run(command, text=True, capture_output=True, env=env, timeout=180)
        (args.output / f"dbt-run-{number}.log").write_text(process.stdout + process.stderr, encoding="utf-8")
        if process.returncode:
            raise RuntimeError(f"dbt run {number} failed; see its retained log")
        with Warehouse(database) as warehouse:
            fact_count = warehouse.db.execute("SELECT count(*) FROM analytics.fct_auction_events").fetchone()[0]
            attempts = warehouse.db.execute("SELECT count(*) FROM raw.load_attempts").fetchone()[0]
            accepted_count, accepted_sum = warehouse.db.execute(
                "SELECT count(*),coalesce(sum(accepted_bid_amount_minor),0) FROM analytics.fct_auction_events WHERE is_accepted_bid"
            ).fetchone()
        failures = any(reconciliation[name] for name in ["missing", "pending", "unexpected", "duplicateEffects", "quarantine"])
        if failures or fact_count != manifest["eventCount"]:
            raise RuntimeError(f"run {number} did not reconcile")
        result = {"run": number, "recordedAt": datetime.now(UTC).isoformat(),
                  "ingestOutcomes": outcomes, "ingestionMilliseconds": ingestion_ms,
                  "factCount": fact_count, "loadAttempts": attempts,
                  "acceptedBidEvents": accepted_count, "sumOfAcceptedOffersMinor": accepted_sum,
                  "reconciliation": reconciliation, "dbtExitCode": process.returncode,
                  "dbtCommand": command}
        evidence["runs"].append(result)
        (args.output / f"run-{number}.json").write_text(json.dumps(result, indent=2, default=str) + "\n", encoding="utf-8")
        print(json.dumps({name: result[name] for name in ["run", "ingestOutcomes", "factCount", "loadAttempts", "dbtExitCode"]}))
    with Warehouse(database) as warehouse:
        evidence["rebuild"] = warehouse.rebuild(args.output / "replacement.duckdb")
    with Warehouse(args.output / "replacement.duckdb") as replacement:
        if replacement.reconcile(manifest["cutId"], delivery_complete=True) != evidence["runs"][-1]["reconciliation"]:
            raise RuntimeError("replacement did not preserve the immutable cut and attempts")
    evidence["finishedAt"] = datetime.now(UTC).isoformat()
    (args.output / "summary.json").write_text(json.dumps(evidence, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps({"complete": True, "runs": args.runs, "summary": str(args.output / "summary.json")}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
