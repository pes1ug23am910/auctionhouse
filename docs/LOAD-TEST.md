# Load measurements

The seeded k6 workload and invocation are in [the load experiment](../experiments/load/README.md).
[Observability](OBSERVABILITY.md) records the exact application/workload hashes,
host conditions, trace proof, metric semantics, query validation and alert recovery.

On 2026-10-01, one paired local diagnostic changed the connection pool from
two to sixteen while retaining the same application JAR, 384 MiB heap,
memcached, 10% trace sampling and workload seed. Each variant had a 50/s
fifteen-second warm-up followed by 100 offered operations/s for forty-five
seconds, with forty preallocated and at most eighty virtual users.

| Measurement | Pool 2 | Pool 16 |
|---|---:|---:|
| Actual offered operations | 4,501 | 4,501 |
| Completed operations | 4,257 | 3,360 |
| Dropped operations | 244 | 1,141 |
| Infrastructure errors among completed operations | 0 | 0 |
| Logical latency p50 / p95 / p99, ms | 330 / 1,024 / 1,896.44 | 32 / 2,116 / 7,636.25 |
| Peak observed waiting / active connections | 74 / 2 | 63 / 16 |
| Outbox events / unique notification effects | 969 / 969 | 815 / 815 |
| k6 exit code | 99 | 99 |

Both variants failed the zero-drop gate and the proposed 99% offered-work
completion objective. Both committed-history and event-effect reconciliations
passed. The lower median with sixteen connections accompanied worse tail
latency and more dropped work; it is not an optimization win.

This Windows/Docker laptop was under memory pressure, with only 249,552 KiB
and 894,240 KiB free physical memory at the respective run starts. Run order,
JVM warm-up, background work and shared services prevent a causal claim about
pool size. The normal default remains sixteen. No production capacity or
cloud scaling claim follows from this single diagnostic.

The measurements preceded the subsequent dependency security updates and
belong to the artifact hash recorded in the observability report. Newer
builds do not inherit these timing results. Actual one-host versus two-host
AWS measurements remain pending account readiness and cloud authorization.

## Host comparison tooling

The [host runner](../experiments/hosts/README.md) binds the same seeded workload to a verified AWS ALB and one or two actual application instances. It records shared dependencies, immutable artifacts, per-host settings, observed request counters, client constraints and source-cut reconciliation. Local contract tests and a small actual HTTPS session-workload regression pass. Real AWS timing, routing and scaling evidence remains pending; these fixtures do not satisfy that requirement.

## Repeated local-topology investigation

The separate [manual Ubuntu campaign](../experiments/profile/README.md) runs pool order 2/16/16/2 with a single newly built JAR and fresh dependency data for each variant. It captures Linux resource pressure, PostgreSQL waits, finite acquisition histogram deltas and selected JFR events. Both run orders, threshold exit codes and durable reconciliations remain visible. Diagnostic execution and performance acceptance are separate workflow steps: missed offered-work/latency targets fail the final acceptance step after artifacts are retained. Actual hosted results must be read from their source- and artifact-bound run report. This experiment uses one VM with a local topology and does not substitute for the pending AWS host comparison.

The first campaign with complete acquisition, resource and JFR diagnostics is [run 36794785555](https://github.com/pes1ug23am910/auctionhouse/actions/runs/36794785555), source `85f0ad0255db2be9fa73750bb3dd1756cb062504`. All four executions and eight durable reconciliations passed. In pool order 2/16/16/2, p95 was 188/210.9/77/241 ms and dropped work was 13/38/0/10 out of 4,501 offered operations each. All four met the proposed 99%/250-ms objective, but only the third met every stricter k6 gate; the workflow correctly failed performance acceptance. [The measured pool trade-offs and environment limits](OBSERVABILITY.md#completed-isolated-campaign) explain why this is bounded profiling evidence and why the default pool is unchanged.
