# Reproducible service/database contention

This opt-in experiment runs 20 repetitions of 200 clients for each transaction strategy. It uses two independent AuctionService objects and JDBC pools in one JVM, sharing one isolated PostgreSQL 18.6 container. It does not establish HTTP throughput or cross-process behavior.

From the repository root, choose a fresh evidence path:

    ./experiments/contention/run.ps1 -OutputDirectory E:/measurements/contention-run-001 -BackgroundActivity "Describe other running load"

The ordinary unit/integration suites skip this experiment unless AUCTIONHOUSE_CONTENTION_EXPERIMENT=true. The script opts in only for its child Gradle execution and restores previous environment values. Docker is required.

The default seed is 20261001, with seed + repetition indexing. Each run shuffles 200 distinct increasing bid amounts and assigns 200 distinct deterministic actor IDs to two service instances alternately. Both strategies use the same amount fixture for a given repetition. Their execution order alternates. All 200 virtual-thread clients reach one release barrier before work begins. OS scheduling and production retry jitter are not deterministic.

Each instance has a warmed pool of 16 connections, matching the application default. Therefore 200 simultaneous clients do not imply 200 simultaneous database sessions. The 5-second connection acquisition timeout and 8-attempt transaction bound are also the application defaults. PostgreSQL has 2 CPUs and 1 GiB memory limits, 100 maximum connections and 128 MiB shared buffers. Pool warmup occurs before timing; no bidding warmup repetitions are discarded.

Each request records release offset, latency, actual transaction attempts, retry count, exact business outcome or failure. Retry exhaustion remains a reported failure; the harness does not silently issue a new intent or remove failed requests from latency summaries. Percentiles use nearest-rank selection across all 200 timed requests, including failures. It captures peak concurrent service calls and end-to-end batch wall time.

An independent post-run SQL oracle reads committed bid history, retained intent outcomes and outbox rows. It checks:

- contiguous accepted versions and increasing prices satisfying opening price/minimum increment;
- accepted replies and cross-instance retained outcomes matching exactly one committed bid and one accepted outbox event;
- rejected bids being too low at their recorded observed auction version;
- failed requests having no retained committed result;
- retry counts staying within the configured bound;
- final auction version, price and bidder matching history; closure then names that bidder as winner.

Closure uses a fixture-only deadline update after timing. No existing application database is edited. Fresh container data is removed by container lifecycle. Reports retain actor IDs, seeds, outcomes, histories and source hashes.

Per-run JSON reports and progress.json are written after every completed run. summary.json includes every completed run and any oracle violation. environment.json records JVM, database settings, container limits, Git state, source hashes and declared host activity. console.log, exit-code.txt, JUnit XML and PostgreSQL logs are retained. Preserve failed directories and choose a different output directory for a corrected run.

These local results are workload-specific. Connection pool queueing, scheduler behavior, warm cache state and retries contribute to latency. This harness is a transaction correctness and contention comparison, not a production capacity claim.

## Separate two-process HTTP check

The sibling script experiments/http-instances.mjs accepts AUCTIONHOUSE_DEMO_PASSWORD, AUCTIONHOUSE_INSTANCE_OUTPUT and optional AUCTIONHOUSE_INSTANCE_ORIGINS (default localhost ports 8080 and 8082). Start two local application JVMs against the same database first. The password stays in process memory; cookies, CSRF values and bearer tokens are not written to evidence.

It obtains CSRF/session state on one instance and logs in on the other, reuses application cookies across both, sends twelve concurrent identical intents alternating instances, verifies one bid effect, discards a later successful bid response body, reconciles that intent on the other instance, verifies replay and rejects a changed payload with HTTP 409. It retains public auction payloads and status codes.

Discarding a body after successful response headers is a deliberate client-loss fixture. It does not establish behavior for arbitrary TCP loss or application process crashes. HTTP bid history proves bid effects; outbox exactly-once assertions belong to the separate database checks.

## Observed local comparison (2026-10-01)

The full 40-run check completed all 8,000 requests with zero committed-history oracle violations. All runs reached 200 active service calls. This is a correctness-oracle pass, not a claim that all requests succeeded.

| Strategy | Requests | Accepted | Durable rejection | Retry exhausted | Transaction retries | All-request p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ROW_LOCKS | 4000 | 122 | 3878 | 0 | 0 | 1514.6991 ms |
| SERIALIZABLE | 4000 | 97 | 1279 | 2624 | 24447 | 1427.8695 ms |

The unchanged 8-attempt bound exhausted for 65.6% of serializable requests. No exhausted request left a partial bid, intent or outbox effect. Failures are included in latency percentiles, so the lower serializable p95 is not evidence that it is faster at completing the workload. Row locking completed every request with an accepted or durable rejected business outcome in this local hot-row experiment.

The original 8080 application, Compose PostgreSQL and Redpanda remained running during measurement. The experiment used two service objects in one JVM; the separate HTTP test established behavior across two actual application JVMs. No production capacity or general isolation-strategy performance claim follows from these local observations.
