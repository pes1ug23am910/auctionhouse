# Isolated contention diagnostics

The manual [profiling workflow](../../.github/workflows/profile.yml) builds one application JAR and investigates the same seed-42 workload with connection pools **2, 16, 16, 2** on a fresh Ubuntu runner. It uses no AWS credentials or deployment. The source revision, JAR, agent, scripts, configuration, image identities, actual CPU model/count, available memory and kernel are recorded. Results belong to those artifacts and that runner.

Each variant creates fresh PostgreSQL, Redpanda, memcached, Collector, Tempo and Prometheus containers/volumes, then a fresh JVM. Only the pool size changes within the campaign. The JVM has a fixed 384 MiB initial/maximum heap, `ActiveProcessorCount=2`, affinity to the same two available CPUs, and 10% parent-based tracing. Compose dependency limits remain fixed. k6 has one CPU, 256 MiB and 40/80 initial/maximum virtual users. A 50/s fifteen-second warm-up and a 100/s forty-five-second measurement each create their own auction. The same authenticated bidder session is shared across load users, intentionally including JDBC-session row contention alongside the hot auction. These are bounded diagnostic workloads, not sustained production capacity measurements.

The campaign retains all four runs even if k6 returns threshold exit **99**. It records actual offered/started/completed/dropped operations, client recovery, errors and logical p50/p95/p99; setup HTTP calls do not become logical operations. Every warm-up and measurement invokes the durable database reconciliation for accepted/rejected intents, monotonic bid/version history, final auction state, outbox publication and unique sink effects.

There are two separate results:

- `executionAndOraclesPassed` requires all four executions, valid instrumentation, durable reconciliations and fixture cleanup. This controls the diagnostic execution step.
- `performanceTargetMet` requires at least 99% of actual offered work to complete with a valid outcome and p95 below 250 ms. `allPerformanceGatesMet` also requires k6's stricter zero-drop/error thresholds. These booleans, actual k6 exit codes and missed targets remain in the report and workflow summary. A separate final **Performance acceptance** step fails the workflow if any measured run misses these gates, after retaining the diagnostic artifacts. Successful diagnostic execution alone does not produce a green workflow.

Pairs compare the first two runs and the final two in reverse order. Two samples per pool can reveal order sensitivity; they cannot establish a stable distribution or attribute every difference to the pool. The default connection pool is unchanged.

## Diagnostic evidence

- Linux `/proc` CPU counters, available memory, swap/paging and major-fault counters, CPU/memory/I/O pressure, application RSS/CPU/fault counters and PostgreSQL wait/blocker groups are sampled at a nominal one-second interval. Actual timestamps, sampling errors and gaps are retained; gaps above eight seconds invalidate the instrumentation. Container resource samples occur every fifth iteration and include only owned dependencies and the load generator when present; its presence is recorded because setup/exit can fall between samples. PostgreSQL samples omit SQL text, parameters and credentials. Connection-samples are not wait duration or distinct requests.
- Micrometer acquisition histogram buckets are explicitly enabled for this experiment. Analysis reads the actual OTLP metric name, seconds unit, cumulative temporality, single pool-series identity, count/sum/buckets and export timestamps. Missing buckets, multiple/interleaved series, resets, stale snapshots, negative deltas and nonfinite values invalidate the result. The p95 is an interpolated bucket estimate over recorded export snapshots with at most seven seconds of end-boundary slack; acquisition includes every pool caller. It is distinct from client/request latency.
- A custom [JFR configuration](diagnostic.jfc) records only CPU/execution samples, thresholded parks/monitors/socket reads and GC events. The recording is capped at 16 MiB; selected JSON is limited to 64 MiB and stack depth 24. Environment variables, system properties, command lines, object contents and SQL/header values are not enabled. Before export, every nonzero recorded event type must be on the explicit allowlist. Raw JFR and raw telemetry files never enter the uploaded report. Selected event JSON is compressed; summed thread waits overlap and must not be read as request wall time.
- The measurement diagnostics include client setup/drain and the short post-load reconciliation. Their exact windows and export timestamps remain visible. Instrumentation and the load generator share the VM with dependencies; their overhead is a limitation common to the variants.

Artifacts contain hashes, selected runtime configuration, sanitized application logs, load summaries, durable oracles, resource samples and selected metric/JFR JSON. A known-credential scan must pass before the upload step is enabled. Total upload size is capped at 100 MiB and retention is seven days. Disposable databases and private telemetry/recording files are removed after each variant. The harness refuses to run outside a GitHub Linux runner or write to an existing/non-temporary output directory.

Run the lightweight analysis/failure-path contracts independently:

```sh
python -m unittest discover -s experiments/profile -p 'test_*.py' -v
```

Use **Actions → Bounded contention profiling (manual) → Run workflow** for actual collection. There are no deployment inputs. Hosted timing and instrumentation validation are established only by the resulting run artifacts, not by the helper tests or workflow definition.
