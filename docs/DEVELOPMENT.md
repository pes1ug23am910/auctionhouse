# Development

The versioned build files and locks are the authority. The exercised local stack uses Java 25, Spring Boot 4.1.1, Gradle 9.8.0 and PostgreSQL 18.6. The Boot platform manages JUnit 6, Testcontainers 2, Flyway and JDBC. Frontend/gateway packages use Node 24 and npm locks; the warehouse uses a separate Python 3.13 environment.

## Build and test

Run from the repository root:

```powershell
.\gradlew.bat --version
.\gradlew.bat test
.\gradlew.bat integrationTest
.\gradlew.bat build
```

Use `bash ./gradlew` on Linux/macOS. `test` runs without Docker. `integrationTest` explicitly selects the container/HTTP integration source set and needs a reachable Linux-container Docker daemon. `build` runs units and creates `build/libs/auctionhouse.jar`; it does not imply integration tests or browser assets were built. CI invokes integration separately. Additional jobs build/test the frontend and gateway, run Chromium accessibility/session fixtures, and execute the pinned warehouse pytest suite including its actual dbt build. A separate PostgreSQL/browser job generates a fresh masked demo credential and exercises publishing, bidding, offline reconnect and deadline closure; credential-bearing network traces are disabled. Workflow configuration and local actionlint validation do not imply a completed remote CI run.

Install and check the client:

```powershell
npm.cmd --prefix frontend ci
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
```

For a single-origin local application with the actual disposable Keycloak fixture, follow the [README quickstart](../README.md). Copy the built client into `build/resources/main/static` after `classes`, then run `bootRun` with `local,oidc`. This directory is ignored build output. Rebuild and recopy the UI after client changes; `clean` removes it. The Dockerfile performs the equivalent bundling inside its isolated build stages.

For frontend hot reload, run `npm.cmd --prefix frontend run dev`; Vite serves `127.0.0.1:5173` and proxies API requests to `127.0.0.1:8080`. Use the local demo login with an explicitly configured BCrypt hash, or use the bundled same-origin setup for OIDC. Do not assume a Vite port is included in an identity provider's registered callbacks.

The local demo route exists only under `local` and has no built-in password. Supply `AUCTIONHOUSE_DEMO_PASSWORD_HASH` from an ignored/private configuration source and use the matching password in the browser. Browser test execution uses `AUCTIONHOUSE_DEMO_PASSWORD` in the test process environment, never an argument or checked-in file.

## Local profiles

`local` binds the Java service to loopback and uses the Compose development database. Its example database password is local-only; `AUCTIONHOUSE_DB_PASSWORD` overrides it on both sides. `AUCTIONHOUSE_DB_PORT` changes the loopback PostgreSQL port. The base profile accepts `AUCTIONHOUSE_DB_URL`, `AUCTIONHOUSE_DB_USER` and `AUCTIONHOUSE_DB_PASSWORD`.

| Capability | Start dependencies | Application configuration |
| --- | --- | --- |
| PostgreSQL | `docker compose up -d --wait postgres` | `local` |
| Keycloak fixture | `docker compose -f compose.yaml -f compose.auth.yaml --profile auth up -d postgres keycloak` | `local,oidc`; wait for issuer discovery |
| Redpanda delivery | `docker compose -f compose.yaml -f compose.broker.yaml up -d --wait` | `local,broker` |
| Cache comparisons | See [CACHE](CACHE.md) and `compose.cache.yaml` | Select the documented adapter explicitly; disabled by default |
| Telemetry | See `compose.observability.yaml` and [LOAD-TEST](LOAD-TEST.md) | Explicit local/private OTLP endpoint and agent opt-in |

Profiles compose, for example `local,oidc,broker`. Start only the dependencies needed for a run. Flyway applies additive migrations on ordinary local startup; never edit an applied migration. Isolated integration tests use their own databases and containers. Do not use a development database as a disposable integration-test database.

## Independent entry points

