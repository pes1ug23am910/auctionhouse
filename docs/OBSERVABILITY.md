# Local observability and load experiments

The optional Java agent exports OpenTelemetry traces, metrics and logs to a local Collector. The Collector sends traces to Tempo, exposes metrics for Prometheus, and retains bounded JSONL files for all three signals. Grafana provisions a dashboard and Prometheus/Tempo data sources. The normal application does not need this stack; its public actuator exposure remains health only.

## Run locally

Build the application, start PostgreSQL and the broker as described in [DEVELOPMENT.md](DEVELOPMENT.md), and configure the local demo password/hash through the existing environment settings. Never put credentials in command arguments, committed files or evidence.

Create a private environment file outside the repository containing:

```dotenv
AUCTIONHOUSE_TELEMETRY_DIR=/absolute/private/evidence/directory
GRAFANA_ADMIN_PASSWORD=<a-new-strong-local-password>
```

The telemetry directory must exist and be writable by the local Docker containers. From the repository root:

```powershell
docker compose --env-file <private-env-file> -f compose.yaml -f compose.observability.yaml config --quiet
docker compose --env-file <private-env-file> -f compose.yaml -f compose.observability.yaml up -d collector tempo prometheus grafana
docker compose -f compose.yaml -f compose.cache.yaml up -d memcached
./observability/fetch-agent.ps1 -Directory <private-agent-directory>
./observability/run-app.ps1 -OutputDirectory <private-launch-directory> -Instance local-observed -AgentJar <private-agent-directory>/opentelemetry-javaagent-2.31.1.jar -Profiles 'local,broker,cache'
```

The launch helper verifies the agent checksum, refuses an occupied port, caps the application heap at 384 MiB, and writes a process identity record. Stop only that owned process with `./observability/stop-app.ps1 -ProcessRecord <private-launch-directory>/process.json`. The helper checks PID, creation time, command line and application path before stopping it. A one-connection pool cannot run this Flyway startup path: Flyway needs a second connection. Use at least two; the normal default is sixteen.

Local endpoints are Collector OTLP HTTP `127.0.0.1:4318`, Prometheus `127.0.0.1:9090`, Tempo `127.0.0.1:3200`, and Grafana `127.0.0.1:3300`. Grafana requires username `auctionhouse` and the private password; anonymous access, signup, plugin installation and plugin auto-update are disabled. Ports are bound to loopback. Container limits are Collector 192 MiB, Prometheus 192 MiB, Tempo 512 MiB and Grafana 320 MiB; these are local fixture limits, not production sizing guidance. Prometheus retains 24 hours/256 MB. Collector files rotate at 10 MB with two backups per signal.

