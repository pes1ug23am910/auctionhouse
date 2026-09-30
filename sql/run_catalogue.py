#!/usr/bin/env python3
"""Run the SQL catalogue against an isolated local PostgreSQL benchmark database."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
from pathlib import Path
import platform
import re
import statistics
import subprocess
import time
import traceback

ROOT = Path(__file__).resolve().parents[1]
SQL_ROOT = ROOT / "sql"
DATABASE = "auctionhouse_sql_bench"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def small_oracle() -> dict[str, list[dict]]:
    return {
        "01": [
            {"bid_id": 1, "auction_id": 1, "amount_minor": 110, "increment_minor": 10},
            {"bid_id": 2, "auction_id": 1, "amount_minor": 120, "increment_minor": 10},
            {"bid_id": 3, "auction_id": 1, "amount_minor": 140, "increment_minor": 20},
        ],
        "02": [
            {"category_id": 2, "depth": 0, "auctions": 1, "accepted_bids": 2},
            {"category_id": 4, "depth": 1, "auctions": 2, "accepted_bids": 3},
        ],
        "03": [{"observed_gaps": 4, "p50_seconds": 129595, "p95_seconds": 172800}],
        "04": [{"auction_id": 4, "opening_minor": 400}],
        "05": [
            {"seller_id": 1, "auctions": 2, "accepted_bids": 5, "bid_volume_minor": 830, "active_bidders": 3},
            {"seller_id": 2, "auctions": 2, "accepted_bids": 2, "bid_volume_minor": 660, "active_bidders": 2},
        ],
        "06": [
            {"bid_id": 2, "auction_id": 1, "amount_minor": 120},
            {"bid_id": 3, "auction_id": 1, "amount_minor": 140},
        ],
        "07": [
            {"cohort_day": "2026-01-01", "day_offset": 0, "active_bidders": 3, "cohort_bidders": 3, "retention_fraction": 1},
            {"cohort_day": "2026-01-01", "day_offset": 1, "active_bidders": 1, "cohort_bidders": 3, "retention_fraction": .3333},
            {"cohort_day": "2026-01-01", "day_offset": 2, "active_bidders": 1, "cohort_bidders": 3, "retention_fraction": .3333},
            {"cohort_day": "2026-01-01", "day_offset": 3, "active_bidders": 1, "cohort_bidders": 3, "retention_fraction": .3333},
        ],
    }


def large_oracle(rows: int, seed: int) -> dict[str, list[dict]]:
    """Compute expected business answers without querying PostgreSQL or copying its joins."""
    auction_counts = Counter()
    seller_counts, seller_volume = Counter(), Counter()
    seller_bidders = defaultdict(set)
    category_auctions = Counter(2 + (a * 17 + seed) % 30 for a in range(1, 12001))
    category_bids = Counter()
    windows, page = [], []
    latest_second = {}
    gaps = Counter()
    first_day, bidder_days = {}, defaultdict(set)
    for n in range(1, rows + 1):
        if n <= rows // 2:
            auction = 1 + (n - 1) % 100
            ordinal = 1 + (n - 1) // 100
        else:
            auction = 101 + (n - rows // 2 - 1) % 9900
            ordinal = 1 + (n - rows // 2 - 1) // 9900
        seller = 1 + (auction - 1) % 100
        bidder = 101 + (n * 7919 + seed) % 2000
        amount = 100000 + auction * 100 + ordinal * 10
        auction_counts[auction] += 1
        seller_counts[seller] += 1
        seller_volume[seller] += amount
        seller_bidders[seller].add(bidder)
        category_bids[2 + (auction * 17 + seed) % 30] += 1
        if auction in latest_second:
            gaps[n - latest_second[auction]] += 1
        latest_second[auction] = n
        day = n // 86400
        first_day.setdefault(bidder, day)
        bidder_days[bidder].add(day)
        if auction == 1:
            windows.append({"bid_id": n, "auction_id": 1, "amount_minor": amount, "increment_minor": 10})
            if (n, n) > (15, 4) and len(page) < 50:
                page.append({"bid_id": n, "auction_id": 1, "amount_minor": amount})

    def percentile(fraction: str):
        total = sum(gaps.values())
        if not total:
            return None
        rank = Decimal(fraction) * (total - 1)
        lo = int(rank)
        hi = lo if rank == lo else lo + 1
        def at(index):
            visited = 0
            for gap, count in sorted(gaps.items()):
                visited += count
                if index < visited:
                    return Decimal(gap)
            raise AssertionError("Percentile rank outside data")
        value = at(lo) + (at(hi) - at(lo)) * (rank - lo)
        return float(value.quantize(Decimal(".01"), rounding=ROUND_HALF_UP))

    subtree = []
    for category in range(1, 32):
        parent, depth = category, 0
        while parent > 2:
            parent //= 2
            depth += 1
        if parent == 2:
            subtree.append({
                "category_id": category, "depth": depth,
                "auctions": category_auctions[category], "accepted_bids": category_bids[category],
            })
    cohort_sizes = Counter(first_day.values())
    activity = defaultdict(set)
    for bidder, days in bidder_days.items():
        for day in days:
            activity[first_day[bidder], day - first_day[bidder]].add(bidder)
    cohorts = []
    for (cohort, offset), active in sorted(activity.items()):
        cohorts.append({
            "cohort_day": str(date(2026, 1, 1) + timedelta(days=cohort)),
            "day_offset": offset, "active_bidders": len(active), "cohort_bidders": cohort_sizes[cohort],
            "retention_fraction": float((Decimal(len(active)) / cohort_sizes[cohort]).quantize(Decimal(".0001"), rounding=ROUND_HALF_UP)),
        })
    return {
        "01": windows,
        "02": subtree,
        "03": [{"observed_gaps": sum(gaps.values()), "p50_seconds": percentile(".5"), "p95_seconds": percentile(".95")}],
        "04": [{"auction_id": a, "opening_minor": 100000 + a * 100}
               for a in range(2, 12001, 100) if auction_counts[a] == 0],
        "05": [{"seller_id": s, "auctions": 120, "accepted_bids": seller_counts[s],
                "bid_volume_minor": seller_volume[s], "active_bidders": len(seller_bidders[s])}
               for s in range(1, 101)],
        "06": page,
        "07": cohorts,
    }


class Runner:
    def __init__(self, output: Path):
        self.output = output
        self.command_id = 0
        (output / "raw").mkdir()

    def psql(self, sql: str, database: str = DATABASE) -> str:
        self.command_id += 1
        stem = f"{self.command_id:04d}"
        args = ["docker", "compose", "exec", "-T", "postgres", "psql", "--no-psqlrc",
                "-v", "ON_ERROR_STOP=1", "-qAt", "-U", "auctionhouse", "-d", database]
        text = "SET TIME ZONE 'UTC'; SET statement_timeout='120s';\n" + sql + "\n"
        (self.output / "raw" / f"{stem}.sql").write_text(text, encoding="utf-8")
        started = time.perf_counter()
        result = subprocess.run(args, cwd=ROOT, input=text, text=True, capture_output=True, encoding="utf-8")
        (self.output / "raw" / f"{stem}.stdout.txt").write_text(result.stdout, encoding="utf-8")
        (self.output / "raw" / f"{stem}.stderr.txt").write_text(result.stderr, encoding="utf-8")
        record = {"id": stem, "cwd": str(ROOT), "argv": args, "stdin_sha256": digest(text.encode()),
                  "exit_code": result.returncode, "wall_seconds": time.perf_counter() - started}
        with (self.output / "commands.jsonl").open("a", encoding="utf-8") as f:
            f.write(json.dumps(record) + "\n")
        if result.returncode:
            raise RuntimeError(f"psql command {stem} exited {result.returncode}: {result.stderr}")
        return result.stdout.strip()

    def result(self, query: str) -> list[dict]:
        return json.loads(self.psql("SELECT coalesce(jsonb_agg(to_jsonb(answer)), '[]'::jsonb) FROM (\n"
                                    + query.rstrip().rstrip(";") + "\n) answer;"))

    def table(self, query: str):
        return self.result(query)

    def template(self, name: str, schema: str, rows: int, seed: int) -> str:
        return (SQL_ROOT / name).read_text().replace("__SCHEMA__", schema).replace(
            "__ROWS__", str(rows)).replace("__SEED__", str(seed))

    def save_json(self, relative: str, value):
        path = self.output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def index_names(plan):
    names = set()
    if "Index Name" in plan:
        names.add(plan["Index Name"])
    for child in plan.get("Plans", []):
        names.update(index_names(child))
    return names


def execute(args):
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    schema = args.schema or "run_" + stamp
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,40}", schema):
        raise ValueError("Schema must be a simple lowercase SQL identifier of at most 41 characters")
    if args.bids < 1000 or args.bids > 10_000_000 or args.bids % 2:
        raise ValueError("--bids must be even and between 1000 and 10000000")
    if not 0 <= args.seed <= 1_000_000_000 or not 1 <= args.repetitions <= 10:
        raise ValueError("Seed/repetition value outside allowed range")
    output = Path(args.output).resolve() if args.output else ROOT / "build" / "sql-catalogue" / stamp
    if output.exists() and any(output.iterdir()):
        raise ValueError("Output directory must be empty so prior evidence cannot be overwritten")
    output.mkdir(parents=True, exist_ok=True)
    runner = Runner(output)
    try:
        if not runner.psql(f"SELECT 1 FROM pg_database WHERE datname='{DATABASE}';", "postgres"):
            runner.psql(f"CREATE DATABASE {DATABASE};", "postgres")
        if runner.psql(f"SELECT 1 FROM pg_namespace WHERE nspname IN ('{schema}', '{schema}_oracle');"):
            raise ValueError("Benchmark/oracle schema already exists; choose a new schema")
        meta = {
            "started_utc": datetime.now(timezone.utc).isoformat(),
            "command": vars(args), "cwd": str(ROOT), "database": DATABASE, "schema": schema,
            "platform": platform.platform(), "rows": args.bids, "seed": args.seed,
            "query_repetitions": args.repetitions, "background_activity": args.background_activity,
            "source_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
            "source_dirty": subprocess.check_output(["git", "status", "--short"], cwd=ROOT, text=True),
            "input_hashes": {str(p.relative_to(ROOT)): digest(p.read_bytes())
                            for p in sorted(SQL_ROOT.rglob("*")) if p.is_file()},
            "server": runner.table("SELECT version() AS version, current_setting('server_version_num') AS version_num"),
            "settings": runner.table("SELECT name, setting, unit FROM pg_settings WHERE name IN "
                                     "('shared_buffers','work_mem','jit','max_parallel_workers_per_gather','effective_cache_size') ORDER BY name"),
        }
        runner.save_json("metadata.json", meta)
        queries = sorted((SQL_ROOT / "queries").glob("*.sql"))
        if len(queries) != 7:
            raise AssertionError("Exactly seven catalogue queries required")
        oracle_schema = schema + "_oracle"
        runner.psql("BEGIN;\n" + runner.template("schema.sql", oracle_schema, args.bids, args.seed)
                    + runner.template("oracle_fixture.sql", oracle_schema, args.bids, args.seed) + "\nCOMMIT;")
        oracle_checks = []
        for query in queries:
            qid = query.name[:2]
            observed = runner.result(runner.template("queries/" + query.name, oracle_schema, args.bids, args.seed))
            expected = small_oracle()[qid]
            runner.save_json(f"small-oracle/{qid}.json", {"expected": expected, "observed": observed})
            if observed != expected:
                raise AssertionError(f"Small-fixture oracle mismatch: {query.name}")
            oracle_checks.append(qid)
        print("SMALL_ORACLES_PASS", ",".join(oracle_checks), flush=True)

        start = time.perf_counter()
        runner.psql("BEGIN;\n" + runner.template("schema.sql", schema, args.bids, args.seed)
                    + runner.template("seed.sql", schema, args.bids, args.seed) + "\nCOMMIT;")
        seed_seconds = time.perf_counter() - start
        for table in ("actors", "categories", "auctions", "bids"):
            runner.psql(f"VACUUM (ANALYZE) {schema}.{table};")
        expected_large = large_oracle(args.bids, args.seed)
        runner.save_json("large-oracle/expected.json", expected_large)
        print("SEEDED", args.bids, "bids", round(seed_seconds, 3), "seconds", flush=True)
        comparisons = {}
        storage = {}
        for phase in ("before", "after"):
            if phase == "after":
                start = time.perf_counter()
                runner.psql(runner.template("indexes.sql", schema, args.bids, args.seed))
                index_build_seconds = time.perf_counter() - start
                runner.psql(f"ANALYZE {schema}.bids; ANALYZE {schema}.auctions;")
            storage[phase] = runner.table(
                f"SELECT relname, pg_relation_size(c.oid) AS heap_bytes, pg_indexes_size(c.oid) AS index_bytes, "
                f"pg_total_relation_size(c.oid) AS total_bytes FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                f"WHERE n.nspname='{schema}' AND c.relkind='r' ORDER BY relname")
            for path in queries:
                qid = path.name[:2]
                query = runner.template("queries/" + path.name, schema, args.bids, args.seed)
                observed = runner.result(query)
                runner.save_json(f"{phase}/{qid}-result.json", observed)
                if observed != expected_large[qid]:
                    runner.save_json(f"{phase}/{qid}-mismatch.json", {"expected": expected_large[qid], "observed": observed})
                    raise AssertionError(f"Independent large-fixture oracle mismatch: {path.name} {phase}")
                samples, indexes = [], set()
                for rep in range(1, args.repetitions + 1):
                    plan = json.loads(runner.psql("EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)\n" + query))
                    runner.save_json(f"{phase}/{qid}-plan-{rep}.json", plan)
                    root = plan[0]["Plan"]
                    samples.append({"execution_ms": plan[0]["Execution Time"],
                                    "planning_ms": plan[0]["Planning Time"],
                                    "shared_hit_blocks": root.get("Shared Hit Blocks", 0),
                                    "shared_read_blocks": root.get("Shared Read Blocks", 0),
                                    "temp_written_blocks": root.get("Temp Written Blocks", 0)})
                    indexes.update(index_names(root))
                comparisons.setdefault(qid, {"query": path.name})[phase] = {
                    "oracle_pass": True, "result_rows": len(observed), "result_sha256": digest(json_bytes(observed)),
                    "median_execution_ms": statistics.median(s["execution_ms"] for s in samples),
                    "index_names": sorted(indexes), "samples": samples}
                print("MEASURED", phase, path.name,
                      comparisons[qid][phase]["median_execution_ms"], "ms", flush=True)
        for row in comparisons.values():
            before, after = row["before"]["median_execution_ms"], row["after"]["median_execution_ms"]
            row["delta_percent"] = round((after - before) / before * 100, 2) if before else None
        summary = {"completed_utc": datetime.now(timezone.utc).isoformat(), "database": DATABASE, "schema": schema,
                   "rows": args.bids, "seed": args.seed, "seed_seconds": seed_seconds,
                   "index_build_seconds": index_build_seconds, "repetitions": args.repetitions,
                   "small_oracle_checks_passed": oracle_checks, "comparisons": comparisons, "storage": storage,
                   "status": "passed", "limitations": [
                       "Synthetic benchmark schema; not an end-to-end application delivery claim.",
                       "Primary keys and foreign keys exist before secondary-index comparison.",
                       "One result/oracle execution warms each query before recorded EXPLAIN samples.",
                       "Before then after order; not randomized, cache-cleared, or a universal speedup claim.",
                       "ANALYZE samples and concurrent host work can affect plans and timings.",
                       "Index storage/build time measured; ongoing write amplification not benchmarked.",
                       "Cohorts are descriptive activity observations; future days are right-censored."]}
        runner.save_json("summary.json", summary)
        print("SQL_CATALOGUE_PASS", output, flush=True)
        return 0
    except Exception as exc:
        runner.save_json("failure.json", {"error": str(exc), "traceback": traceback.format_exc()})
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bids", type=int, default=1_000_000)
    parser.add_argument("--seed", type=int, default=20261001)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--schema")
    parser.add_argument("--output")
    parser.add_argument("--background-activity", default="not recorded")
    raise SystemExit(execute(parser.parse_args()))
