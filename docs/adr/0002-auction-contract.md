# 0002 - Fixed-deadline auctions and durable bid identity

**Status:** accepted

## Context

Concurrent bids, retries and a response lost after commit must produce the
same history across application instances and isolation strategies. Clients
need to distinguish committed decisions from unknown transport outcomes.
Publication also needs a matched monolith workflow for comparison with
separate services.

## Decision

Use exact positive integer minor units in Java `long` values, capped at
`9000000000000000` for JSON/JavaScript interoperability. The first bid is at
least the opening price; subsequent bids are at least the current accepted
amount plus the positive increment. Reject overflow and out-of-range values.
The owner cannot bid.

Auctions move from DRAFT through owner publication to OPEN, then CLOSED at a
fixed deadline. No bid-driven extensions apply. Read server time after locking
the auction and accept only if `now < endsAt`. New bids at/after deadline
reject even before closure materializes. Closure stores the highest accepted
bid as winner or an unsold result. Owner cancellation requires no accepted
bids. Terminal auctions cannot reopen.

Publication atomically reserves the listing, records announcement activation
intent, moves to OPEN and appends its event in PostgreSQL. No external
participant is needed for the monolith commit.

Scope bid identity by `(actorId, auctionId, operation, key)`, with keys matching
`[A-Za-z0-9._:-]{1,128}`. Store an immutable canonical semantic digest.
Changed payload conflicts. Store accepted/rejected business outcomes durably;
replay the original outcome without reevaluating current auction state.
Authentication still gates replay/status lookup.

Acceptance atomically commits bid, auction/version, outcome and event.
Lock order begins with the auction, followed by its identity/child records.
READ COMMITTED row locking and SERIALIZABLE with bounded retry obey the same
contract. Single-flight optimizes local work; it is not cross-instance
correctness.

Replay outcomes for 30 days, measured by database time. Reserve identities and
fingerprints indefinitely; the initial implementation keeps their full rows as
tombstones. Expired identical retries/status return `410 INTENT_EXPIRED`;
changed payloads remain conflicts. Absent status is UNKNOWN and can represent
an in-flight or failed request. Expiry never permits a fresh key for the same
intended bid. Publish the protocol through `/api/auctions/bid-policy`.

Serialize aggregate versions under the auction lock. A consistent snapshot
includes its matching version; SSE resumes after it, with durable replay
covering the snapshot/live race. Reconciliation captures an immutable event-ID
manifest within REPEATABLE READ instead of using maximum allocated sequence.

## Consequences

Fixed deadlines and exact units make the oracle deterministic. Lock wait can
correctly cause expiry. Clients resolve lost successful responses after
closure without creating a new bid.

Indefinite retention consumes storage and requires an explicit later design
before cleanup. Absence is not proof of noncommitment. Hot-auction row locking
serializes decisions and must be measured; more instances do not remove it.

Manifest capture/storage costs scale with the selected event set. In return,
late commits and sequence gaps cannot silently move events across a cut.

## Alternatives considered

- Floating-point amounts: rounding undermines the exact increment invariant.
- Bid-driven extension: changes the accepted rule and closure/recovery cases.
- Key-only identity: collides across actors/auctions and can expose outcomes.
- In-memory identity: loses correctness after restart or across instances.
- Reevaluate retries: can turn committed success into rejection after closure.
- Delete old keys without tombstones: permits a forgotten second effect.
- Maximum outbox sequence as cut: allocation is not commit order, so an
  uncommitted earlier event can be omitted from the wrong cut.
