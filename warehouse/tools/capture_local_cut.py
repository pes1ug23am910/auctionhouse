"""Local PostgreSQL/Redpanda evidence export; never reads or prints a password.

Run from the source repository with the Compose PostgreSQL service running.
The only database writes create an immutable event cut and its membership.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from uuid import UUID, uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from auctionwarehouse.validation import canonical, make_manifest, timestamp, validate_event


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cut", help="Reuse an existing immutable cut instead of creating one")
    parser.add_argument("--psql", action="store_true", help="Use local psql with explicit TLS libpq environment instead of Compose; requires an existing cut")
    args = parser.parse_args()
    cut = str(UUID(args.cut)) if args.cut else str(uuid4())
    root = Path(__file__).resolve().parents[2]
    if args.psql and (not args.cut or os.environ.get('PGSSLMODE') != 'verify-full' or not os.environ.get('PGSSLROOTCERT') or not os.environ.get('PGPASSFILE')):
        raise ValueError("Remote export requires an existing cut, verify-full TLS, CA and private password file")

    def sql(statement: str) -> str:
        command = (["psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1"] if args.psql else
                   ["docker", "compose", "exec", "-T", "postgres", "psql", "-X", "-q", "-A", "-t",
                    "-v", "ON_ERROR_STOP=1", "-U", "auctionhouse", "-d", "auctionhouse"])
        run = subprocess.run(
            command,
            input=statement, cwd=root, text=True, capture_output=True, encoding="utf-8",
            timeout=60, check=False,
        )
        if run.returncode:
            raise RuntimeError("Local PostgreSQL export failed; inspect the local service (credentials are never included)")
        return run.stdout.strip()

    if args.output.exists():
        raise ValueError("export output must be a new directory")
    if not args.cut:
        sql(f"""BEGIN ISOLATION LEVEL REPEATABLE READ;
            INSERT INTO event_cuts(cut_id) VALUES('{cut}');
            INSERT INTO event_cut_members(cut_id,event_id)
                SELECT '{cut}',event_id FROM outbox_events;
            COMMIT;""")
    report = json.loads(sql(f"""
        SELECT jsonb_build_object(
            'cutId','{cut}', 'capturedAt', (SELECT created_at FROM event_cuts WHERE cut_id='{cut}'),
            'expected',(SELECT count(*) FROM event_cut_members WHERE cut_id='{cut}'),
            'missing',COALESCE((SELECT jsonb_agg(c.event_id ORDER BY c.event_id)
                FROM event_cut_members c LEFT JOIN notification_effects n USING(event_id)
                WHERE c.cut_id='{cut}' AND n.event_id IS NULL),'[]'::jsonb),
            'unpublished',COALESCE((SELECT jsonb_agg(c.event_id ORDER BY c.event_id)
                FROM event_cut_members c JOIN outbox_events o USING(event_id)
                WHERE c.cut_id='{cut}' AND (o.published_at IS NULL OR o.broker_partition IS NULL
                OR o.broker_offset IS NULL)),'[]'::jsonb),
            'duplicateAttempts',COALESCE((SELECT jsonb_agg(to_jsonb(d))
                FROM (SELECT d.event_id,count(*) AS attempts FROM notification_deliveries d
                JOIN event_cut_members c USING(event_id) WHERE c.cut_id='{cut}'
                GROUP BY d.event_id HAVING count(*)>1 ORDER BY d.event_id) d),'[]'::jsonb),
            'unexpected',COALESCE((SELECT jsonb_agg(n.event_id ORDER BY n.event_id)
                FROM notification_effects n LEFT JOIN outbox_events o USING(event_id)
                WHERE o.event_id IS NULL),'[]'::jsonb)
        );"""))
    if not report["capturedAt"]:
        raise ValueError("unknown source cut")
    if report["missing"] or report["unpublished"] or report["unexpected"]:
        print(json.dumps({"cutId": cut, "status": "incomplete", "report": report}, indent=2))
        return 2

    source = sql(f"""SELECT jsonb_build_object(
        'eventId',o.event_id,'eventType',o.event_type,'schemaVersion',o.schema_version,
        'aggregateId',o.aggregate_id,'aggregateVersion',o.aggregate_version,
        'occurredAt',o.occurred_at,'payload',o.payload)
        FROM outbox_events o JOIN event_cut_members c USING(event_id)
        WHERE c.cut_id='{cut}' ORDER BY o.event_id;""")
    sink = sql(f"""SELECT n.payload FROM notification_effects n
        JOIN event_cut_members c USING(event_id) WHERE c.cut_id='{cut}' ORDER BY n.event_id;""")
    source_events = [validate_event(line)[0] for line in source.splitlines() if line]
    sink_events = [validate_event(line)[0] for line in sink.splitlines() if line]
    source_ids = [row["eventId"] for row in source_events]
    sink_ids = [row["eventId"] for row in sink_events]
    if source_ids != sink_ids or len(source_ids) != report["expected"]:
        raise ValueError("source and sink immutable identity sets differ")
    # PostgreSQL JSON and Java Instant use different UTC text spellings.
    # Compare instants semantically, while retaining each original envelope.
    for before, after in zip(source_events, sink_events):
        if timestamp(before["occurredAt"]) != timestamp(after["occurredAt"]):
            raise ValueError("source and sink occurrence instants differ")
        if {k: v for k, v in before.items() if k != "occurredAt"} != {k: v for k, v in after.items() if k != "occurredAt"}:
            raise ValueError("source and sink immutable event content differs")
    manifest = make_manifest(source_ids, cut, "postgres-outbox-immutable-cut")
    args.output.mkdir(parents=True, exist_ok=False)
    for name, events in [("source-events.jsonl", source_events), ("sink-events.jsonl", sink_events)]:
        (args.output / name).write_text("".join(canonical(row) + "\n" for row in events), encoding="utf-8")
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (args.output / "source-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"cutId": cut, "sourceEvents": len(source_ids), "sinkEvents": len(sink_ids),
        "manifestSha256": manifest["sha256"], "directory": str(args.output),
        "path": "auction/outbox/Redpanda/notification-sink/export",
        "sourceSinkContentEqual": True}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
