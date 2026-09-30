# Architecture

auctionhouse is an online auction service written as a modular monolith in Java 25 with Spring Boot on PostgreSQL: a bid transaction with idempotency keys and a deterministic lock order built under two isolation strategies, an analytics query set with EXPLAIN-driven indexing, OpenID Connect login with refresh-token rotation, a transactional outbox relayed through a Kafka-API broker, a cache-aside layer behind a cache interface, and an event warehouse reconciled by event-id sets.

## Components

| Component | Responsibility |
|---|---|
| `auction` | auctions, bids, the place-bid transaction, the invariant oracle |
| `idempotency` | idempotency keys with single-flight; response replay |
| `auth` | OIDC login, token rotation with family revocation, authorization policy |
| `outbox` | event table, relay with retry state, offset and ack handling |
| `notify` | local transactional notification sink with effect-plus-dedup |
| `cache` | `CacheStore` interface; memcached-text client; cache-aside layer |
| `warehouse` | event validation; idempotent DuckDB load; dbt models; reconciliation |
| `web` | REST controllers; SSE endpoint for live bids |
| `ops` | health, metrics, OpenTelemetry |

## Data flow

A bid request carries an idempotency key. The service checks the key (single-flight), runs the place-bid transaction against PostgreSQL, writes the outbox row in the same transaction, and returns. The relay reads the outbox, publishes to the broker, and records offsets. The notification sink consumes, applies the effect and the dedup mark in one transaction, and acknowledges. The warehouse loads events idempotently and reconciles event-id sets against the outbox at a common watermark.

## Invariants

- The highest accepted bid equals the stored current price after every transaction (the defined transaction invariant).
- The same idempotency key returns the same response and produces at most one bid.
- Outbox and business write commit together or not at all.
- Every relayed event has an id; reconciliation reports missing and duplicate ids at a watermark, never counts alone.

## Boundaries

A modular monolith: modules communicate through Java interfaces and the outbox, never through each other's tables. `cache` depends on nothing but the interface; the two adapters are interchangeable. `warehouse` reads events, never the service's tables.

See `SPEC.md` for interfaces and acceptance checks, and `adr/` for the
reasoning behind each choice.
