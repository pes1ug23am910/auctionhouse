# auctionhouse

An online auction service written as a modular monolith in Java 25 with
Spring Boot on PostgreSQL. The bid transaction uses idempotency keys with
single-flight handling and a deterministic lock order, and is built twice:
row locks under READ COMMITTED, and SERIALIZABLE with retry. An analytics
query set (window functions, recursive CTEs, percentiles) runs over a seeded
dataset with EXPLAIN-driven indexing. Login goes through OpenID Connect
against a mock identity provider in CI, with refresh-token rotation and
family revocation. A transactional outbox relays events through a Kafka-API
broker into a transactional sink. A cache-aside layer sits behind a cache
interface with a memcached-protocol client. An event warehouse loads the
service's own events idempotently and reconciles them by event-id sets at a
common watermark.

## Planned components

- Gradle build, Docker Compose (PostgreSQL, memcached), Flyway migrations, Testcontainers
- Place-bid transaction, idempotency store, invariant oracle
- SQL analytics set with recorded plans and timings
- Authentication and authorization with a 401/403 matrix
- Outbox relay with retry state and atomic effect-plus-dedup
- Cache-aside layer: lookup, fill, invalidate, failure fallback
- Event warehouse with dbt models and reconciliation
- Optional front end (React, TypeScript, Server-Sent Events)
- Load test and observability (k6, OpenTelemetry, Grafana)

## Toolchain

Java 25, Spring Boot 4.1, Gradle, JUnit 5, Flyway, Testcontainers, PostgreSQL 18.

Status: scaffold. No functionality yet.

## Documentation

- `docs/ARCHITECTURE.md` — components, data flow, invariants
- `docs/SPEC.md` — interfaces, configuration, acceptance checks
- `docs/DEVELOPMENT.md` — toolchain, build, test, conventions
- `docs/DEBUGGING.md` — method, logging, tools
- `docs/TESTING.md` — test kinds, CI expectations, flaky-test policy
- `docs/adr/` — architecture decision records
- `CHANGELOG.md`
- `docs/SQL.md`
- `docs/LOAD-TEST.md`
- `docs/EVENTS.md`
