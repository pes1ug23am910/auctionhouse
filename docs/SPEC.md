# Specification

## Purpose

auctionhouse is an online auction service written as a modular monolith in Java 25 with Spring Boot on PostgreSQL: a bid transaction with idempotency keys and a deterministic lock order built under two isolation strategies, an analytics query set with EXPLAIN-driven indexing, OpenID Connect login with refresh-token rotation, a transactional outbox relayed through a Kafka-API broker, a cache-aside layer behind a cache interface, and an event warehouse reconciled by event-id sets.

## Scope and limits

- No payments, no money movement, no invoices.
- Not microservices; one deployable with modules.
- No real identity providers until integration evidence exists; CI uses a mock provider.

## Interfaces

REST (JSON): `POST /auctions`, `GET /auctions/{id}`, `POST /auctions/{id}/bids` (header `Idempotency-Key`), `GET /auctions/{id}/bids`, `GET /me`. `GET /auctions/{id}/events` (SSE).

Cache: `interface CacheStore { Optional<byte[]> get(String key); void set(String key, byte[] value, Duration ttl); void delete(String key); }` with adapters for memcached and for pagekv's protocol subset.

Events: see `EVENTS.md`.

## Configuration

- datasource, migrations (Flyway)
- OIDC issuer, client id, redirect; mock identity provider in CI
- broker bootstrap servers; outbox relay interval; consumer group
- cache backend (`memcached` or `pagekv`), address, TTLs
- isolation strategy (`row-locks` or `serializable`) for the bid transaction

## Behaviour and invariants

- The highest accepted bid equals the stored current price after every transaction (the defined transaction invariant).
- The same idempotency key returns the same response and produces at most one bid.
- Outbox and business write commit together or not at all.
- Every relayed event has an id; reconciliation reports missing and duplicate ids at a watermark, never counts alone.

## Acceptance checks

1. 20 recorded 200-client contention runs against one seeded auction with the invariant oracle passing.
2. Named tests cover refresh-token rotation, reuse detection and the 401/403 matrix.
3. 20 documented crash/restart runs of the relay and sink report expected, missing and duplicate event ids.
4. The same integration cases pass against memcached and against pagekv.
5. `docs/SQL.md` carries plans, timings, host and seed for every query family.

## Open questions

1. Whether Spring Security's OIDC client or a hand-wired code flow is used for the mock provider in CI.
2. Broker persistence settings in dev mode, recorded before any loss statement.
3. The SSE endpoint's reconnection contract if the front end is built.

Each open question is closed by an architecture decision record in
`adr/`.
