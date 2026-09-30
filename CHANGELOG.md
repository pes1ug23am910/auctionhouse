# Changelog

## Unreleased

### Added

- Java 25/Spring Boot 4.1.1 application, pinned Gradle build, PostgreSQL migrations, unit and separate container integration tasks.
- Fixed-deadline auction lifecycle with exact integer amounts, owner restrictions, durable bid outcomes, 30-day replay and permanent identity reservation.
- READ COMMITTED row locking and SERIALIZABLE retry strategies, independent history verification, cross-instance recovery and reproducible contention experiments.
- OIDC authorization code with PKCE, local Keycloak fixture, opaque token families, hashed storage, rotation/reuse revocation, CSRF and ownership checks.
- Transactional outbox, Redpanda relay and notification sink, poison-event quarantine, crash/replay experiments and explicit immutable source cuts.
- Versioned cache-aside reads with memcached, Redis and PageKV adapters; actual memcached/Redis checks and PageKV wire fixtures.
- Seven SQL query families, independent result models and a seeded million-row indexing experiment.
- DuckDB event ingestion, immutable-ID conflict handling, attempt metadata, schema quarantine, replacement rebuilds and dbt star models.
- React auction browsing, creation, bidding and result flows, persisted bid recovery, offline reconnection and browser/accessibility checks.
- Java nonblocking SSE with bounded frame/byte queues, backpressure/stall handling, versioned resume and per-chunk authority checks.
- Independent Node.js SSE gateway experiment with original-credential authorization, fan-out, bounded buffers and restart/replay tests.
- Bundled immutable release image, local smoke/rollback and database-role fixtures, reviewed Terraform interfaces and scoped cloud release/teardown scripts.
- OpenTelemetry tracing/metrics configuration and a seeded constant-arrival k6 workload for the local operational comparison.

### Corrected

- Browser text contrast and encoding artifacts, explicit offline stream recovery, coordinated cross-tab refresh and account-bound intent recovery.
- Expired access cookies no longer prevent public frontend resources from loading.
- Public cache entries require matching versions and visibility, including a cancellation race regression.
- Slow Java stream clients no longer depend on blocking emitter writes; pending native flushes remain subject to the stall watchdog.

### Scope

Local validation and measured experiments are described beside their commands. Real AWS application delivery/negative tests/teardown, the actual PageKV counterpart, complete cloud-stack deployment, and multi-host/load-balancer evidence remain outstanding. Local trace continuity, dashboards and alert recovery were verified. Both measured load variants missed their offered-work targets; the dated report preserves the regression and artifact baseline. No production capacity or payment-processing claim is made.
