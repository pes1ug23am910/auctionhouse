"""Strict schema-v1 validation. Rejected payloads are never copied to quarantine."""
from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime
from uuid import UUID

MAX_AMOUNT = 9_000_000_000_000_000
TYPES = {"auction.created", "auction.published", "bid.accepted", "auction.closed", "auction.cancelled"}
ENVELOPE_FIELDS = {"eventId", "eventType", "schemaVersion", "aggregateId", "aggregateVersion", "occurredAt", "payload"}
PAYLOAD_FIELDS = {"id", "ownerId", "title", "description", "openingPriceMinor", "minimumIncrementMinor",
                  "endsAt", "status", "highestBidAmountMinor", "highestBidderId", "version", "createdAt", "winnerId"}
MANIFEST_FIELDS = {"formatVersion", "cutId", "source", "exportedAt", "eventIds", "eventCount", "sha256"}


class ValidationFailure(ValueError):
    """A stable non-sensitive rejection code."""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def canonical(value: object) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def uuid_string(value: object) -> str:
    if not isinstance(value, str):
        raise ValidationFailure("INVALID_UUID")
    try:
        parsed = str(UUID(value))
    except (ValueError, AttributeError):
        raise ValidationFailure("INVALID_UUID") from None
    if parsed != value:
        raise ValidationFailure("NONCANONICAL_UUID")
    return parsed


def timestamp(value: object) -> datetime:
    if not isinstance(value, str):
        raise ValidationFailure("INVALID_TIME")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None or parsed.utcoffset() is None:
            raise ValueError()
        return parsed.astimezone(UTC)
    except ValueError:
        raise ValidationFailure("INVALID_TIME") from None


def integer(value: object, upper: int = 2**63 - 1) -> int:
    if type(value) is not int or not 1 <= value <= upper:
        raise ValidationFailure("INVALID_INTEGER")
    return value