| Check or experiment | Entry point and prerequisite |
| --- | --- |
| Domain/history, auth, stream and delivery tests | `test` / `integrationTest`; see [TESTING](TESTING.md) for selected classes |
| Contention and independent history oracle | [experiments/contention](../experiments/contention/README.md) |
| Two actual application instances | `experiments/http-instances.mjs`, with two configured local services |
| Seven-query SQL catalogue | `python sql/run_catalogue.py --bids 1000000 --seed 20261001 --repetitions 3`; dedicated benchmark database |
| Real broker fault experiments | [DELIVERY](DELIVERY.md); explicitly opt into the broker runner |
| Frontend browser flow | In `frontend`: `npx playwright install chromium`, then `npm run test:e2e`; real flow requires local service/demo credential |
| Node gateway | In `gateway`: `npm ci`, `npm test`, `npm start`; upstream Java service required to run |
| DuckDB/dbt | [warehouse/README](../warehouse/README.md); isolated environment and `requirements.lock` |
| Fixed-cut warehouse replay | `warehouse/tools/capture_local_cut.py`, then `warehouse/tools/run_cut.py`; actual source/sink must reconcile |
| k6 workload | [experiments/load](../experiments/load/README.md); explicit measurement window and private credential environment |
| Immutable image and rollback | [DEPLOYMENT](DEPLOYMENT.md), `ops/Deploy-Local.ps1`, `ops/Test-LocalRollback.ps1` |
| Infrastructure checks | `terraform -chdir=infra init -backend=false`, `fmt -check -recursive`, `validate`, `test`; mocked-provider tests make no AWS calls |

The million-row SQL run, repeated contention, real broker failures and operational measurements are explicit experiments; ordinary `build` does not run them. Avoid concurrent builds/container startup during measurements. Record skipped and failed runs as such.

## Layout and configuration

```text
src/main/java/io/auctionhouse/     auction, auth, outbox, cache, HTTP and telemetry
src/main/resources/db/migration/  additive PostgreSQL migrations
src/test/java/                    units and independent domain-model tests
src/integrationTest/java/         real database, HTTP, provider and transport checks
frontend/                        React/TypeScript and Playwright
gateway/                         experimental Node SSE transport
warehouse/                       Python ingestion, DuckDB and dbt
sql/                             catalogue, seed/oracles and plan runner
experiments/                     contention, two-instance and load tools
observability/                   local collector, dashboards and agent settings
ops/, infra/                     immutable delivery and reviewed cloud interfaces
docs/                            technical contracts and bounded evidence reports
```

Credentials, cookies and token-bearing headers must stay out of source, summaries, events and request logs. Use environment variables or private ignored secret files. The telemetry agent is disabled by default in the image and requires an explicit destination when enabled. Do not dump Docker environments to diagnose credentials.

Bid amounts and fixtures use exact integer minor units. Retry the original actor/auction/key/amount after uncertainty; do not manufacture a replacement key. An expired outcome reserves its identity permanently. Cookie refresh is serialized across browser tabs and never replaces auction idempotency.

## Release and evidence scope

The container build bundles client assets, runs frontend/Java units, and produces a non-root read-only runtime with a separate migration command. Local release verification covers readiness, UI/CSRF behavior, deliberately failed-candidate rollback and database privilege boundaries. Migration compatibility must be reviewed before replacing an existing release; application rollback does not undo database changes.

Cloud plans/applies, public publication and resource purchases are not part of a local build. Real AWS delivery/negative tests/teardown, actual PageKV, full-cloud dependency deployment and one-versus-two-host measurements remain separately required work. The dated local observation report verifies trace continuity and alert recovery and retains both failed offered-load targets. Its timings apply only to the recorded measurement artifact, which predates the dependency security updates.

A measurement names the command, source revision/diff, environment, seed, configuration and raw output. An HTTP fixture is not a real broker/provider/cloud run. Accepted architecture changes create a new ADR; preserve prior records and document the superseding decision. See [TESTING](TESTING.md), [SPEC](SPEC.md) and [DEPLOYMENT](DEPLOYMENT.md).
