from copy import deepcopy
from uuid import UUID
import json

import pytest

from auctionwarehouse import ValidationFailure, Warehouse, make_manifest, validate_event, validate_manifest
from auctionwarehouse.validation import canonical
from fixtures import AUCTION, CUT, OWNER, SECOND, event


def test_replay_keeps_one_fact_and_distinct_delivery_attempts(tmp_path):
    original = event()
    with Warehouse(tmp_path / "events.duckdb") as warehouse:
        assert warehouse.ingest(original, source="fixture") == "accepted"
        assert warehouse.ingest(json.dumps(original, indent=4), source="fixture-retry") == "duplicate"
        assert warehouse.db.execute("SELECT count(*) FROM raw.events").fetchone()[0] == 1
        assert warehouse.db.execute("SELECT count(*) FROM raw.load_attempts").fetchone()[0] == 2


def test_same_identity_different_payload_and_same_position_new_identity_are_quarantined():
    original = event()
    with Warehouse(":memory:") as warehouse:
        warehouse.ingest(original, source="fixture")
        changed = deepcopy(original)
        changed["payload"]["title"] = "Changed content"
        assert warehouse.ingest(changed, source="fixture") == "quarantined"
        changed = deepcopy(original)
        changed["eventId"] = str(UUID(int=555))
        assert warehouse.ingest(changed, source="fixture") == "quarantined"
        reasons = {row[0] for row in warehouse.db.execute("SELECT reason FROM raw.quarantine").fetchall()}
        assert reasons == {"IDENTITY_CONFLICT", "POSITION_CONFLICT"}
        retained = json.loads(warehouse.db.execute("SELECT canonical_envelope FROM raw.events").fetchone()[0])
        assert retained == original


@pytest.mark.parametrize("mutation", [
    lambda value: value.update(schemaVersion=2),
    lambda value: value.update(schemaVersion=True),
    lambda value: value.update(eventType=[]),
    lambda value: value.update(eventId="not-a-uuid"),
    lambda value: value.update(occurredAt="2026-01-01T00:01:00"),
    lambda value: value["payload"].update(openingPriceMinor=1.5),
    lambda value: value["payload"].update(version=1.0),
    lambda value: value["payload"].update(status="CLOSED"),
    lambda value: value["payload"].update(accessToken="must-not-retain"),
])
def test_invalid_schema_is_quarantined_without_retaining_raw_secrets(mutation):
    value = event()
    mutation(value)
    with Warehouse(":memory:") as warehouse:
        assert warehouse.ingest(value, source="fixture-invalid") == "quarantined"
        assert warehouse.db.execute("SELECT count(*) FROM raw.events").fetchone()[0] == 0
        assert "must-not-retain" not in str(warehouse.db.execute("SELECT * FROM raw.quarantine").fetchall())


def test_duplicate_json_fields_nonfinite_and_mismatched_owners_are_rejected():
    with pytest.raises(ValidationFailure, match="DUPLICATE_JSON_FIELD"):
        validate_event('{"eventId":"a","eventId":"b"}')
    with pytest.raises(ValidationFailure, match="NONFINITE_JSON"):
        validate_event('{"value":NaN}')
    own_bid = event(3)
    own_bid["payload"]["highestBidderId"] = OWNER
    with pytest.raises(ValidationFailure, match="OWNER_BID"):
        validate_event(own_bid)


def test_atomicity_rolls_back_fact_when_attempt_record_fails(monkeypatch):
    with Warehouse(":memory:") as warehouse:
        def fail(*_args, **_kwargs):
            raise RuntimeError("injected boundary failure")
        monkeypatch.setattr(warehouse, "_record", fail)
        with pytest.raises(RuntimeError, match="injected"):
            warehouse.ingest(event(), source="fixture")
        assert warehouse.db.execute("SELECT count(*) FROM raw.events").fetchone()[0] == 0
        assert warehouse.db.execute("SELECT count(*) FROM raw.load_attempts").fetchone()[0] == 0


