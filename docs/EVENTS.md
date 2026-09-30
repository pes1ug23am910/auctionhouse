# Event and recovery contracts

These contracts cover the transactional outbox, transport, SSE, notification
sink and warehouse. The JSON wire fields below are camelCase; relational
columns use snake_case. See [DELIVERY.md](DELIVERY.md) for the implemented
transport and its measured verification boundary.

## Envelope and identity

| Field | Type | Meaning |
|---|---|---|
| `eventId` | UUID | Immutable business identity; deduplication/reconciliation key |
| `eventType` | String | Event family, such as `auction.published`, `bid.accepted`, `auction.closed`, `auction.cancelled` |
| `schemaVersion` | Positive integer | Payload/envelope schema; not stream position |
| `aggregateId` | UUID | Auction identity |
| `aggregateVersion` | Positive integer | Committed auction transition order |
| `occurredAt` | UTC timestamp | Server occurrence metadata; not commit-order watermark |
| `payload` | Object | Allowlisted data for the type/version |

The initial contract emits one state-change event per auction version. The row
lock serializes version assignment; auction changes and events commit together.
A rejected bid creates a durable outcome but does not advance accepted state or
produce `bid.accepted`. Event IDs/payloads are immutable across relay retries.

Publication reflects committed local listing reservation and announcement
activation intent. It does not certify remote announcement/email delivery.
A notification record is a separate local sink effect.

Payloads carry only necessary data. Tokens, cookies, authorization headers,
passwords, refresh-token material and arbitrary request bodies are excluded.
Actor identifiers and bids remain subject to consumer authorization/privacy.

Consumers validate type and schema before applying effects. Unsupported or
malformed events are quarantined with identity and a sanitized reason, never
silently coerced. Correction/supersession is a new identified event, not a
mutation of published history.

## Publication and consumption

The outbox transaction does not synchronously depend on a broker. A relay
records retry state, publishes immutable events and records observed progress.
A crash between publication and progress commit may publish again. Retries
have bounded concurrency, backoff and backpressure. Retryable/terminal failures
remain observable.

The notification consumer commits its effect and event-ID deduplication in one
database transaction, then acknowledges. Restart or acknowledgement loss may
redeliver an event but must not duplicate the local effect.

Enumerate crashes before/after business commit, publication, relay-progress
commit, sink effect/dedup commit and acknowledgement. Record actual replication,
fsync and acknowledgement settings. At-least-once transport and a deduplicated
local effect do not promise external email delivery.

## SSE ordering and cursor

Ordering is per auction. A cursor identifies
`(streamGeneration, auctionId, aggregateVersion)`; its wire encoding is opaque
and versioned. The stable event UUID is independently available. An auction
cannot use another auction's cursor; changed generation requires recovery,
not comparison of unrelated positions.

Revalidate authorization when opening a subscription and before sensitive
delivery. Reconnection must not revive expired/revoked access. The Node.js
gateway implements these checks and consumes committed events; it cannot
decide bids or assign business versions.

Clients keep state and applied cursor together. Events at/below the cursor
are duplicate/old input and do not roll state backwards. A missing version,
generation mismatch or retention gap requires a consistent snapshot.
Unavailable history is explicit, never silently skipped.

Read snapshot state and version from one consistent database view. Separate
READ COMMITTED reads can observe mismatched transitions. The snapshot includes
the auction version and stream generation; recovery delivers subsequent
versions only. Durable replay covers the snapshot/subscription race: capture
snapshot, establish/replay from its version and deduplicate overlap. If
retention overtakes recovery, return another gap and repeat recovery.

Bound every connection queue. Disconnect/resynchronize slow clients instead
of allowing unbounded memory. Tests cover duplicates, out-of-order delivery,
gaps, disconnect/reconnect, gateway restart, authorization expiry and
snapshot/live races. A snapshot restores current state, not every intermediate
notification missed by a client.

## Immutable committed source cuts

Capture an explicit event-ID manifest within a PostgreSQL REPEATABLE READ
transaction. All selected events are visible in that one snapshot. Export the
complete set and finalize an immutable artifact containing:

- Unique cut ID and capture time.
- Exact sorted event IDs and expected multiplicity (one per business event).
- Source/filter definition and relevant schema/version metadata.
- Cryptographic digest of the canonical manifest and row count.

Validate the digest before use. Both sides of reconciliation reference the
same frozen cut. A maximum sequence, maximum timestamp or largest version
across auctions never substitutes for set membership. Sequence allocation
can precede a late commit or an abort.

An event uncommitted at capture is outside the cut even with an earlier
sequence/timestamp; it belongs to a later cut. A visible but undelivered event
is pending until the declared delivery window ends. Reports distinguish
pending/late delivery from missing, rather than treating immediate consumer
lag as loss.

Compare identity sets and multiplicities:

```text
missing    = expected IDs - observed IDs for this cut
unexpected = observed IDs attributed to this cut - expected IDs
duplicates = IDs with observed business-effect multiplicity > 1
```

Count delivery attempts separately: repeated transport is not duplicate
business effect. Unknown IDs and newer-cut events are reported separately,
never silently discarded to make counts agree.

## Warehouse grain and replay

Fact grain is one immutable business event, not a delivery attempt. Idempotent
loading keys on `eventId`; retain attempt metadata separately. Validate
versions before ingestion. Define dimensional keys, temporal interpretation
and correction/supersession in the selected dbt model.

Replay preserves one fact. Same identity with changed payload is corruption
or conflict, not an update. Rebuild/replacement declares target data version
and reconciliation cut.

Fixtures establish loader/schema behavior. End-to-end evidence needs a real
committed auction event, selected relay/transport path and warehouse/sink
report against the same frozen manifest.

## Implemented Java transport

The default Java route uses Servlet asynchronous nonblocking output. The default
limits are 128 connected clients, 128 queued frames and 256 KiB queued payload
per client, a two-second pending-output stall deadline and a ten-minute maximum
connection lifetime. A final flush that is waiting for native readiness remains
pending even after the application queue is empty. Writes use at most 1 KiB per
chunk and only proceed when `ServletOutputStream.isReady()` permits them.

Four fixed poll workers share a queue bounded by the configured client limit.
An independent 100 ms watchdog closes stalled/lifetime-expired clients and any
client whose last successful authority check is at least one second old. Polling
normally revalidates every 500 ms. Each sensitive chunk also checks current
auction visibility and the original opaque token immediately before writing.
A slow source/auth database can therefore disconnect a valid client; it cannot
extend authority indefinitely. Slot release and application-queue cleanup are
idempotent.

These are application admission/payload bounds, not a measurement of all servlet,
JVM or kernel buffers. Bytes already accepted by the network stack cannot be
retracted on revocation. No replay or cache bypasses current permissions:
non-owners lose access when an auction becomes private/cancelled.

The Java checks include readiness/chunking, byte/frame overflow, final-flush
stall tracking and authority changes between queued frames. Ten actual
Tomcat/PostgreSQL integration cases cover consistent snapshot/resume, rejected
cursors, private/cancelled visibility, revocation and access expiry during a
replay batch, finite capacity, and a real unread TCP socket whose slot is
released before a healthy connection resumes. These include cases that commit a bid
between an HTTP snapshot and subscription, remove an intermediate retained
version, and resume beyond the configured replay window. Each checks the
recovered snapshot/cursor and subsequent live event. Large replay inputs in these
transport tests are isolated committed-outbox fixtures; transaction correctness
and throughput are separate evidence.

The implementation follows the Servlet [nonblocking output](https://jakarta.ee/specifications/servlet/6.1/apidocs/jakarta.servlet/jakarta/servlet/servletoutputstream)
and [asynchronous lifecycle](https://jakarta.ee/specifications/servlet/6.1/apidocs/jakarta.servlet/jakarta/servlet/asynccontext)
contracts. Gateway comparisons must use the current Java transport and identical
event IDs; measurements of the earlier blocking emitter do not describe this
implementation.
