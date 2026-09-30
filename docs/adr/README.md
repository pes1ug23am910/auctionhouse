# Architecture decision records

One file per decision, numbered in order. A record is never edited after it
is accepted; a later record supersedes it and both link to each other.

Sections: **Status** (proposed, accepted, superseded by NNNN), **Context**,
**Decision**, **Consequences**, **Alternatives considered**.

Records describe the software and its trade-offs, nothing else.

## Accepted decisions

- [0001: Record architecture decisions](0001-record-architecture-decisions.md)
- [0002: Fixed deadlines and durable bid identity](0002-auction-contract.md)
- [0003: Java SSE default and Node.js gateway experiment](0003-sse-gateway.md)
- [0004: Compare actual cache backends](0004-cache-backends.md)
- [0005: Compare actual Apache Kafka](0005-broker-comparison.md)

Observed results live in the associated technical reports. These records describe the accepted design decisions; later measurements do not rewrite the original decision.