def test_cut_digest_membership_and_identity_are_immutable():
    manifest = make_manifest([event()["eventId"]], CUT, "fixture")
    altered = deepcopy(manifest)
    altered["eventIds"] = []
    with pytest.raises(ValidationFailure):
        validate_manifest(altered)
    with pytest.raises(ValidationFailure, match="MANIFEST_MEMBERSHIP"):
        make_manifest([event()["eventId"]] * 2, CUT, "fixture")
    with Warehouse(":memory:") as warehouse:
        assert warehouse.register_cut(manifest) == CUT
        assert warehouse.register_cut(manifest) == CUT
        with pytest.raises(ValidationFailure, match="CUT_IDENTITY_CONFLICT"):
            warehouse.register_cut(make_manifest([], CUT, "fixture"))


def test_cut_reports_missing_not_equal_counts_and_excludes_later_events():
    first, later = event(), event(1, auction=SECOND)
    with Warehouse(":memory:") as warehouse:
        warehouse.register_cut(make_manifest([first["eventId"]], CUT, "fixture-cut"))
        warehouse.ingest(later, source="later-delivery")
        report = warehouse.reconcile(CUT)
        assert report["pending"] == [first["eventId"]]
        assert report["missing"] == []
        assert report["outsideCutLoaded"] == [later["eventId"]]
        final = warehouse.reconcile(CUT, delivery_complete=True)
        assert final["missing"] == [first["eventId"]]
        assert final["observedAtCut"] == 0
        assert warehouse.ingest(later, source="wrong-cut-attribution", cut_id=CUT) == "quarantined"
        assert warehouse.reconcile(CUT)["unexpected"] == [later["eventId"]]


def test_three_fixture_replays_have_one_effect_and_visible_duplicate_attempts():
    item = event()
    with Warehouse(":memory:") as warehouse:
        warehouse.register_cut(make_manifest([item["eventId"]], CUT, "fixture-cut"))
        reports = []
        for run in range(3):
            warehouse.ingest(item, source=f"fixture-run-{run}", cut_id=CUT)
            reports.append(warehouse.reconcile(CUT, delivery_complete=True))
        assert all(not row["missing"] and not row["duplicateEffects"] for row in reports)
        assert reports[-1]["duplicateAttempts"] == [{"eventId": item["eventId"], "attempts": 3}]


def test_rebuild_from_retained_raw_preserves_attempts_and_refuses_overwrite(tmp_path):
    target = tmp_path / "replacement.duckdb"
    with Warehouse(tmp_path / "original.duckdb") as original:
        item = event()
        original.register_cut(make_manifest([item["eventId"]], CUT, "fixture-cut"))
        original.ingest(item, source="fixture", cut_id=CUT)
        original.ingest(item, source="fixture-replay", cut_id=CUT)
        report = original.rebuild(target)
        assert report["events"] == 1
        with pytest.raises(ValueError, match="never overwritten"):
            original.rebuild(target)
        with Warehouse(target) as replacement:
            assert replacement.reconcile(CUT, delivery_complete=True) == original.reconcile(CUT, delivery_complete=True)
            assert replacement.db.execute("SELECT count(*) FROM raw.rebuild_runs").fetchone()[0] == 1
        original.db.execute("UPDATE raw.events SET canonical_envelope=?", [canonical({**item, "eventType": "unknown"})])
        with pytest.raises(ValidationFailure):
            original.rebuild(tmp_path / "corrupt-rebuild.duckdb")


def test_rebuild_rejects_flattened_raw_or_manifest_membership_corruption(tmp_path):
    with Warehouse(tmp_path / "original.duckdb") as warehouse:
        item = event()
        warehouse.register_cut(make_manifest([item["eventId"]], CUT, "fixture"))
        warehouse.ingest(item, source="fixture", cut_id=CUT)
        warehouse.db.execute("UPDATE raw.events SET aggregate_version=99")
        with pytest.raises(ValidationFailure, match="RETAINED_RAW_CORRUPTION"):
            warehouse.rebuild(tmp_path / "bad-event.duckdb")
        warehouse.db.execute("UPDATE raw.events SET aggregate_version=1")
        warehouse.db.execute("DELETE FROM raw.cut_members")
        with pytest.raises(ValidationFailure, match="RETAINED_CUT_CORRUPTION"):
            warehouse.rebuild(tmp_path / "bad-cut.duckdb")
