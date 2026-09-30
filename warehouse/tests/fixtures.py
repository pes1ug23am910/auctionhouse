from copy import deepcopy
from uuid import UUID

OWNER = str(UUID(int=1))
BIDDER = str(UUID(int=2))
OTHER = str(UUID(int=3))
AUCTION = str(UUID(int=10))
SECOND = str(UUID(int=11))
CUT = str(UUID(int=20))


def event(version=1, *, auction=AUCTION, event_id=None):
    types = {1: ("auction.created", "DRAFT"), 2: ("auction.published", "OPEN"),
             3: ("bid.accepted", "OPEN"), 4: ("bid.accepted", "OPEN"), 5: ("auction.closed", "CLOSED")}
    kind, status = types[version]
    amount = None if version < 3 else 100 if version == 3 else 150
    bidder = None if version < 3 else BIDDER if version == 3 else OTHER
    payload = {"id": auction, "ownerId": OWNER, "title": "Fixture vessel", "description": "Synthetic auction",
               "openingPriceMinor": 100, "minimumIncrementMinor": 10,
               "endsAt": "2026-01-01T01:00:00Z", "status": status,
               "highestBidAmountMinor": amount, "highestBidderId": bidder, "version": version,
               "createdAt": "2026-01-01T00:00:00Z", "winnerId": bidder if status == "CLOSED" else None}
    occurred = "2026-01-01T01:00:00Z" if version == 5 else f"2026-01-01T00:0{version}:00Z"
    return {"eventId": event_id or str(UUID(int=1000 + int(UUID(auction)) * 10 + version)),
            "eventType": kind, "schemaVersion": 1, "aggregateId": auction,
            "aggregateVersion": version, "occurredAt": occurred, "payload": payload}


def all_events():
    result = [event(version) for version in range(1, 6)]
    result.extend([event(1, auction=SECOND), event(2, auction=SECOND)])
    cancelled = deepcopy(event(3, auction=SECOND))
    cancelled["eventType"] = "auction.cancelled"
    cancelled["payload"].update(status="CANCELLED", highestBidAmountMinor=None, highestBidderId=None)
    result.append(cancelled)
    return result
