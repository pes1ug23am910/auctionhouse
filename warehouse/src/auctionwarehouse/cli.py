from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from .store import Warehouse
from .validation import ValidationFailure, make_manifest, uuid_string, validate_event


def output(value: object):
    print(json.dumps(value, indent=2, default=str))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


def export_cut(base: str, cut_id: str, destination: Path, cookie: str) -> dict:
    uuid_string(cut_id)
    if not cookie:
        raise ValueError("AUCTIONHOUSE_ADMIN_COOKIE must be supplied through the environment")
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("invalid source URL")
    opener = urllib.request.build_opener(NoRedirect())

    def get(path: str):
        request = urllib.request.Request(base.rstrip("/") + path, headers={"Cookie": cookie, "Accept": "application/json"})
        try:
            with opener.open(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Source export failed with HTTP {error.code}") from None

    route = f"/api/admin/event-cuts/{cut_id}"
    report = get(route)
    if report.get("missing") or report.get("unexpected"):
        raise ValueError("source sink is not reconciled at this cut; wait for delivery or investigate unexpected IDs")
    destination.mkdir(parents=True, exist_ok=False)
    ids: list[str] = []

    def pages(endpoint: str, filename: str, collect_ids: bool) -> int:
        after = ""
        total = 0
        with (destination / filename).open("x", encoding="utf-8", newline="\n") as stream:
            while True:
                query = "?limit=1000" + ("&after=" + urllib.parse.quote(after) if after else "")
                batch = get(route + endpoint + query)
                if not isinstance(batch, list):
                    raise ValueError("source export did not return an event list")
                if not batch:
                    break
                for raw in batch:
                    event, canonical, _ = validate_event(raw)
                    if after and event["eventId"] <= after:
                        raise ValueError("source pagination is not strictly increasing")
                    after = event["eventId"]
                    if collect_ids:
                        ids.append(after)
                    stream.write(canonical + "\n")
                    total += 1
                if len(batch) < 1000:
                    break
        return total

    source_count = pages("/events", "source-events.jsonl", True)
    if source_count != report.get("expected"):
        raise ValueError("source cut count does not match the complete exported identity set")
    sink_count = pages("/notifications", "sink-events.jsonl", False)
    manifest = make_manifest(ids, cut_id, "postgres-outbox-immutable-cut")
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (destination / "source-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return {"cutId": cut_id, "sourceEvents": source_count, "sinkEvents": sink_count,
            "manifestSha256": manifest["sha256"], "directory": str(destination),
            "ingestInput": "sink-events.jsonl", "path": "auction/outbox/broker/notification-sink/export"}


def main() -> int:
    parser = argparse.ArgumentParser(description="Immutable auction-event warehouse")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ["init", "ingest", "reconcile", "rebuild"]:
        command = commands.add_parser(name)
        command.add_argument("--database", required=True)
        if name == "ingest":
            command.add_argument("--input", type=Path, required=True)
            command.add_argument("--source", required=True)
            command.add_argument("--manifest", type=Path)
        elif name == "reconcile":
            command.add_argument("--cut", required=True)
            command.add_argument("--delivery-complete", action="store_true")
        elif name == "rebuild":
            command.add_argument("--target", type=Path, required=True)
    export = commands.add_parser("export")
    export.add_argument("--base-url", default="http://127.0.0.1:8080")
    export.add_argument("--cut", required=True)
    export.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "export":
            output(export_cut(args.base_url, args.cut, args.output, os.environ.get("AUCTIONHOUSE_ADMIN_COOKIE", "")))
            return 0
        with Warehouse(args.database) as warehouse:
            if args.command == "init":
                output({"initialized": args.database})
            elif args.command == "ingest":
                cut = warehouse.register_cut(json.loads(args.manifest.read_text(encoding="utf-8"))) if args.manifest else None
                result = warehouse.ingest_file(args.input, source=args.source, cut_id=cut)
                output(result)
                return 1 if result.get("quarantined", 0) else 0
            elif args.command == "reconcile":
                result = warehouse.reconcile(args.cut, delivery_complete=args.delivery_complete)
                output(result)
                if result["missing"] or result["unexpected"] or result["duplicateEffects"] or result["quarantine"]:
                    return 1
                return 2 if result["pending"] else 0
            elif args.command == "rebuild":
                output(warehouse.rebuild(args.target))
        return 0
    except (ValidationFailure, ValueError, RuntimeError) as error:
        output({"error": str(error)})
        return 1
