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
