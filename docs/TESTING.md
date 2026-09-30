# Testing

These are required checks, not a report that suites or future components have
run. A passing claim identifies the suite, command, source revision/diff,
environment and recorded output.

## Executable build contract

`test` selects JUnit 6 unit tests without Docker. `integrationTest` selects
`src/integrationTest/java` and needs a reachable Docker daemon for actual
PostgreSQL Testcontainers tests. `build` includes unit checks and artifact
construction; database integration is separate. See
[DEVELOPMENT.md](DEVELOPMENT.md) for commands and version authority.

Do not replace an unavailable database test with a mock and label isolation,
locking or migrations validated. Failed, skipped and unavailable checks stay
visible with their prerequisites.

## Lifecycle and identity

Use an independent reference model and database cases covering:

- Draft creation, owner publication, local reservation/announcement intent
  and atomic outbox; owner cancellation only without accepted bids.
- Opening price, positive increment, owner rejection, exact-unit range and
  overflow, multiple increasing accepts and equal/lower rejection.
- Deadline boundary, lock wait crossing it, close/bid races, no extension
  and unsold closure.
- Same-key concurrent retries, changed-payload conflict, distinct
  actor/auction/operation namespaces and cross-instance uniqueness.
- Response loss after commit, authorized status lookup, exact replay after
  closure and after later aggregate versions.
- Durable rejection, rollback, serialization/deadlock retry and exhausted
  retry without a fabricated committed outcome.
- Thirty-day outcome replay with permanent identity reservation, 410 after
  expiry, and UNKNOWN for absent/uncommitted identity;
  explicit tombstone/expiry cases before any future deletion policy.
- Atomic auction state, accepted history, durable outcome and outbox across
  transaction-failure boundaries.

An independent history oracle checks every accepted bid against its preceding
committed state. Final price equality cannot detect every invalid intermediate
acceptance. Compare both isolation strategies under the same contract,
including retries and multiple application instances.

Record 20 contention runs with 200 clients against a seeded auction. Preserve
logical IDs, attempts/commits, rejected/unknown responses, retries, final state
and event history. These are experimental conditions, not universal guarantees.

## Full-scope acceptance

| Area | Evidence required |
|---|---|
| Bootstrap | Compatible clean build, empty-database migrations, health, independently selectable unit/integration tasks and recorded CI run |
| SQL | Seven business families; seeded 1M-bid distributions/cardinalities, result oracles, EXPLAIN ANALYZE/BUFFERS, timings and index/storage/write trade-offs |
| Authentication | Mock OIDC and separately verified real provider; state/nonce/PKCE as applicable, redirect validation, expired/revoked credentials, concurrent refresh, rotation/reuse/family revocation, logout, CSRF and full route/role 401/403 matrix |
| Outbox/sink | 20 enumerated crash/restart runs, actual durability settings, retries/backpressure, atomic effect/dedup and missing/duplicate/unexpected IDs at a common frozen cut |
| Kafka candidate | If selected, actual Apache Kafka producer/consumer and failure comparison, including differences from Redpanda |
| Cache | Same supported memcached/pagekv cases after capability verification; TTL rounding/boundaries, expiry/restart, stale policy, fill/invalidation races, fallback and hit/miss/load measurements |
| Redis candidate | If selected, actual Redis operations, expiry/invalidation/failures and separately recorded comparison |
| Warehouse | Version rejection/quarantine, replay, identity/payload conflict, correction/replacement, grain/dimensions, dbt checks and repeated frozen-cut reconciliation; real event path separate from fixtures |
| React | Accessible login/create/browse/bid/close/result, visible failures, Vitest state tests and Playwright against the full selected Compose stack |
| SSE/Node gateway | Java baseline and independent authorized Node fan-out; cursors, snapshots/gaps, duplicate/out-of-order input, snapshot/live races, bounded buffers, slow clients, restart and same-workload comparison |
| Delivery | Thin and complete releases, immutable artifact, migration compatibility, TLS/identity/network negative checks, health/smoke, failed-release rollback, resource manifest, cost/expiry and teardown inventories |
| Operations | Seeded browse/bid load, offered/completed rate, errors/timeouts/retries, p50/p95/p99, saturation/traces; SLO/error-budget alert/recovery and one-versus-two-host configuration/measurements |

