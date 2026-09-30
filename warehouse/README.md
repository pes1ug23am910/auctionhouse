# Auction event warehouse

A DuckDB loader with immutable event identities, delivery-attempt accounting, strict schema quarantine, fixed-cut reconciliation, replacement rebuilds and dbt star models. See [the contract and measured scope](../docs/WAREHOUSE.md).

Use Python 3.13 and an isolated environment. The lock was installed and exercised on Windows; resolved versions are fixed in `requirements.lock`.

```powershell
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.lock
.\.venv\Scripts\python.exe -m pip install --no-deps -e .
.\.venv\Scripts\python.exe -m pytest -q
```

On Unix, use `python3.13 -m venv .venv` and `.venv/bin/python`. Do not check an environment, database or source export into Git.

Capture the immutable cut through the admin API first. Supply an authorized cookie through the environment without printing it, then export both source and notification effects:

```powershell
auction-warehouse export --cut <cut-uuid> --output data/cut
auction-warehouse ingest --database data/events.duckdb --input data/cut/sink-events.jsonl --manifest data/cut/manifest.json --source notification-sink
auction-warehouse reconcile --database data/events.duckdb --cut <cut-uuid> --delivery-complete
$env:AUCTIONHOUSE_WAREHOUSE_PATH = (Resolve-Path data/events.duckdb).Path
$env:DBT_SEND_ANONYMOUS_USAGE_STATS = 'false'
dbt build --project-dir dbt --profiles-dir dbt
```

The exporter uses `AUCTIONHOUSE_ADMIN_COOKIE` and defaults to `http://127.0.0.1:8080`. The manifest's `exportedAt` is the export time, not a claimed source snapshot timestamp. Reuse the original manifest rather than regenerating it for an existing cut.

For the local Compose stack, run from the source repository root:

```powershell
warehouse/.venv/Scripts/python.exe warehouse/tools/capture_local_cut.py --output warehouse/data/cut
warehouse/.venv/Scripts/python.exe warehouse/tools/run_cut.py --manifest warehouse/data/cut/manifest.json --input warehouse/data/cut/sink-events.jsonl --output warehouse/data/runs --runs 3
```

The capture tool writes only `event_cuts` and `event_cut_members`, atomically, and reads the source/sink thereafter. An incomplete sink produces exit 2 and prints its cut ID; reuse that ID with `--cut` after delivery completes. The run tool requires a new output directory, writes raw logs and JSON reports, builds dbt each time and verifies a new replacement database. Each run replays the same sink export.

For controlled reconstruction, stop the single ingestion writer and use a fresh target:

```powershell
auction-warehouse rebuild --database data/events.duckdb --target data/replacement.duckdb
```

Then point dbt at the replacement, run `dbt build`, reconcile the original cut and switch the application configuration only after reviewing the results. Rebuild never overwrites or automatically replaces the source.

`ingest` returns exit 1 if any input was quarantined. `reconcile` returns 0 for complete success, 2 for pending delivery, and 1 for missing/unexpected/duplicate-effect/quarantine failures. Duplicate delivery attempts are informational and remain queryable.

Primary package records: [DuckDB](https://pypi.org/project/duckdb/1.5.6/), [dbt-duckdb](https://pypi.org/project/dbt-duckdb/1.11.0/), [dbt-core](https://pypi.org/project/dbt-core/1.12.5/), [pytest](https://pypi.org/project/pytest/9.1.1/). The actual fixture and fixed-cut runs establish compatibility for this workload; version numbers alone do not.
