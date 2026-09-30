# Seeded local load workload

Use Grafana k6 2.3.0. The pinned image is `grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34`. This script is part of the local observability experiment; a checked-in workload is not a passing performance result.

The default workload schedules 20 logical operations per second for 60 seconds, with 40 preallocated VUs and a maximum of 80. A deterministic seed chooses approximately 75% authenticated detail reads and 25% bids on one newly created auction. A new auction isolates each run. Amounts ascend by global iteration identity; concurrent arrival order can legitimately reject an older/lower offer with HTTP 422. Those durable business outcomes are counted separately from infrastructure failures.

Prepare the local application with its demo accounts and pass `AUCTIONHOUSE_DEMO_PASSWORD` through the environment, without including its value in a command, trace or report. The script keeps login cookies and CSRF tokens in memory. The default `local-demo` mode refuses remote target URLs; loopback and Docker's host alias are accepted.

```powershell
$env:BASE_URL = 'http://127.0.0.1:8080'
$env:SEED = '42'
$env:RATE = '20'
$env:DURATION_SECONDS = '60'
$env:COMPARISON_LABEL = 'pool-16'
$env:SUMMARY_PATH = '<private-evidence-directory>/pool-16.json'
k6 run --no-usage-report experiments/load/k6-workload.js
```

Run from the repository root with k6 2.3.0 installed. For a container runner, mount the script read-only and a private output directory, pass the already-populated password environment variable by name, and target `http://host.docker.internal:<port>`. Keep the load generator on the same host and resource allocation across the comparison.

For a controlled pool-size comparison, launch one application configuration with a pool of 2 and another with a pool of 16, holding seed, rate, duration, VU bounds, database, broker/cache settings and telemetry configuration fixed. Start only one measured workload at a time. Record JVM/container CPU/memory limits, actual pool settings, source revision, generator version and raw logs. A fresh auction is created in each run; the operation sequence is otherwise determined by the same seed. Repeat runs before generalizing.

The JSON summary distinguishes the nominal scheduled target (`rate * durationSeconds`) from actual offered operations (`started + dropped`), and records started/completed/dropped operations, browse/bid counts, accepted and rejected bids, retries, recovered outcomes, logical p50/p95/p99 and errors. Boundary scheduling can produce one additional arrival: a local k6 2.3.0 smoke run at 5/s for 10 seconds started and completed 51 operations against a nominal target of 50. Do not treat the nominal target as the observed offered count. `infrastructureErrorCount` counts true error predicates; `successfulOperationCount` counts false predicates (the raw k6 Rate fields name these `passes` and `fails`, respectively). Logical latency includes status reconciliation and the bounded retry. HTTP totals also include setup calls. A lost bid response first queries the original intent and permits one same-key retry only after an explicit unknown result. It never replaces an ambiguous key. The run fails its thresholds for any dropped arrival or an infrastructure error rate of 1% or more.

A 60-second run fits inside the configured local access-token lifetime. This script does not model long-lived user sessions, OIDC provider load, payment processing or distributed generators. The [host comparison harness](../hosts/README.md) supplies the explicit HTTPS `session` mode, fresh authenticated fixtures, observed AWS inventory and API reconciliation for one versus two application hosts. That implementation still requires an actual authorized AWS run; local fixtures do not establish scaling behavior.

The executor and summary behavior follow the official [constant-arrival-rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/) and [custom summary](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/) contracts. See [LOAD-TEST](../../docs/LOAD-TEST.md) for observability and interpretation.