## Source-cut and recovery adversaries

Use [EVENTS.md](EVENTS.md). Hold an early event transaction uncommitted while a
later one commits, then capture the cut. Only committed snapshot-visible IDs
belong to that cut; the earlier allocation belongs to a later cut when committed.

Test equal counts with unequal sets, repeated transport with one sink effect,
duplicate effects, unexpected IDs, late delivery, unsupported versions and
changed manifest digests. Assertions compare sets/multiplicities, not totals.

For SSE, race changes with snapshot capture and subscription setup. Recovery
must not miss the transition between snapshot and live delivery. Test cursor
generation/auction mismatch and credentials expiring on reconnect.

The 2026-10-01 targeted regression run passed 61 invocations across
`AuctionServiceIntegrationTest`, `AuthHttpIntegrationTest`,
`DeliveryIntegrationTest` and `AuctionStreamIntegrationTest`, with no failures
or skips. This invocation selected these four classes, not the entire suite.
It includes eight real database interleavings covering both isolation modes,
owner and scheduled closure, and either bid or closure holding the auction
lock first. Assertions check the committed winner, retained outcome, fixed
deadline, bid history and ordered outbox effects.

The same run checks the actual administrator cut capture/report/export routes
for anonymous and ordinary-user rejection, administrator access, CSRF and
current-role revocation. Its MVCC adversary holds an earlier event uncommitted
while a later event commits, proving the first immutable cut excludes the
earlier allocation and a subsequent cut includes both. The schema uses UUID
event identities; an earlier occurrence timestamp is not a committed watermark.

Three Java SSE cases additionally exercise a commit between HTTP snapshot and
subscription, a missing retained version, and a cursor beyond the configured
replay window. They verify consistent state/cursor recovery and the next live
event. Large retained-history fixtures establish transport behavior only.

The same date also has actual k6 authentication compatibility evidence for
`AUTH_MODE=local-demo` and `AUTH_MODE=session`, each configured for 5 operations
per second over 10 seconds against a fresh auction. All 50 and 51 actual
iterations respectively completed without infrastructure errors or dropped
iterations. Each produced 13 accepted bids; retained intents, bid identities,
versions, final state and transactional outbox counts reconciled. All 13
session-mode JSON outcome records matched their durable intents field for field.
The session run used a temporary local HTTPS proxy and self-signed certificate
with the explicitly test-only `--insecure-skip-tls-verify` option. This checks the
real k6 client and session-file path, not a cloud deployment, external identity
provider, notification transport or capacity claim; the broker was disabled.

## Measurement and CI

Correctness assertions gate changes. Benchmarks retain raw data and a declared
acceptance policy; they do not manufacture a speedup requirement. Record
unchanged/regressed results and trade-offs. Timeouts and retries count in load
outcomes.

CI jobs have timeouts, full-commit-pinned actions and retained failure artifacts.
Documented commands match the jobs. Ignored exits or hidden retries must not
turn failure into success. Native sanitizer/race checks apply only if an actual
native component is part of this repository; they are not presumed Java jobs.

Randomized checks expose/accept seeds. Tests own isolated fixtures and do not
mutate normal developer databases. Contention retains histories. Fault runs
record the exact boundary, process and configuration.

Intermittent failures stay unresolved until reproduced or explained. Keep the
first failure, reduce reproduction and fix the cause. Any quarantine is
visible, keeps running, names an owner/exit condition and cannot support a
passing coverage claim. Quarantine is not a way to hide unavailable
infrastructure.