def _pairs(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValidationFailure("DUPLICATE_JSON_FIELD")
        result[key] = value
    return result


def parse(raw: str) -> object:
    def invalid_constant(_: str):
        raise ValidationFailure("NONFINITE_JSON")
    try:
        return json.loads(raw, object_pairs_hook=_pairs, parse_constant=invalid_constant)
    except (json.JSONDecodeError, UnicodeError):
        raise ValidationFailure("MALFORMED_JSON") from None


def validate_event(raw: str | dict) -> tuple[dict, str, str]:
    if isinstance(raw, dict):
        try:
            raw = canonical(raw)
        except (ValueError, TypeError):
            raise ValidationFailure("MALFORMED_JSON") from None
    if not isinstance(raw, str):
        raise ValidationFailure("ENVELOPE_SIZE")
    try:
        size = len(raw.encode("utf-8"))
    except UnicodeError:
        raise ValidationFailure("INVALID_UNICODE") from None
    if size > 1_000_000:
        raise ValidationFailure("ENVELOPE_SIZE")
    event = parse(raw)
    if not isinstance(event, dict) or set(event) != ENVELOPE_FIELDS:
        raise ValidationFailure("ENVELOPE_FIELDS")
    if type(event["schemaVersion"]) is not int or event["schemaVersion"] != 1:
        raise ValidationFailure("UNSUPPORTED_SCHEMA")
    if not isinstance(event["eventType"], str) or event["eventType"] not in TYPES:
        raise ValidationFailure("UNSUPPORTED_TYPE")
    uuid_string(event["eventId"])
    uuid_string(event["aggregateId"])
    integer(event["aggregateVersion"])
    occurred_at = timestamp(event["occurredAt"])
    payload = event["payload"]
    if not isinstance(payload, dict) or set(payload) != PAYLOAD_FIELDS:
        raise ValidationFailure("PAYLOAD_FIELDS")
    for name in ["id", "ownerId"]:
        uuid_string(payload[name])
    if payload["id"] != event["aggregateId"] or payload["version"] != event["aggregateVersion"]:
        raise ValidationFailure("AGGREGATE_MISMATCH")
    integer(payload["version"])
    if not isinstance(payload["title"], str) or not 1 <= len(payload["title"].strip()) <= 160:
        raise ValidationFailure("INVALID_TITLE")
    if not isinstance(payload["description"], str) or len(payload["description"]) > 4000:
        raise ValidationFailure("INVALID_DESCRIPTION")
    opening = integer(payload["openingPriceMinor"], MAX_AMOUNT)
    integer(payload["minimumIncrementMinor"], MAX_AMOUNT)
    created_at = timestamp(payload["createdAt"])
    ends_at = timestamp(payload["endsAt"])
    if ends_at <= created_at or occurred_at < created_at:
        raise ValidationFailure("INVALID_CHRONOLOGY")
    amount = payload["highestBidAmountMinor"]
    bidder = payload["highestBidderId"]
    winner = payload["winnerId"]
    if (amount is None) != (bidder is None):
        raise ValidationFailure("INVALID_BID_STATE")
    if amount is not None:
        if integer(amount, MAX_AMOUNT) < opening:
            raise ValidationFailure("INVALID_BID_STATE")
        uuid_string(bidder)
        if bidder == payload["ownerId"]:
            raise ValidationFailure("OWNER_BID")
    if winner is not None:
        uuid_string(winner)
    expected_status = {"auction.created": "DRAFT", "auction.published": "OPEN",
                       "bid.accepted": "OPEN", "auction.closed": "CLOSED", "auction.cancelled": "CANCELLED"}
    if payload["status"] != expected_status[event["eventType"]]:
        raise ValidationFailure("INVALID_STATE")
    if payload["status"] == "CLOSED":
        if winner != bidder:
            raise ValidationFailure("INVALID_WINNER")
    elif winner is not None:
        raise ValidationFailure("INVALID_WINNER")
    if event["eventType"] == "bid.accepted" and amount is None:
        raise ValidationFailure("INVALID_BID_STATE")
    if event["eventType"] in {"auction.created", "auction.published", "auction.cancelled"} and amount is not None:
        raise ValidationFailure("INVALID_BID_STATE")
    encoded = canonical(event)
    try:
        event_hash = digest(encoded)
    except UnicodeError:
        raise ValidationFailure("INVALID_UNICODE") from None
    return event, encoded, event_hash


def make_manifest(event_ids: list[str], cut_id: str, source: str,
                  exported_at: str | None = None) -> dict:
    core = {"formatVersion": 1, "cutId": cut_id, "source": source,
            "exportedAt": exported_at or datetime.now(UTC).isoformat(),
            "eventIds": sorted(event_ids), "eventCount": len(event_ids)}
    result = {**core, "sha256": digest(canonical(core))}
    validate_manifest(result)
    return result


def validate_manifest(manifest: dict) -> dict:
    if not isinstance(manifest, dict) or set(manifest) != MANIFEST_FIELDS:
        raise ValidationFailure("MANIFEST_FIELDS")
    if type(manifest["formatVersion"]) is not int or manifest["formatVersion"] != 1:
        raise ValidationFailure("MANIFEST_VERSION")
    uuid_string(manifest["cutId"])
    timestamp(manifest["exportedAt"])
    if not isinstance(manifest["source"], str) or not 1 <= len(manifest["source"]) <= 200:
        raise ValidationFailure("MANIFEST_SOURCE")
    ids = manifest["eventIds"]
    if not isinstance(ids, list) or any(not isinstance(value, str) for value in ids):
        raise ValidationFailure("MANIFEST_IDS")
    for value in ids:
        uuid_string(value)
    if ids != sorted(set(ids)) or type(manifest["eventCount"]) is not int or manifest["eventCount"] != len(ids):
        raise ValidationFailure("MANIFEST_MEMBERSHIP")
    core = {name: value for name, value in manifest.items() if name != "sha256"}
    if manifest["sha256"] != digest(canonical(core)):
        raise ValidationFailure("MANIFEST_DIGEST")
    return manifest