Compose pins the exact image digests for Collector 0.161.0, Tempo 3.1.0, Prometheus 3.15.0 and Grafana 13.2.3. Collector 0.162.0 had a release announcement but the checked public image tags were unavailable during verification, so the available 0.161.0 image was selected. The agent is 2.31.1 with SHA-256 `bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba`. See [the official agent release](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v2.31.1) and [Collector releases](https://github.com/open-telemetry/opentelemetry-collector-releases/releases).

## Trace continuity and metric meaning

The HTTP server and JDBC spans come from the agent. `outbox.record` captures only W3C `traceparent`/`tracestate` alongside the transactional outbox row. A later relay invocation restores that context for `outbox.publish` and explicitly injects it into Kafka headers. The instrumented consumer then parents `notification.apply` and its SQL work beneath the Kafka receive/process path. Public event JSON is unchanged. Without an agent the standard API is a no-op.

The 2026-10-01 local proof sent one real bid with a known parent context and verified a 24-span trace containing one HTTP server span, eighteen JDBC spans and the Kafka/outbox/sink path. It verified ancestry across the asynchronous boundary, read the persisted context, observed one committed outbox event and one notification effect, and retrieved the trace through Tempo. The runner is `experiments/load/trace-proof.mjs`; set `AUCTIONHOUSE_DEMO_PASSWORD`, `AUCTIONHOUSE_TRACE_OUTPUT` and `AUCTIONHOUSE_TELEMETRY_DIR` privately, then run it with Node from the repository root. This is an observed local trace, not evidence of an external deployment.

Metric semantics matter:

- `auctionhouse_bid_response_total` counts returned request outcomes, including successful idempotent replays. It is not a count of new committed bids.
- `auctionhouse_transaction_retry_total` counts transaction retry attempts; `auctionhouse_transaction_exhausted_total` counts exhausted budgets. The experiment's client retry counter is separate.
- `auctionhouse_outbox_publish_total` records observed publication acknowledgements/failures inside the relay transaction. A later enclosing transaction rollback can leave an acknowledged event eligible for replay. It does not prove a committed mark or exactly-once transport.
- `auctionhouse_notification_delivery_total` records sink transaction outcomes; caller-owned outer transactions can delay final commit. Database identities, unique effects and reconciliation provide the durable correctness evidence.
- Cache hit/miss/load/fallback counters describe the cache path. An authoritative database version/access lookup still precedes public detail cache use.
- HTTP duration/error and Hikari pool metrics come from instrumentation. The dashboard separates the agent JVM memory series from the similarly named Micrometer series to avoid double counting.

The Grafana dashboard's ten expressions were executed against the live Prometheus API. Empty error series display zero when request traffic exists; missing retry events remain absent. Metrics are not silently converted into evidence of traffic that did not occur. No application authorization or actuator policy was weakened to expose them.

HTTP request/response headers are not captured; the Collector additionally removes authorization/cookie/set-cookie attributes and process command-line attributes. SQL query sanitization is enabled. Logs remain bounded local OTLP JSONL files; there is no claim of a deployed log-search service. Keep the evidence directory private because operational metadata and fixture IDs remain present.

## Reproducible workload

[The load runner](../experiments/load/README.md) uses checksum/digest-pinned k6 2.3.0. It creates a fresh auction, uses a deterministic seed for approximately 75% public-detail reads and 25% bids, and treats durable business rejections as valid outcomes. Ambiguous bids reconcile their original identity before one bounded same-key retry. Credentials stay in memory. The PowerShell wrapper records the script hash, exact settings, container limits, host conditions and raw output:

```powershell
# AUCTIONHOUSE_DEMO_PASSWORD is already set privately in this process.
./experiments/load/run.ps1 -OutputDirectory <private-run-directory> -Label baseline -Rate 100 -DurationSeconds 45 -PreallocatedVUs 40 -MaxVUs 80
node ./experiments/load/reconcile.mjs <private-run-directory>
```

The reconciliation runner only reads the new fixture auction and checks accepted/rejected intent counts, accepted price/version progression, final state, complete outbox publication and one sink effect per event. The larger independent concurrent-history oracle is documented in [the contention experiment](../experiments/contention/README.md).

## Availability objective and recovery

A proposed local objective is 99% of offered logical operations completing with a valid outcome and p95 under 250 ms at the explicitly recorded workload. This is an engineering target, not a production SLO claim. Dropped k6 iterations consume the offered-work budget even when every request that reached the server succeeded. HTTP 5xx alone cannot measure these dropped operations.

`observability/probe.mjs` independently probes public health once per second and sends an OTLP gauge. Run it with `PROBE_OUTPUT` set to a private directory and `PROBE_DURATION_SECONDS` between 10 and 600. Its report separates failed health checks from telemetry export failures and computes an illustrative 99% probe-count error budget. A controlled stop of an owned process causes the Prometheus availability rule to become pending and fire after fifteen seconds; restoring the application resolves it. Keep the probe running continuously through the entire fixture: absent metrics after a probe exits are not recovery evidence.

The other rules cover Collector scrape failure, exhausted transaction retries, and a five-minute HTTP 5xx ratio over 2% for thirty seconds (twice the proposed 1% budget). Only the controlled availability firing/recovery was fault-injected for this local evidence; rule validation alone is not proof that every other alert was exercised. No external notification channel is configured.

## Measurement scope

The 2026-10-01 local comparison used application JAR SHA-256 `c76fc714495d8d0925652546618cc0158eab5b66608106452fe6f9a46c039a91` and workload SHA-256 `71116c0d64fed739faf420438307f26b6e8213c3b43b21333f33e3c051135e63`. These identifiers retain the measured dependency/source baseline; later builds do not inherit these measurements automatically. Both variants use the same frozen application artifact, PostgreSQL and broker containers, memcached, a 384 MiB application heap, 10% parent-based trace sampling, a 50/s fifteen-second warm-up, then the same seed-42 100/s forty-five-second workload. k6 has one CPU and 256 MiB; forty VUs are preallocated and eighty is the ceiling. Windows/Docker share one memory-constrained laptop with the telemetry stack. This is one paired local diagnostic, not a capacity estimate, a replicated benchmark, or a cloud comparison. JVM warm-up, run ordering, background activity and evolving fixture data remain confounders.

A real one-host versus two-host AWS comparison remains pending external account readiness and separate cloud authorization. The existing two-process HTTP test proves local cross-instance correctness, not host-level availability or cloud scaling.

## Observed comparison and limits

| Observation | Pool 2 | Pool 16 |
|---|---:|---:|
| Actual offered operations | 4,501 | 4,501 |
| Started/completed | 4,257 / 4,257 | 3,360 / 3,360 |
| Dropped before execution | 244 | 1,141 |
| Infrastructure errors among completed operations | 0 | 0 |
| Accepted / durably rejected bids | 967 / 97 | 813 / 27 |
| Client retry attempts | 0 | 0 |
| Logical latency p50 / p95 / p99, ms | 330 / 1,024 / 1,896.44 | 32 / 2,116 / 7,636.25 |
| Observed peak pool waiters / active connections | 74 / 2 | 63 / 16 |
| Committed outbox / unique sink effects | 969 / 969 | 815 / 815 |
| k6 exit code | 99 | 99 |

Both runs failed the zero-drop threshold and the proposed 99% offered-work objective. Completion was 94.58% and 74.65%, respectively. The sixteen-connection variant improved the median but regressed tail latency and dropped work; this is not an optimization win. Both database reconciliations passed accepted/rejected counts, monotonic price/version history, final state and complete unique notification effects.

Connection occupancy and waiting show queue pressure in both configurations. Sampled HTTP spans reached 2.15 seconds and 6.53 seconds; the recorded maximum JVM GC pauses were only 7 ms and 8 ms. The evidence does not establish the cause of the remaining delay. The shared Windows host had only 249,552 KiB and 894,240 KiB free physical memory at the two run starts. Run order, memory pressure and shared PostgreSQL/broker/telemetry work prevent attributing the regression solely to pool size. No default was changed from this single comparison. A dedicated host with OS paging/CPU and PostgreSQL wait-event capture is needed before a causal performance conclusion.

The isolated 5/s ten-second smoke passed. Each 50/s fifteen-second warm-up completed all offered work without drops/errors, but those warm-ups are not sustained capacity measurements. Constant-arrival scheduling can include a boundary iteration: the nominal 4,500 target produced 4,501 actual offered operations, and both are recorded.

The complete 150-second availability fixture recorded 149 probes, 69 deliberate failures and zero telemetry export failures. Its observed transitions were inactive, pending, firing and inactive again after successful health responses. Against an illustrative 99% probe-count target, 69 failures consumed 46.31 times that short window's budget. Earlier incomplete windows and the pool-one startup failure are retained separately; only the uninterrupted complete fixture supports the firing/recovery claim. This induced-outage ratio does not describe normal availability.

The workload and measurements ran before a subsequent dependency-advisory remediation pass. Preserve this explicit artifact baseline and use fresh compatibility smoke evidence for newer builds. Local telemetry services and the owned application were stopped after verification; Docker volumes and evidence were retained.

## Repeated isolated investigation

Further inspection of the retained slow-request traces found long gaps before and between recorded JDBC spans. That narrows the next investigation toward acquisition, scheduling and uninstrumented work; it does not prove a connection-pool cause. The old acquisition metric contained count/sum observations without finite histogram buckets, so its attempted histogram-quantile query did not yield usable p95 evidence.

The [manual Ubuntu profiling campaign](../experiments/profile/README.md) now specifies four same-artifact runs in pool order 2/16/16/2, fresh dependencies per run, real CPU/paging and PostgreSQL wait samples, explicit acquisition SLO boundaries and bounded JFR stack/GC observations. It preserves threshold failures independently of execution and durable correctness. Its histogram reader validates the actual exported unit, finite buckets and counter deltas. Its restricted JFR export excludes raw recordings and environment/property event types. This is tooling for the remaining causal investigation; a workflow definition and parser tests do not establish a new measured capacity result. The original negative results above remain valid for their original artifacts.

The pinned agent exports finite acquisition buckets only when explicit Micrometer SLO boundaries are configured; the percentile-histogram switch alone exported count/sum with an overflow bucket. A small synthetic Boot 4.1.1/agent 2.31.1 probe verified the duration binding, all 20 finite second-unit boundaries, and 500 known observations. This validates the configuration path, not a workload or capacity result. Each campaign still requires valid actual acquisition snapshots and deltas.
