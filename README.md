# auctionhouse

[![Build and integration tests](https://github.com/pes1ug23am910/auctionhouse/actions/workflows/ci.yml/badge.svg)](https://github.com/pes1ug23am910/auctionhouse/actions/workflows/ci.yml)

A fixed-deadline auction application with a React interface, a Java 25/Spring Boot backend and PostgreSQL as the authority for bids. Sellers create and publish listings; other accounts place exact integer offers, follow live updates and see the recorded winner after closure. No payments are collected.

The implementation includes durable bid identities, two transaction isolation strategies, OIDC and rotating application credentials, a transactional outbox and broker sink, bounded SSE delivery, cache adapters, SQL experiments and a DuckDB/dbt warehouse. It is a locally exercised engineering project; real AWS delivery and multi-host performance remain separate work.

## Run the local application

Install Java 25, Node 24 and Docker with Linux containers. The Gradle wrapper is included. These PowerShell commands build the browser client into the ignored local build resources and start PostgreSQL plus the disposable Keycloak fixture:

```powershell
docker compose -f compose.yaml -f compose.auth.yaml --profile auth up -d postgres keycloak
npm.cmd --prefix frontend ci
npm.cmd --prefix frontend run build
.\gradlew.bat classes
New-Item -ItemType Directory -Force build/resources/main/static | Out-Null
Copy-Item -Path frontend/dist/* -Destination build/resources/main/static -Recurse -Force
.\gradlew.bat bootRun --args='--spring.profiles.active=local,oidc'
```

Before starting the application, Keycloak's [local discovery endpoint](http://localhost:8081/realms/auctionhouse-local/.well-known/openid-configuration) must respond. Open [localhost:8080](http://localhost:8080), choose **Continue with your identity provider**, and use the disposable fixture account `seller` / `local-seller-fixture-only` or `bidder` / `local-bidder-fixture-only`. Use separate browser profiles to view both accounts together. These deliberately public credentials belong only to the loopback fixture; [authentication documentation](docs/AUTHENTICATION.md) explains its boundaries.

The UI flow is **List a piece → Create draft → Publish your listing → bid from another account → final result**. Owners cannot bid, the deadline never extends, and the scheduled closer records the result. Cancellation requires no accepted bids.

For a browser development server, optional local demo login, the immutable container release and Linux command equivalents, see [Development](docs/DEVELOPMENT.md) and [Deployment](docs/DEPLOYMENT.md).

## Correctness boundaries

- Amounts are whole minor units from 1 through 9,000,000,000,000,000. JSON strings, fractions and booleans are not accepted as amounts.
- Each bid carries an immutable identity scoped by actor, auction, operation and key. A lost response is recovered with that same identity. Changed payloads return 409; outcomes replay for 30 days, then return 410 while the fingerprint remains reserved indefinitely.
- Bidding, publication, cancellation and closure serialize on the auction row. The deadline is checked using the database clock after the lock is acquired. READ COMMITTED row locks and SERIALIZABLE with bounded retry share the same domain rules.
- Accepted state, bid history, outcome and outbox event commit together. The notification sink couples its effect and deduplication transaction before broker acknowledgement.
- Stream snapshots and cursors identify the same auction version. Slow readers have bounded application queues and disconnect; reconnects recover an authoritative snapshot or retained history. Browser prices change only after server confirmation.
- PostgreSQL owns authorization and auction decisions. Cache, gateway and warehouse failures do not authorize bids.

## Components and evidence

| Area | Implemented local behavior | Detail |
| --- | --- | --- |
| Auction and recovery | Lifecycle, durable accepted/rejected outcomes, independent history checks, two-instance recovery and contention experiments | [Specification](docs/SPEC.md), [contention runner](experiments/contention/README.md) |
| Authentication | OIDC code flow with PKCE, real local Keycloak and signed mock negative tests, hashed opaque tokens, rotation and family revocation | [Authentication](docs/AUTHENTICATION.md) |
| Browser and SSE | Responsive React flows, persisted bid recovery, versioned SSE, offline reconnect, deadline/winner browser checks and automated accessibility checks | [Frontend](frontend/README.md), [events](docs/EVENTS.md) |
| Delivery | Redpanda transport, actual Apache Kafka comparison, crash/replay experiments, atomic notification effect/dedup and immutable source cuts | [Delivery](docs/DELIVERY.md) |
| Cache | Versioned public-detail cache, failure fallback, actual memcached and Redis checks; PageKV adapter fixtures | [Cache](docs/CACHE.md) |
| SQL and warehouse | Seven-query million-row SQL experiment; strict DuckDB ingestion, quarantine, dbt star models, three replays of one real sink cut | [SQL](docs/SQL.md), [warehouse](docs/WAREHOUSE.md) |
| Gateway experiment | Authenticated Node.js fan-out, bounded buffers, resume/restart tests and paired event collector; Java remains the default | [Gateway](docs/GATEWAY.md) |
| Delivery operations | Bundled immutable image, local smoke/rollback and database-role checks; Terraform and scoped cloud release/teardown interfaces | [Deployment](docs/DEPLOYMENT.md) |
| Observation and load | Verified asynchronous trace, live dashboards, seeded k6 comparison with failed load targets retained, and alert firing/recovery | [Observability](docs/OBSERVABILITY.md), [load report](docs/LOAD-TEST.md), [host comparison harness](experiments/hosts/README.md) |

Local checks establish their named configurations, not production scale. The actual PageKV server, full cloud stack, real AWS negative tests and teardown, hosted-provider configuration, and one-versus-two-host load-balancer comparison remain unverified. These requirements are retained in the technical specifications.

## Verify

```powershell
.\gradlew.bat test
.\gradlew.bat integrationTest
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
npm.cmd --prefix gateway ci
npm.cmd --prefix gateway test
```

`test` is the Docker-free Java unit suite. `integrationTest` uses actual PostgreSQL/Keycloak and other selected containers; it requires Docker. Broker crash and large SQL/contention measurements have explicit runners and are not implied by `build`. The frontend's real Playwright flow needs the local demo credential supplied through the environment; fixture and guest browser checks are labelled separately. Warehouse installation and dbt commands are in [warehouse/README.md](warehouse/README.md).

Toolchain pins live in the Gradle version catalogue/wrapper, npm locks, warehouse lock and container digests. The current Java stack uses Spring Boot 4.1.1, Gradle 9.8.0, JUnit 6, Testcontainers 2 and PostgreSQL 18.6.

[Architecture](docs/ARCHITECTURE.md) Â· [Development](docs/DEVELOPMENT.md) Â· [Testing](docs/TESTING.md) Â· [Architecture decisions](docs/adr/README.md) Â· [Changelog](CHANGELOG.md)
