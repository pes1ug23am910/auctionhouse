import json
import os
from pathlib import Path
import subprocess
import sys

import duckdb

from auctionwarehouse import Warehouse
from fixtures import all_events


def test_actual_dbt_build_checks_star_grain_and_business_oracles(tmp_path):
    database = tmp_path / "warehouse.duckdb"
    with Warehouse(database) as warehouse:
        for item in all_events():
            warehouse.ingest(item, source="dbt-fixture")
            warehouse.ingest(item, source="dbt-fixture-retry")
    executable = Path(sys.executable).with_name("dbt.exe" if os.name == "nt" else "dbt")
    project = Path(__file__).resolve().parents[1] / "dbt"
    env = {**os.environ, "AUCTIONHOUSE_WAREHOUSE_PATH": str(database),
           "DBT_SEND_ANONYMOUS_USAGE_STATS": "false", "DBT_USE_COLORS": "false"}
    result = subprocess.run([str(executable), "build", "--project-dir", str(project),
                             "--profiles-dir", str(project), "--target-path", str(tmp_path / "target"),
                             "--log-path", str(tmp_path / "logs")],
                            env=env, text=True, capture_output=True, timeout=120)
    assert result.returncode == 0, result.stdout + result.stderr
    runs = json.loads((tmp_path / "target" / "run_results.json").read_text())
    assert len(runs["results"]) >= 20
    with duckdb.connect(str(database), read_only=True) as db:
        assert db.execute("SELECT count(*) FROM analytics.fct_auction_events").fetchone()[0] == 8
        assert db.execute("SELECT count(*) FROM analytics.dim_actors").fetchone()[0] == 3
        assert db.execute("SELECT accepted_bids,accepted_offer_volume_minor FROM analytics.mart_daily_activity").fetchone() == (2, 250)
        assert db.execute("SELECT final_offer_amount_minor,offer_above_opening_minor FROM analytics.mart_auction_outcomes").fetchone() == (150, 50)
        assert db.execute("SELECT count(*) FROM raw.load_attempts").fetchone()[0] == 16
