"""Single-writer DuckDB ingestion with immutable raw events and explicit source cuts."""
from __future__ import annotations

import json
import hashlib
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import duckdb

from .validation import ValidationFailure, canonical, digest, parse, timestamp, uuid_string, validate_event, validate_manifest

DDL = """
CREATE SCHEMA IF NOT EXISTS raw;
CREATE TABLE IF NOT EXISTS raw.events(
  event_id UUID PRIMARY KEY,
  event_type VARCHAR NOT NULL,
  schema_version INTEGER NOT NULL,
  auction_id UUID NOT NULL,
  aggregate_version BIGINT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  canonical_envelope VARCHAR NOT NULL,
  envelope_sha256 VARCHAR NOT NULL,
  first_seen_at TIMESTAMPTZ NOT NULL,
  UNIQUE(auction_id,aggregate_version)
);
CREATE TABLE IF NOT EXISTS raw.source_cuts(
  cut_id UUID PRIMARY KEY, manifest_sha256 VARCHAR NOT NULL,
  manifest_json VARCHAR NOT NULL, exported_at TIMESTAMPTZ NOT NULL,
  source VARCHAR NOT NULL, event_count BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS raw.cut_members(
  cut_id UUID NOT NULL,event_id UUID NOT NULL,PRIMARY KEY(cut_id,event_id)
);
CREATE TABLE IF NOT EXISTS raw.load_attempts(
  attempt_id UUID PRIMARY KEY,event_id UUID,cut_id UUID,source VARCHAR NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,outcome VARCHAR NOT NULL,
  input_sha256 VARCHAR NOT NULL,reason VARCHAR
);
CREATE TABLE IF NOT EXISTS raw.quarantine(
  attempt_id UUID PRIMARY KEY,event_id UUID,reason VARCHAR NOT NULL,
  input_sha256 VARCHAR NOT NULL,received_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE IF NOT EXISTS raw.rebuild_runs(
  run_id UUID PRIMARY KEY,reconstructed_at TIMESTAMPTZ NOT NULL,
  event_count BIGINT NOT NULL,event_set_sha256 VARCHAR NOT NULL
);
"""


class Warehouse:
    def __init__(self, path: str | Path):
        self.path = str(path)
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self.db = duckdb.connect(self.path)
        self.db.execute("SET threads=2")
        self.db.execute("SET memory_limit='512MB'")
        self.db.execute(DDL)

    def close(self):
        self.db.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def register_cut(self, manifest: dict) -> str:
        validate_manifest(manifest)
        cut_id = manifest["cutId"]
        self.db.execute("BEGIN")
        try:
            old = self.db.execute("SELECT manifest_sha256 FROM raw.source_cuts WHERE cut_id=?", [cut_id]).fetchone()
            if old:
                if old[0] != manifest["sha256"]:
                    raise ValidationFailure("CUT_IDENTITY_CONFLICT")
            else:
                self.db.execute("INSERT INTO raw.source_cuts VALUES(?,?,?,?,?,?)",
                                [cut_id, manifest["sha256"], canonical(manifest),
                                 timestamp(manifest["exportedAt"]), manifest["source"], manifest["eventCount"]])
                if manifest["eventIds"]:
                    self.db.executemany("INSERT INTO raw.cut_members VALUES(?,?)",
                                        [(cut_id, event_id) for event_id in manifest["eventIds"]])
            self.db.execute("COMMIT")
        except Exception:
            self.db.execute("ROLLBACK")
            raise
        return cut_id

    def _cut_exists(self, cut_id: str):
        uuid_string(cut_id)
        if not self.db.execute("SELECT 1 FROM raw.source_cuts WHERE cut_id=?", [cut_id]).fetchone():
            raise ValidationFailure("UNKNOWN_CUT")

    def _record(self, event_id: str | None, source: str, cut_id: str | None,
                input_hash: str, outcome: str, reason: str | None = None):
        attempt_id = str(uuid4())
        now = datetime.now(UTC)
        self.db.execute("INSERT INTO raw.load_attempts VALUES(?,?,?,?,?,?,?,?)",
                        [attempt_id, event_id, cut_id, source, now, outcome, input_hash, reason])
        if reason:
            self.db.execute("INSERT INTO raw.quarantine VALUES(?,?,?,?,?)",
                            [attempt_id, event_id, reason, input_hash, now])

    def ingest(self, raw: str | dict, *, source: str, cut_id: str | None = None) -> str:
        if not isinstance(source, str) or not 1 <= len(source) <= 200:
            raise ValueError("source must be a non-sensitive label of 1..200 characters")
        if cut_id:
            self._cut_exists(cut_id)
        if isinstance(raw, str):
            input_hash = hashlib.sha256(raw.encode("utf-8", errors="replace")).hexdigest()
        else:
            try:
                input_hash = digest(canonical(raw))
            except (ValueError, TypeError, UnicodeError):
                input_hash = digest("unserializable input")
        event_id = None
        reason = None
        try:
            event, encoded, envelope_hash = validate_event(raw)
            event_id = event["eventId"]
        except ValidationFailure as error:
            reason = error.code
            try:
                candidate = parse(raw) if isinstance(raw, str) else raw
                event_id = uuid_string(candidate.get("eventId")) if isinstance(candidate, dict) else None
            except ValidationFailure:
                pass
        self.db.execute("BEGIN")
        try:
            outcome = "quarantined"
            if not reason and cut_id and not self.db.execute(
                    "SELECT 1 FROM raw.cut_members WHERE cut_id=? AND event_id=?", [cut_id, event_id]).fetchone():
                reason = "UNEXPECTED_FOR_CUT"
            if not reason:
                previous = self.db.execute("SELECT envelope_sha256 FROM raw.events WHERE event_id=?", [event_id]).fetchone()
                if previous:
                    if previous[0] != envelope_hash:
                        reason = "IDENTITY_CONFLICT"
                    else:
                        outcome = "duplicate"
                elif self.db.execute("SELECT 1 FROM raw.events WHERE auction_id=? AND aggregate_version=?",
                                     [event["aggregateId"], event["aggregateVersion"]]).fetchone():
                    reason = "POSITION_CONFLICT"
                else:
                    self.db.execute("INSERT INTO raw.events VALUES(?,?,?,?,?,?,?,?,?)",
                                    [event_id, event["eventType"], event["schemaVersion"], event["aggregateId"],
                                     event["aggregateVersion"], timestamp(event["occurredAt"]), encoded,
                                     envelope_hash, datetime.now(UTC)])
                    outcome = "accepted"
            self._record(event_id, source, cut_id, input_hash, "quarantined" if reason else outcome, reason)
            self.db.execute("COMMIT")
            return "quarantined" if reason else outcome
        except Exception:
            self.db.execute("ROLLBACK")
            raise

    def ingest_file(self, path: str | Path, *, source: str, cut_id: str | None = None) -> dict[str, int]:
        outcomes: Counter[str] = Counter()
        with Path(path).open(encoding="utf-8") as stream:
            for line in stream:
                if line.strip():
                    outcomes[self.ingest(line, source=source, cut_id=cut_id)] += 1
        return dict(outcomes)

    def reconcile(self, cut_id: str, *, delivery_complete: bool = False) -> dict:
        self._cut_exists(cut_id)
        expected = {str(row[0]) for row in self.db.execute("SELECT event_id FROM raw.cut_members WHERE cut_id=?", [cut_id]).fetchall()}
        observed = {str(row[0]) for row in self.db.execute("SELECT event_id FROM raw.events").fetchall()}
        absent = sorted(expected - observed)
        unexpected = sorted({str(row[0]) for row in self.db.execute(
            "SELECT event_id FROM raw.load_attempts WHERE cut_id=? AND event_id IS NOT NULL", [cut_id]).fetchall()} - expected)
        repeated = self.db.execute("""
            SELECT a.event_id,count(*) FROM raw.load_attempts a
            JOIN raw.cut_members c ON c.event_id=a.event_id AND c.cut_id=?
            WHERE a.outcome IN ('accepted','duplicate') GROUP BY a.event_id HAVING count(*)>1 ORDER BY a.event_id
            """, [cut_id]).fetchall()
        duplicate_effects = self.db.execute("""
            SELECT e.event_id,count(*) FROM raw.events e
            JOIN raw.cut_members c ON c.event_id=e.event_id AND c.cut_id=?
            GROUP BY e.event_id HAVING count(*)>1
            """, [cut_id]).fetchall()
        quarantine = self.db.execute("""
            SELECT a.reason,count(*) FROM raw.load_attempts a
            WHERE a.cut_id=? AND a.outcome='quarantined' GROUP BY a.reason ORDER BY a.reason
            """, [cut_id]).fetchall()
        return {"cutId": cut_id, "expected": len(expected), "observedAtCut": len(expected & observed),
                "deliveryCompleteDeclared": delivery_complete,
                "missing": absent if delivery_complete else [], "pending": [] if delivery_complete else absent,
                "unexpected": unexpected, "outsideCutLoaded": sorted(observed - expected),
                "duplicateAttempts": [{"eventId": str(value), "attempts": count} for value, count in repeated],
                "duplicateEffects": [{"eventId": str(value), "effects": count} for value, count in duplicate_effects],
                "quarantine": dict(quarantine)}

    def rebuild(self, target: str | Path) -> dict:
        target = Path(target)
        if target.exists():
            raise ValueError("rebuild target must be a new path; existing databases are never overwritten")
        events = self.db.execute("SELECT * FROM raw.events ORDER BY event_id").fetchall()
        for row in events:
            event, _, hash_value = validate_event(row[6])
            if (hash_value != row[7] or event["eventId"] != str(row[0])
                    or event["eventType"] != row[1] or event["schemaVersion"] != row[2]
                    or event["aggregateId"] != str(row[3]) or event["aggregateVersion"] != row[4]
                    or timestamp(event["occurredAt"]) != row[5]):
                raise ValidationFailure("RETAINED_RAW_CORRUPTION")
        cuts = self.db.execute("SELECT * FROM raw.source_cuts ORDER BY cut_id").fetchall()
        for row in cuts:
            manifest = validate_manifest(json.loads(row[2]))
            members = sorted(str(item[0]) for item in self.db.execute(
                "SELECT event_id FROM raw.cut_members WHERE cut_id=?", [row[0]]).fetchall())
            if (manifest["cutId"] != str(row[0]) or manifest["sha256"] != row[1]
                    or timestamp(manifest["exportedAt"]) != row[3]
                    or manifest["source"] != row[4] or manifest["eventCount"] != row[5]
                    or manifest["eventIds"] != members):
                raise ValidationFailure("RETAINED_CUT_CORRUPTION")
        report = {"runId": str(uuid4()), "events": len(events),
                  "eventSetSha256": digest(canonical([[str(row[0]), row[7]] for row in events]))}
        with Warehouse(target) as replacement:
            replacement.db.execute("BEGIN")
            try:
                for table in ["events", "source_cuts", "cut_members", "load_attempts", "quarantine", "rebuild_runs"]:
                    rows = self.db.execute("SELECT * FROM raw." + table).fetchall()
                    if rows:
                        replacement.db.executemany("INSERT INTO raw." + table + " VALUES(" + ",".join("?" for _ in rows[0]) + ")", rows)
                replacement.db.execute("INSERT INTO raw.rebuild_runs VALUES(?,?,?,?)",
                                       [report["runId"], datetime.now(UTC), report["events"], report["eventSetSha256"]])
                replacement.db.execute("COMMIT")
            except Exception:
                replacement.db.execute("ROLLBACK")
                raise
        return report
